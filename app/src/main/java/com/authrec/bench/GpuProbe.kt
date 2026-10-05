package com.authrec.bench

import android.hardware.camera2.CameraMetadata
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Times the per-frame GPU work AuthRec will do, at the real RAW size:
 * RAW upload → demosaic → colour matrix → log curve → 3D LUT. Runs headless on a
 * pbuffer context, so it measures raw GPU throughput without preview or encoder load.
 */
object GpuProbe {

    private const val WARMUP = 10
    private const val ITERATIONS = 60
    private const val LUT_SIZE = 33

    fun run(r: Report, target: RawTarget?) {
        r.section("GPU")
        val w = target?.size?.width ?: Targets.OPEN_GATE_W
        val h = target?.size?.height ?: Targets.OPEN_GATE_H
        val white = target?.whiteLevel?.takeIf { it > 0 } ?: 1023
        val black = target?.blackLevel ?: 64

        val egl = Egl.create()
        try {
            r.kv("Renderer", GLES30.glGetString(GLES30.GL_RENDERER))
            r.kv("GL version", GLES30.glGetString(GLES30.GL_VERSION))
            val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
            listOf("GL_EXT_texture_norm16", "GL_EXT_color_buffer_half_float", "GL_EXT_YUV_target", "GL_EXT_shader_framebuffer_fetch")
                .forEach { r.kv(it, if (it in ext) "yes" else "no") }
            val maxInvocations = IntArray(1).also { GLES31.glGetIntegerv(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, it, 0) }[0]
            r.kv("Max compute invocations / group", maxInvocations)

            val pipeline = Pipeline(w, h, black, white, redOffset(target?.cfa))
            try {
                pipeline.fillRandomRaw(white)

                val fullCompute = pipeline.time(upload = false, superpixel = false)
                val fullTotal = pipeline.time(upload = true, superpixel = false)
                val spTotal = pipeline.time(upload = true, superpixel = true)

                r.kv("Open gate ${w}×$h: compute only", "%.2f ms".format(fullCompute))
                r.kv("Open gate ${w}×$h: upload + compute", "%.2f ms".format(fullTotal))
                r.kv("Superpixel ${w / 2}×${h / 2}: upload + compute", "%.2f ms".format(spTotal))

                verdict(r, "Open gate demosaic+log+LUT", fullTotal)
                verdict(r, "Superpixel log+LUT", spTotal)
            } finally {
                pipeline.release()
            }
        } finally {
            egl.release()
        }
    }

    /**
     * Preview drawing and the encoder share the GPU and memory bus with this pass, so it should
     * use at most about half a frame at 30 fps.
     */
    private fun verdict(r: Report, name: String, ms: Double) {
        val maxFps = 1000.0 / ms
        val v = when {
            ms <= 1000.0 / 30 / 2 -> Report.Verdict.PASS
            ms <= 1000.0 / 30 -> Report.Verdict.LIMITED
            else -> Report.Verdict.FAIL
        }
        r.verdict(v, "GPU $name: %.1f ms/frame (≈%.0f fps ceiling)".format(ms, maxFps))
    }

    /** Where the red pixel sits in each 2×2 CFA block, for the shader's site parity. */
    private fun redOffset(cfa: Int?): Pair<Int, Int> = when (cfa) {
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> 1 to 0
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> 0 to 1
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> 1 to 1
        else -> 0 to 0 // RGGB
    }

    private class Pipeline(val w: Int, val h: Int, black: Int, white: Int, red: Pair<Int, Int>) {
        private val rawTex = genTexture()
        private val outTex = genTexture()
        private val spOutTex = genTexture()
        private val lutTex = genTexture()
        private val fullProgram = compute(Shaders.common + Shaders.demosaicMain)
        private val spProgram = compute(Shaders.common + Shaders.superpixelMain)
        private val rawData: ByteBuffer = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder())

