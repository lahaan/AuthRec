package com.authrec.gl

import android.opengl.GLES30
import android.os.SystemClock
import android.util.Log

/** Where a Glass control sits on the preview surface: pixels, top-left origin, as views measure. */
class GlassRect(val left: Float, val top: Float, val right: Float, val bottom: Float, val radius: Float, val alpha: Float)

/**
 * What the Glass layout's controls show of the image behind them. The preview is a SurfaceView
 * drawn by our GL thread, so Android's view blur can't see it; instead each preview frame is also
 * drawn at a quarter size and blurred (lightly for buttons, more for big panels holding text), and
 * every control's rounded shape is filled with it as if it were a slab of glass: flat in the
 * middle, so the scene shows through undistorted, with a rounded bevel towards the rim that bends
 * what's behind inwards (a little more for red than for blue, as real glass does) and catches the
 * light along its top-left edge. The views then draw their tint, text and gloss on top.
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

    /**
     * Step 1, before anything is drawn to the window: [image] as the screen shows it ([rotation]
     * quarter turns, inside [letterbox] = x, y, w, h in GL pixels), small and blurred. Doing this
     * first keeps the window's own render pass in one piece: switching to our buffers half way
     * made a tile-based GPU (Adreno) write the whole screen out and read it back, ~2 ms a frame.
     * [refresh] = false keeps the last blurred copy (while recording, every GPU millisecond counts).
     */
    fun prepare(image: Int, imageW: Int, imageH: Int, rotation: Int, letterbox: IntArray, viewW: Int, viewH: Int, refresh: Boolean) {
        if (timing) { GLES30.glFinish(); t0 = SystemClock.elapsedRealtimeNanos() }
        val w = (viewW / SCALE).coerceAtLeast(1)
        val h = (viewH / SCALE).coerceAtLeast(1)
        val fresh = w != bw || h != bh
        ensureTargets(w, h)
        if (refresh || fresh) blurScreen(image, imageW, imageH, rotation, letterbox)
    }

    /** Step 2, on the window surface after the image: the controls' glass shapes. */
    fun drawShapes(rects: List<GlassRect>, viewW: Int, viewH: Int) {
        drawShapesNow(rects, viewW, viewH)
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

    private fun blurScreen(image: Int, imageW: Int, imageH: Int, rotation: Int, letterbox: IntArray) {
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

    private fun drawShapesNow(rects: List<GlassRect>, viewW: Int, viewH: Int) {
        // 3. The controls' shapes on the window surface.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glUseProgram(glassProgram)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, tex[0])
        Gl.bindTexture(1, GLES30.GL_TEXTURE_2D, tex[2])
        GLES30.glUniform1i(Gl.uniform(glassProgram, "uLight"), 0)
        GLES30.glUniform1i(Gl.uniform(glassProgram, "uHeavy"), 1)
        GLES30.glUniform2f(Gl.uniform(glassProgram, "uScreen"), viewW.toFloat(), viewH.toFloat())
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
            val x0 = r.left.toInt().coerceIn(0, viewW)
            val y0 = bottom.toInt().coerceIn(0, viewH)
            val x1 = (r.right.toInt() + 1).coerceIn(0, viewW)
            val y1 = (top.toInt() + 1).coerceIn(0, viewH)
            if (x1 <= x0 || y1 <= y0) continue
            GLES30.glScissor(x0, y0, x1 - x0, y1 - y0)
            GLES30.glUniform4f(rectLoc, r.left, bottom, r.right, top)
            GLES30.glUniform1f(radiusLoc, r.radius)
            GLES30.glUniform1f(alphaLoc, r.alpha)
            // Panels that hold text get the heavy blur; buttons the light one, so the bend shows.
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
         * One control as a slab of glass. The rounded rectangle's signed distance gives both the
         * antialiased outline and the bevel: within [bevel] of the rim the surface curves down
         * like a quarter circle, and its slope pushes the lookup towards the middle (refraction;
         * red a bit more than blue for a faint fringe). The top-left part of the bevel faces the
         * light and brightens. highp: pixel coordinates beyond 2048 don't fit mediump.
         */
        private val GLASS_FRAGMENT = """
            #version 300 es
            precision highp float;
            uniform sampler2D uLight;
            uniform sampler2D uHeavy;
            uniform vec2 uScreen;
            uniform vec4 uRect;     // left, bottom, right, top in GL pixels
            uniform float uRadius;
            uniform float uAlpha;
            uniform float uFrost;   // 0 = light blur (buttons), 1 = heavy (panels)
            out vec4 fragColor;

            // One texture per shape (a uniform branch costs nothing; reading both and mixing did).
            vec3 behind(vec2 p) {
                vec2 uv = p / uScreen;
                return uFrost > 0.5 ? texture(uHeavy, uv).rgb : texture(uLight, uv).rgb;
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

                float bevel = clamp(min(hs.x, hs.y) * 0.55, 6.0, 64.0);
                float t = clamp(-d / bevel, 0.0, 1.0);             // 0 at the rim, 1 on the flat top
                float e = 1.0 - t;
                float slope = e / max(sqrt(1.0 - e * e), 0.2);     // quarter-circle bevel, capped near the rim
                vec2 shift = -n * slope * bevel * 0.22;

                vec3 col = vec3(behind(p + shift * 1.12).r, behind(p + shift).g, behind(p + shift * 0.88).b);
                // Light from the top-left (GL y points up), mostly on the bevel.
                float facing = max(dot(n, normalize(vec2(-0.55, 0.85))), 0.0);
                float rimLight = e * e * (0.25 + 0.75 * facing) * 0.22;
                col = col * 1.05 + 0.012 + rimLight;
                fragColor = vec4(col, a);
            }
        """.trimIndent()
    }
}
