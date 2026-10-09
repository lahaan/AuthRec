package com.authrec.gl

import android.opengl.GLES30
import android.os.SystemClock
import android.util.Log

/** A control's glass shape in surface pixels (top-left origin), drawn only inside [clip]. */
class GlassRect(val left: Float, val top: Float, val right: Float, val bottom: Float, val radius: Float, val alpha: Float,
                val clip: android.graphics.RectF? = null)

/**
 * What the Glass layout's controls show of the image behind them. The preview is a SurfaceView
 * drawn by our GL thread, so Android's view blur can't see it; instead every control's rounded
 * shape is filled with the image as if it were a slab of glass: buttons clear (the preview image
 * itself, sharp, or the bending can't be seen; owner: "not bending like real glass" when they
 * showed a blurred copy), panels holding text frosted (a quarter-size blurred copy). The middle
 * magnifies a little; towards the rim the surface turns down steeply and shows what lies just
 * beyond the edge, pulled in, as a drop of glass does (a little more for red than for blue). A
 * thin specular line runs along the lit top-left rim; over bright scenes the glass darkens so
 * white labels stay readable. The views then draw only a light tint and a hairline rim on top.
 *
 * Cost on the X14 (`glasstiming` adb hook, which glFinishes around the screen draw): screen draw
 * 1.8 ms a frame without glass, ~3.3 ms with ~18 shapes, so about 1.5 ms; a third of that while
 * recording (the blur is refreshed every third frame). Nothing at all while [GlassRect]s are
 * empty (Classic layout). GL thread only; created on first use.
 */
internal class GlassBackdrop {
    private val downProgram = Gl.renderProgram(PipelineShaders.displayVertex, DOWN_FRAGMENT)
    private val blurProgram = Gl.renderProgram(PLAIN_VERTEX, BLUR_FRAGMENT)
    private val glassProgram = Gl.renderProgram(PLAIN_VERTEX, GLASS_FRAGMENT)
    /** 0: the small screen, then the light blur; 1: scratch; 2: the heavy blur. */
    private val tex = IntArray(3)
    private val fbo = IntArray(3)
    private var bw = 0
    private var bh = 0

    /** Debug: measure the GPU time of [draw] (with glFinish around it) and log the average. */
    var timing = false
    private var timedFrames = 0
    private var timedNs = 0L

    private var t0 = 0L

    // The image and how the screen shows it, from [prepare], for the clear glass.
    private var image = 0
    private var rotation = 0
    private var mirror = false
    private val letterbox = IntArray(4)
    private var frameKeep = 1f

    /**
     * Step 1, before anything is drawn to the window: [image] as the screen shows it ([rotation]
     * quarter turns, inside [letterbox] = x, y, w, h in GL pixels), small and blurred. Doing this
     * first keeps the window's own render pass in one piece: switching to our buffers half way
     * made a tile-based GPU (Adreno) write the whole screen out and read it back, ~2 ms a frame.
     * [refresh] = false keeps the last blurred copy (while recording, every GPU millisecond counts).
     */
    fun prepare(image: Int, imageW: Int, imageH: Int, rotation: Int, mirror: Boolean, letterbox: IntArray, viewW: Int, viewH: Int,
                frameKeep: Float, refresh: Boolean) {
        if (timing) { GLES30.glFinish(); t0 = SystemClock.elapsedRealtimeNanos() }
        this.image = image
        this.rotation = rotation
        this.mirror = mirror
        letterbox.copyInto(this.letterbox)
        this.frameKeep = frameKeep
        val w = (viewW / SCALE).coerceAtLeast(1)
        val h = (viewH / SCALE).coerceAtLeast(1)
        val fresh = w != bw || h != bh
        ensureTargets(w, h)
        if (refresh || fresh) blurScreen(image, imageW, imageH, rotation, mirror, letterbox)
    }