        init {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTex)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R16UI, w, h)
            nearest(GLES30.GL_TEXTURE_2D)

            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, outTex)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, w, h)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, spOutTex)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, w / 2, h / 2)

            // Identity LUT; the cost of a 3D lookup doesn't depend on its contents.
            val n = LUT_SIZE
            val lut = ByteBuffer.allocateDirect(n * n * n * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (b in 0 until n) for (g in 0 until n) for (rr in 0 until n) {
                lut.put(rr / (n - 1f)).put(g / (n - 1f)).put(b / (n - 1f)).put(1f)
            }
            lut.rewind()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
            GLES30.glTexStorage3D(GLES30.GL_TEXTURE_3D, 1, GLES30.GL_RGBA16F, n, n, n)
            GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_3D, 0, 0, 0, 0, n, n, n, GLES30.GL_RGBA, GLES30.GL_FLOAT, lut)
            for (p in listOf(GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_TEXTURE_MAG_FILTER)) {
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_LINEAR)
            }
            for (p in listOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R)) {
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_CLAMP_TO_EDGE)
            }

            for (prog in listOf(fullProgram, spProgram)) {
                GLES30.glUseProgram(prog)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uBlack"), black.toFloat())
                GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uWhite"), white.toFloat())
                GLES30.glUniform2i(GLES30.glGetUniformLocation(prog, "uRedOffset"), red.first, red.second)
                // Placeholder white balance + camera→working-space matrix; real values come from the sensor calibration.
                GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(prog, "uCcm"), 1, false,
                    floatArrayOf(1.8f, -0.1f, 0f, -0.5f, 1.2f, -0.4f, -0.3f, -0.1f, 1.4f), 0)
            }
            checkGl("pipeline setup")
        }

        fun fillRandomRaw(white: Int) {
            val shorts = rawData.asShortBuffer()
            val rnd = Random(42)
            for (i in 0 until w * h) shorts.put(rnd.nextInt(white + 1).toShort())
            rawData.rewind()
            upload()
        }

        private fun upload() {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTex)
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, rawData)
        }

        private fun dispatch(superpixel: Boolean) {
            val prog = if (superpixel) spProgram else fullProgram
            val ow = if (superpixel) w / 2 else w
            val oh = if (superpixel) h / 2 else h
            GLES30.glUseProgram(prog)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTex)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
            GLES31.glBindImageTexture(0, if (superpixel) spOutTex else outTex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
            GLES31.glDispatchCompute((ow + 15) / 16, (oh + 15) / 16, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
        }

        /** Average milliseconds per frame. */
        fun time(upload: Boolean, superpixel: Boolean): Double {
            repeat(WARMUP) { if (upload) upload(); dispatch(superpixel) }
            GLES30.glFinish()
            val t0 = SystemClock.elapsedRealtimeNanos()
            repeat(ITERATIONS) { if (upload) upload(); dispatch(superpixel) }
            GLES30.glFinish()
            checkGl("timing")
            return (SystemClock.elapsedRealtimeNanos() - t0) / 1e6 / ITERATIONS
        }

        fun release() {
            GLES30.glDeleteTextures(4, intArrayOf(rawTex, outTex, spOutTex, lutTex), 0)
            GLES30.glDeleteProgram(fullProgram)
            GLES30.glDeleteProgram(spProgram)
        }
    }

    private fun genTexture() = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]

    private fun nearest(target: Int) {
        // Integer textures are incomplete with linear filtering.
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
    }

    private fun compute(src: String): Int {
        val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        GLES31.glShaderSource(shader, src)
        GLES31.glCompileShader(shader)
        val ok = IntArray(1).also { GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, it, 0) }[0]
        check(ok != 0) { "compute shader compile failed: ${GLES31.glGetShaderInfoLog(shader)}" }
        val prog = GLES31.glCreateProgram()
        GLES31.glAttachShader(prog, shader)
        GLES31.glLinkProgram(prog)
        GLES31.glDeleteShader(shader)
        val linked = IntArray(1).also { GLES31.glGetProgramiv(prog, GLES31.GL_LINK_STATUS, it, 0) }[0]
        check(linked != 0) { "compute program link failed: ${GLES31.glGetProgramInfoLog(prog)}" }
        return prog
    }

    private fun checkGl(where: String) {
        val err = GLES30.glGetError()
        check(err == GLES30.GL_NO_ERROR) { "GL error 0x${err.toString(16)} during $where" }
    }

    /** Minimal headless EGL context for GLES 3.1+ compute. */
    private class Egl private constructor(
        private val display: android.opengl.EGLDisplay,
        private val context: android.opengl.EGLContext,
        private val surface: android.opengl.EGLSurface,
    ) {
        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }

        companion object {
            fun create(): Egl {
                val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)) { "eglInitialize failed" }
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                EGL14.eglChooseConfig(display, intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_NONE,
                ), 0, configs, 0, 1, count, 0)
                check(count[0] > 0) { "no GLES3 EGL config" }
                val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
                val surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
                return Egl(display, context, surface)
            }
        }
    }
}

