package com.authrec.record

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES30
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * What this phone's hardware encoder really records, found once per firmware by short test
 * encodes (nothing is saved), like LensProbe does for lenses. Android's capability lists can be
 * wrong: the Xiaomi 12's HEVC encoder is listed for 7680×7680 but takes 4096×2176 at most, and
 * beyond that accepts the settings and puts out nothing (2026-10-11).
 *
 * Every test encodes something like grainy footage (a moving gradient with fine grain) as fast
 * as the encoder takes it: past its limit the Xiaomi 12's encoder drops to ~4 frames/s, which
 * flat test frames hid (40 of 40 out at 4208×3120). Pure noise was too hard instead: the X14 had
 * to put out 180–390 Mbps for it and managed only 16–23 fps, where it records 4K at 30.
 * - Size: the largest 4K frame any lens makes, at 50 Mbps; if that doesn't keep up, the first of
 *   4096×2304, 4096×2176 and 3840×2160 that does, together with that frame shrunk into it.
 * - 10-bit: the same through a recordable 10-bit EGL surface.
 * - Bitrate: the highest bitrate offered, then 100 Mbps if the encoder won't take that; what
 *   comes out is what it really delivers (grain needs more than the encoder is asked for).
 *
 * The runtime fallbacks (silent encoder → smaller size, 10-bit failing → 8-bit) stay for
 * anything this misses.
 */
internal object RecordingCheck {
    /** Bump when the check changes so phones check again. */
    private const val VERSION = 4
    private const val PREFS = "reccheck"
    private const val TAG = "AuthRec"
    private const val FPS = 30
    private const val FRAMES = 40
    private const val EGL_RECORDABLE_ANDROID = 0x3142

    /**
     * [box]: the largest frame box the encoder takes (null: everything the lenses make);
     * [tenBit]: 10-bit HEVC records; [maxMbps]: what it delivers at most (0: not measured).
     */
    data class Result(val box: Pair<Int, Int>?, val tenBit: Boolean, val maxMbps: Int, val summary: String)