    /** Step 2, on the window surface after the image: the controls' glass shapes. */
    fun drawShapes(rects: List<GlassRect>, viewW: Int, viewH: Int, density: Float) {
        drawShapesNow(rects, viewW, viewH, density)
        if (timing) {
            GLES30.glFinish()
            timedNs += SystemClock.elapsedRealtimeNanos() - t0
            if (++timedFrames == 90) {
                Log.i("AuthRec", "glass + screen draw: %.2f ms a frame (%d shapes, blur %dx%d)".format(timedNs / 90 / 1e6, rects.size, bw, bh))
                timedFrames = 0
                timedNs = 0
            }
        }
    }

    private fun blurScreen(image: Int, imageW: Int, imageH: Int, rotation: Int, mirror: Boolean, letterbox: IntArray) {
        // 1. The screen at a quarter size: black margins plus the image, 4 taps a pixel.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glViewport(0, 0, bw, bh)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glViewport(letterbox[0] / SCALE, letterbox[1] / SCALE, letterbox[2] / SCALE, letterbox[3] / SCALE)
        GLES30.glUseProgram(downProgram)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, image)
        GLES30.glUniform1i(Gl.uniform(downProgram, "uImage"), 0)
        GLES30.glUniform1i(Gl.uniform(downProgram, "uRotation"), rotation)
        GLES30.glUniform1i(Gl.uniform(downProgram, "uMirror"), if (mirror) 1 else 0)
        // Each output pixel covers roughly imageW / (letterbox width / SCALE) source texels.
        val spread = (imageW.toFloat() / (letterbox[2].coerceAtLeast(1) / SCALE) / 4f).coerceAtLeast(0.5f)
        GLES30.glUniform2f(Gl.uniform(downProgram, "uTexel"), spread / imageW, spread / imageH)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        // 2. Separable Gaussian: once each way for the light blur (~12 px at full size), once
        // more into its own texture for the heavy one (~18 px).
        GLES30.glViewport(0, 0, bw, bh)
        GLES30.glUseProgram(blurProgram)
        GLES30.glUniform1i(Gl.uniform(blurProgram, "uTex"), 0)
        blurPass(tex[0], fbo[1], 1f / bw, 0f)
        blurPass(tex[1], fbo[0], 0f, 1f / bh)
        blurPass(tex[0], fbo[1], 1f / bw, 0f)
        blurPass(tex[1], fbo[2], 0f, 1f / bh)
    }

    private fun drawShapesNow(rects: List<GlassRect>, viewW: Int, viewH: Int, density: Float) {
        // 3. The controls' shapes on the window surface.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glUseProgram(glassProgram)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, image)
        Gl.bindTexture(1, GLES30.GL_TEXTURE_2D, tex[2])
        GLES30.glUniform1i(Gl.uniform(glassProgram, "uImage"), 0)
        GLES30.glUniform1i(Gl.uniform(glassProgram, "uHeavy"), 1)
        GLES30.glUniform2f(Gl.uniform(glassProgram, "uScreen"), viewW.toFloat(), viewH.toFloat())
        GLES30.glUniform4f(Gl.uniform(glassProgram, "uLetterbox"), letterbox[0].toFloat(), letterbox[1].toFloat(),
            letterbox[2].toFloat(), letterbox[3].toFloat())
        GLES30.glUniform1i(Gl.uniform(glassProgram, "uRotation"), rotation)
        GLES30.glUniform1i(Gl.uniform(glassProgram, "uMirror"), if (mirror) 1 else 0)
        GLES30.glUniform1f(Gl.uniform(glassProgram, "uFrameKeep"), frameKeep)
        GLES30.glUniform1i(Gl.uniform(glassProgram, "uBandAcross"), if (rotation % 2 == 0) 1 else 0)
        GLES30.glUniform1f(Gl.uniform(glassProgram, "uDp"), density)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        val rectLoc = Gl.uniform(glassProgram, "uRect")
        val radiusLoc = Gl.uniform(glassProgram, "uRadius")
        val alphaLoc = Gl.uniform(glassProgram, "uAlpha")
        val frostLoc = Gl.uniform(glassProgram, "uFrost")
        for (r in rects) {
            // GL's origin is bottom-left.
            val bottom = viewH - r.bottom
            val top = viewH - r.top
            val c = r.clip
            val x0 = maxOf(r.left, c?.left ?: 0f).toInt().coerceIn(0, viewW)
            val y0 = maxOf(bottom, c?.let { viewH - it.bottom } ?: 0f).toInt().coerceIn(0, viewH)
            val x1 = (minOf(r.right, c?.right ?: viewW.toFloat()).toInt() + 1).coerceIn(0, viewW)
            val y1 = (minOf(top, c?.let { viewH - it.top } ?: viewH.toFloat()).toInt() + 1).coerceIn(0, viewH)
            if (x1 <= x0 || y1 <= y0) continue
            GLES30.glScissor(x0, y0, x1 - x0, y1 - y0)
            GLES30.glUniform4f(rectLoc, r.left, bottom, r.right, top)
            GLES30.glUniform1f(radiusLoc, r.radius)
            GLES30.glUniform1f(alphaLoc, r.alpha)
            // Panels that hold text are frosted; buttons clear, so the bend shows.
            GLES30.glUniform1f(frostLoc, if (minOf(r.right - r.left, r.bottom - r.top) > viewH * 0.16f) 1f else 0f)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun blurPass(src: Int, dst: Int, dx: Float, dy: Float) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dst)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, src)
        GLES30.glUniform2f(Gl.uniform(blurProgram, "uStep"), dx * 1.5f, dy * 1.5f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun ensureTargets(w: Int, h: Int) {
        if (w == bw && h == bh) return
        release()
        GLES30.glGenFramebuffers(3, fbo, 0)
        for (i in 0..2) {
            tex[i] = Gl.texture2D(GLES30.GL_RGBA8, w, h, linear = true)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[i], 0)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        bw = w
        bh = h
    }

    fun release() {
        if (bw == 0) return
        GLES30.glDeleteFramebuffers(3, fbo, 0)
        GLES30.glDeleteTextures(3, tex, 0)
        bw = 0
        bh = 0
    }

    companion object {
        private const val SCALE = 4

        /** Full-viewport triangle with texture coordinates in GL's own orientation (FBO to FBO). */
        private val PLAIN_VERTEX = """
            #version 300 es
            out vec2 vUv;
            void main() {
                vec2 pos = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);
                gl_Position = vec4(pos, 0.0, 1.0);
                vUv = pos * 0.5 + 0.5;
            }
        """.trimIndent()

        /** Four bilinear taps (16 texels) per output pixel, so the small copy doesn't shimmer. */
        private val DOWN_FRAGMENT = """
            #version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform sampler2D uImage;
            uniform vec2 uTexel;
            out vec4 fragColor;
            void main() {
                vec3 c = texture(uImage, vUv + vec2(-uTexel.x, -uTexel.y)).rgb + texture(uImage, vUv + vec2(uTexel.x, -uTexel.y)).rgb +
                         texture(uImage, vUv + vec2(-uTexel.x, uTexel.y)).rgb + texture(uImage, vUv + vec2(uTexel.x, uTexel.y)).rgb;
                fragColor = vec4(c * 0.25, 1.0);
            }
        """.trimIndent()

        /** 9-tap Gaussian (sigma 2 taps) along [uStep]. */
        private val BLUR_FRAGMENT = """
            #version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform sampler2D uTex;
            uniform vec2 uStep;
            out vec4 fragColor;
            void main() {
                const float w0 = 0.2041637, w1 = 0.1801738, w2 = 0.1238315, w3 = 0.0662822, w4 = 0.0276306;
                vec3 c = texture(uTex, vUv).rgb * w0;
                c += (texture(uTex, vUv + uStep).rgb + texture(uTex, vUv - uStep).rgb) * w1;
                c += (texture(uTex, vUv + 2.0 * uStep).rgb + texture(uTex, vUv - 2.0 * uStep).rgb) * w2;
                c += (texture(uTex, vUv + 3.0 * uStep).rgb + texture(uTex, vUv - 3.0 * uStep).rgb) * w3;
                c += (texture(uTex, vUv + 4.0 * uStep).rgb + texture(uTex, vUv - 4.0 * uStep).rgb) * w4;
                fragColor = vec4(c, 1.0);
            }
        """.trimIndent()

        /**
         * One control as a slab of glass (see the class comment). The rounded rectangle's signed
         * distance gives the antialiased outline, the rim's normal and the bend. highp: pixel
         * coordinates beyond 2048 don't fit mediump.
         */
        private val GLASS_FRAGMENT = """
            #version 300 es
            precision highp float;
            uniform sampler2D uImage;    // the preview image (clear glass)
            uniform sampler2D uHeavy;    // the screen, small and well blurred (frosted glass)
            uniform vec2 uScreen;
            uniform vec4 uRect;          // left, bottom, right, top in GL pixels
            uniform float uRadius;
            uniform float uAlpha;
            uniform float uFrost;        // 0 = clear (buttons), 1 = frosted (panels holding text)
            uniform vec4 uLetterbox;     // the image on screen: x, y, w, h in GL pixels
            uniform int uRotation;       // as the screen draws it
            uniform bool uMirror;
            uniform float uFrameKeep;    // share of the image the recorded frame keeps; the rest is dimmed
            uniform bool uBandAcross;    // that band runs along the screen's width
            uniform float uDp;
            out vec4 fragColor;

            /** What the screen shows at [p] (GL pixels): the image, dimmed outside the frame, black around it. */
            vec3 screenAt(vec2 p) {
                if (uFrost > 0.5) return texture(uHeavy, p / uScreen).rgb;
                vec2 uv = (p - uLetterbox.xy) / uLetterbox.zw;
                if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return vec3(0.0);
                uv.y = 1.0 - uv.y;  // texture row 0 = top of the screen
                float dim = abs((uBandAcross ? uv.y : uv.x) - 0.5) > 0.5 * uFrameKeep ? 0.38 : 1.0;
                if (uMirror) uv.x = 1.0 - uv.x;
                for (int i = 0; i < uRotation; i++) uv = vec2(uv.y, 1.0 - uv.x);
                return texture(uImage, uv).rgb * dim;
            }

            void main() {
                vec2 p = gl_FragCoord.xy;
                vec2 c = 0.5 * (uRect.xy + uRect.zw);
                vec2 hs = 0.5 * (uRect.zw - uRect.xy);
                float r = min(uRadius, min(hs.x, hs.y));
                vec2 rel = p - c;
                vec2 q = abs(rel) - (hs - r);
                float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
                float a = clamp(0.5 - d, 0.0, 1.0) * uAlpha;
                if (a <= 0.0) discard;

                // Outward normal of the outline (the distance field's gradient).
                vec2 n = (q.x > 0.0 || q.y > 0.0) ? normalize(max(q, vec2(1e-3))) : (q.x > q.y ? vec2(1.0, 0.0) : vec2(0.0, 1.0));
                n *= sign(rel + vec2(1e-4));

                bool frosted = uFrost > 0.5;
                float band = clamp(min(hs.x, hs.y) * (frosted ? 0.25 : 0.5), 8.0, 90.0);
                float e = 1.0 - clamp(-d / band, 0.0, 1.0);          // 1 at the rim, 0 on the flat part
                vec2 src = c + rel / (frosted ? 1.02 : 1.04) + n * band * 0.75 * pow(e, 2.2);
                vec2 off = src - p;
                vec3 col = vec3(screenAt(p + off * 1.05).r, screenAt(p + off).g, screenAt(p + off * 0.95).b);

                // Darker over bright scenes, so white labels stay readable.
                float bright = dot(texture(uHeavy, c / uScreen).rgb, vec3(0.2126, 0.7152, 0.0722));
                col *= 1.0 - (frosted ? 0.25 : 0.4) * smoothstep(0.35, 0.85, bright);

                // A thin specular line along the lit (top-left) rim, a faint one all round, a little
                // shade inside the far side. GL y points up.
                float facing = max(dot(n, normalize(vec2(-0.55, 0.85))), 0.0);
                float away = max(dot(n, normalize(vec2(0.55, -0.85))), 0.0);
                float edge = exp(-max(-d, 0.0) / (1.2 * uDp));
                col += edge * (0.10 + 0.55 * facing * facing) - 0.10 * e * e * e * away;
                fragColor = vec4(col, a);
            }
        """.trimIndent()
    }
}