private object Shaders {

    val common = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x = 16, local_size_y = 16) in;

        layout(binding = 0) uniform highp usampler2D uRaw;
        layout(binding = 1) uniform mediump sampler3D uLut;
        layout(rgba16f, binding = 0) writeonly uniform highp image2D uOut;

        uniform float uBlack;
        uniform float uWhite;
        uniform ivec2 uRedOffset;
        uniform mat3 uCcm;

        float px(ivec2 p) {
            p = clamp(p, ivec2(0), textureSize(uRaw, 0) - 1);
            return (float(texelFetch(uRaw, p, 0).r) - uBlack) / (uWhite - uBlack);
        }

        // Placeholder log curve (~14 stops into 0..1); AuthRec's real curve gets designed later.
        vec3 toLog(vec3 x) {
            return clamp((log2(max(x, 0.0) + 1.0 / 4096.0) + 12.0) / 14.0, 0.0, 1.0);
        }

        vec3 grade(vec3 cam) {
            vec3 lin = max(uCcm * cam, 0.0);
            vec3 lg = toLog(lin);
            const float n = 33.0;
            return texture(uLut, lg * ((n - 1.0) / n) + 0.5 / n).rgb;
        }
    """.trimIndent() + "\n"

    /** Malvar-He-Cutler 5×5 linear demosaic: 13 taps, keeps edges far better than bilinear. */
    val demosaicMain = """
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = textureSize(uRaw, 0);
            if (p.x >= size.x || p.y >= size.y) return;

            float C  = px(p);
            float N  = px(p + ivec2(0, -1)), S  = px(p + ivec2(0, 1));
            float W  = px(p + ivec2(-1, 0)), E  = px(p + ivec2(1, 0));
            float NN = px(p + ivec2(0, -2)), SS = px(p + ivec2(0, 2));
            float WW = px(p + ivec2(-2, 0)), EE = px(p + ivec2(2, 0));
            float NW = px(p + ivec2(-1, -1)), NE = px(p + ivec2(1, -1));
            float SW = px(p + ivec2(-1, 1)),  SE = px(p + ivec2(1, 1));

            float cross = N + S + W + E;
            float diag = NW + NE + SW + SE;
            float axial2 = NN + SS + WW + EE;

            // Green at a red/blue site, and the opposite colour at a red/blue site.
            float gAtRB = (4.0 * C + 2.0 * cross - axial2) / 8.0;
            float oppAtRB = (6.0 * C + 2.0 * diag - 1.5 * axial2) / 8.0;
            // At a green site: the colour on the same row, and the colour on the same column.
            float rowAtG = (5.0 * C + 4.0 * (W + E) - diag - (WW + EE) + 0.5 * (NN + SS)) / 8.0;
            float colAtG = (5.0 * C + 4.0 * (N + S) - diag - (NN + SS) + 0.5 * (WW + EE)) / 8.0;

            ivec2 site = (p + uRedOffset) & 1;
            vec3 rgb;
            if (site == ivec2(0, 0))      rgb = vec3(C, gAtRB, oppAtRB);  // red site
            else if (site == ivec2(1, 1)) rgb = vec3(oppAtRB, gAtRB, C);  // blue site
            else if (site == ivec2(1, 0)) rgb = vec3(rowAtG, C, colAtG);  // green on a red row
            else                          rgb = vec3(colAtG, C, rowAtG);  // green on a blue row

            imageStore(uOut, p, vec4(grade(rgb), 1.0));
        }
    """.trimIndent()

    /** 2×2 superpixel: one RGB pixel per CFA block, no interpolation. */
    val superpixelMain = """
        void main() {
            ivec2 o = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = textureSize(uRaw, 0) / 2;
            if (o.x >= size.x || o.y >= size.y) return;

            ivec2 base = o * 2;
            ivec2 rp = base + uRedOffset;
            ivec2 bp = base + (ivec2(1) - uRedOffset);
            ivec2 g1 = base + ivec2(1 - uRedOffset.x, uRedOffset.y);
            ivec2 g2 = base + ivec2(uRedOffset.x, 1 - uRedOffset.y);
            vec3 rgb = vec3(px(rp), 0.5 * (px(g1) + px(g2)), px(bp));

            imageStore(uOut, o, vec4(grade(rgb), 1.0));
        }
    """.trimIndent()
}