    fun load(context: Context): Result? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString("fingerprint", null) != Build.FINGERPRINT || p.getInt("version", 0) != VERSION) return null
        val bw = p.getInt("boxW", 0)
        val bh = p.getInt("boxH", 0)
        return Result(if (bw > 0) bw to bh else null, p.getBoolean("tenBit", true), p.getInt("maxMbps", 0), p.getString("summary", "") ?: "")
    }

    fun clear(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()

    private fun save(context: Context, r: Result) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("fingerprint", Build.FINGERPRINT).putInt("version", VERSION)
        .putInt("boxW", r.box?.first ?: 0).putInt("boxH", r.box?.second ?: 0)
        .putBoolean("tenBit", r.tenBit).putInt("maxMbps", r.maxMbps).putString("summary", r.summary).apply()

    /**
     * Runs the check (a few seconds; ~15 on a phone whose encoder stays silent at full size) on
     * the calling thread, which it uses for its own EGL context. [largest]: the biggest 4K frame
     * a lens makes; [offeredMbps]: the highest bitrate the app offers. Returns null if
     * [cancelled] turned true (a recording started), and saves nothing then.
     */
    fun run(context: Context, largest: Pair<Int, Int>, offeredMbps: Int, cancelled: () -> Boolean): Result? {
        val mime = MediaFormat.MIMETYPE_VIDEO_HEVC
        val info = Recorder.hardwareEncoder(mime) ?: return null
        val notes = mutableListOf<String>()
        fun test(w: Int, h: Int, tenBit: Boolean = false, mbps: Int = 50): Outcome? {
            if (cancelled()) return null
            val o = encode(info, mime, w, h, tenBit, mbps, cancelled)
            notes += "${w}x$h${if (tenBit) " 10-bit" else ""} $mbps Mbps: " +
                (o?.let { "${it.frames}/$FRAMES frames at %.0f fps, %.0f Mbps".format(it.fps, it.mbps) } ?: "no surface")
            return o
        }
        // Keeps up: nearly every frame out, at well over the 30 fps recordings need minus the
        // pipeline's latency at the end of a 40-frame burst.
        fun works(o: Outcome?) = o != null && o.frames >= FRAMES * 3 / 4 && o.fps >= 22

        val (lw, lh) = largest
        var box: Pair<Int, Int>? = null
        var size = largest
        val full = test(lw, lh)
        if (cancelled()) return null
        if (!works(full)) {
            for (candidate in listOf(4096 to 2304, 4096 to 2176, 3840 to 2160)) {
                val fitted = Recorder.fitInside(lw, lh, candidate.first, candidate.second)
                if (works(test(candidate.first, candidate.second)) && works(test(fitted.first, fitted.second))) {
                    box = candidate
                    size = fitted
                    break
                }
                if (cancelled()) return null
            }
        }
        val tenBit = works(test(size.first, size.second, tenBit = true))
        // VBR on noise lands at or above the target where the encoder can (the X14 put out 339 Mbps
        // for 150); well under it means a ceiling, and no output at all a bitrate it won't take.
        var maxMbps = 0
        for (mbps in listOf(offeredMbps, 100).distinct()) {
            val o = test(size.first, size.second, mbps = mbps)
            if (cancelled()) return null
            if (works(o)) {
                if (o!!.mbps < mbps * 0.8 || mbps < offeredMbps) maxMbps = minOf(mbps, o.mbps.toInt().coerceAtLeast(1))
                break
            }
            maxMbps = mbps - 1 // this one failed outright: the next try (or nothing) is the ceiling
        }
        if (maxMbps >= offeredMbps) maxMbps = 0
        val result = Result(box, tenBit, maxMbps, notes.joinToString("; "))
        save(context, result)
        return result
    }

    private class Outcome(val frames: Int, val mbps: Double, val fps: Double)

    /**
     * Encodes up to [FRAMES] frames of a moving gradient with grain at [w]×[h] through a GL
     * surface, as fast as the encoder takes them, and counts what comes out and how fast (from
     * the 10th frame out on: start-up says nothing about the steady rate). Gives up on an
     * encoder that's silent for 1.5 s or takes over 4 s. Null: no EGL config for the surface
     * (10-bit on the Xiaomi 12).
     */
    private fun encode(info: MediaCodecInfo, mime: String, w: Int, h: Int, tenBit: Boolean, mbps: Int,
                       cancelled: () -> Boolean): Outcome? {
        val caps = info.getCapabilitiesForType(mime).videoCapabilities
        // Exactly what a recording asks for: the X12 encoded test frames at 4208×3120 without the
        // recording's colour tags while real recordings at 4096×3072 put out nothing.
        val format = Recorder.videoFormat(mime, if (tenBit) CodecProfileLevel.HEVCProfileMain10 else CodecProfileLevel.HEVCProfileMain,
            w, h, (mbps * 1_000_000).coerceAtMost(caps?.bitrateRange?.upper ?: Int.MAX_VALUE), FPS)
        val codec = runCatching { MediaCodec.createByCodecName(info.name) }.getOrElse { return Outcome(0, 0.0, 0.0) }
        var gl: Gl? = null
        try {
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                return Outcome(0, 0.0, 0.0)
            }
            val input = codec.createInputSurface()
            codec.start()
            gl = Gl.create(input, tenBit) ?: run {
                input.release()
                return null
            }
            val stats = Stats()
            val t0 = SystemClock.elapsedRealtime()
            for (i in 0 until FRAMES) {
                val ms = SystemClock.elapsedRealtime() - t0
                if (cancelled() || ms > 4000 || (ms > 1500 && stats.frames == 0)) break
                gl.draw(i, i * 1_000_000_000L / FPS)
                drain(codec, stats, 0)
            }
            codec.signalEndOfInputStream()
            val deadline = SystemClock.elapsedRealtime() + if (stats.frames > 0) 2000 else 500
            while (!stats.eos && SystemClock.elapsedRealtime() < deadline) drain(codec, stats, 10_000)
            // The first frames (key frame, rate control settling) say little about the steady rate.
            val counted = stats.frames - 5
            val mbps = if (counted > 0) stats.laterBytes * 8.0 / (counted.toDouble() / FPS) / 1e6 else 0.0
            val steady = stats.frames - 10
            val seconds = (stats.lastOutMs - stats.tenthOutMs) / 1000.0
            val fps = if (steady >= 5 && seconds > 0) steady / seconds else 0.0
            return Outcome(stats.frames, mbps, fps)
        } catch (e: Exception) {
            Log.w(TAG, "recording check ${w}x$h", e)
            return Outcome(0, 0.0, 0.0)
        } finally {
            gl?.release()
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private class Stats {
        var frames = 0
        var laterBytes = 0L
        var eos = false
        /** When the 10th and the last frame came out (elapsedRealtime ms). */
        var tenthOutMs = 0L
        var lastOutMs = 0L
    }

    private fun drain(codec: MediaCodec, stats: Stats, timeoutUs: Long) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, timeoutUs)
            if (idx < 0) return
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                if (stats.frames >= 5) stats.laterBytes += info.size
                stats.frames++
                stats.lastOutMs = SystemClock.elapsedRealtime()
                if (stats.frames == 10) stats.tenthOutMs = stats.lastOutMs
            }
            codec.releaseOutputBuffer(idx, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                stats.eos = true
                return
            }
        }
    }

    /**
     * A context of its own on the shared EGL display, drawing into the encoder's surface. It
     * never terminates the display: the camera's renderer uses the same one.
     */
    private class Gl private constructor(
        private val display: android.opengl.EGLDisplay,
        private val context: android.opengl.EGLContext,
        private val surface: android.opengl.EGLSurface,
        private val input: android.view.Surface,
        private val program: Int,
    ) {
        fun draw(i: Int, ptsNs: Long) {
            GLES30.glUseProgram(program)
            GLES30.glUniform1ui(GLES30.glGetUniformLocation(program, "uSeed"), i)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            EGLExt.eglPresentationTimeANDROID(display, surface, ptsNs)
            EGL14.eglSwapBuffers(display, surface)
        }

        fun release() {
            GLES30.glDeleteProgram(program)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            input.release()
        }

        companion object {
            private const val VERTEX = """#version 300 es
                void main() {
                    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                }"""

            // A slowly moving colour gradient with fresh per-pixel grain (±4 levels) every frame:
            // about what grainy footage asks of an encoder.
            private const val NOISE = """#version 300 es
                precision highp float;
                precision highp int;
                uniform uint uSeed;
                out vec4 colour;
                void main() {
                    uvec2 p = uvec2(gl_FragCoord.xy);
                    uint x = p.x * 1664525u + p.y * 1013904223u + uSeed * 2654435761u;
                    x ^= x >> 16; x *= 0x7feb352du; x ^= x >> 15; x *= 0x846ca68bu; x ^= x >> 16;
                    vec3 grain = (vec3(float(x & 255u), float((x >> 8) & 255u), float((x >> 16) & 255u)) / 255.0 - 0.5) * (8.0 / 255.0);
                    vec2 uv = gl_FragCoord.xy / 1024.0 + float(uSeed) * 0.01;
                    vec3 base = 0.5 + 0.35 * vec3(sin(uv.x * 3.0), sin(uv.y * 2.0 + 1.0), sin((uv.x + uv.y) * 1.5 + 2.0));
                    colour = vec4(clamp(base + grain, 0.0, 1.0), 1.0);
                }"""

            fun create(input: android.view.Surface, tenBit: Boolean): Gl? {
                val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)
                val bits = if (tenBit) intArrayOf(10, 10, 10, 2) else intArrayOf(8, 8, 8, 8)
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                EGL14.eglChooseConfig(display, intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RED_SIZE, bits[0], EGL14.EGL_GREEN_SIZE, bits[1],
                    EGL14.EGL_BLUE_SIZE, bits[2], EGL14.EGL_ALPHA_SIZE, bits[3],
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE,
                ), 0, configs, 0, 1, count, 0)
                if (count[0] == 0) return null
                val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                if (context == EGL14.EGL_NO_CONTEXT) return null
                val surface = EGL14.eglCreateWindowSurface(display, configs[0], input, intArrayOf(EGL14.EGL_NONE), 0)
                if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    EGL14.eglDestroyContext(display, context)
                    return null
                }
                val program = compile()
                if (program == 0) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(display, surface)
                    EGL14.eglDestroyContext(display, context)
                    return null
                }
                return Gl(display, context, surface, input, program)
            }

            private fun compile(): Int {
                fun shader(type: Int, src: String): Int {
                    val s = GLES30.glCreateShader(type)
                    GLES30.glShaderSource(s, src.trimIndent())
                    GLES30.glCompileShader(s)
                    val ok = IntArray(1)
                    GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
                    if (ok[0] == 0) {
                        Log.w(TAG, "recording check shader: " + GLES30.glGetShaderInfoLog(s))
                        GLES30.glDeleteShader(s)
                        return 0
                    }
                    return s
                }
                val vs = shader(GLES30.GL_VERTEX_SHADER, VERTEX)
                val fs = shader(GLES30.GL_FRAGMENT_SHADER, NOISE)
                if (vs == 0 || fs == 0) return 0
                val p = GLES30.glCreateProgram()
                GLES30.glAttachShader(p, vs)
                GLES30.glAttachShader(p, fs)
                GLES30.glLinkProgram(p)
                GLES30.glDeleteShader(vs)
                GLES30.glDeleteShader(fs)
                val ok = IntArray(1)
                GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
                return if (ok[0] != 0) p else 0.also { GLES30.glDeleteProgram(p) }
            }
        }
    }
}
