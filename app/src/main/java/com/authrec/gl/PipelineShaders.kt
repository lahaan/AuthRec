package com.authrec.gl

/**
 * GLSL for the RAW → log pipeline.
 *
 * Pass 1 (prep): 16-bit RAW → float, per-pixel black level, lens shading and white balance,
 *   with highlights clipped where the first channel saturates so they stay neutral; optional
 *   hot/dead pixel repair.
 * Pass 2 (develop): Malvar-He-Cutler demosaic (or 2×2 superpixel) → optional temporal colour
 *   noise reduction → camera-to-target-gamut matrix → log curve → the view (eDR, 3D LUT at
 *   adjustable strength, saturation/vibrance; shown and optionally baked) and, when something
 *   needs it (recording log, a look-editor capture), the clean log image, so the encoder can
 *   record either while the screen shows the view.
 */
internal object PipelineShaders {

    val prep = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x = 16, local_size_y = 16) in;

        layout(binding = 0) uniform highp usampler2D uRaw;
        layout(binding = 1) uniform mediump sampler2D uShading;  // R, G_even, G_odd, B gains
        layout(r32f, binding = 0) writeonly uniform highp image2D uOut;

        uniform vec4 uBlack;        // per 2×2 position, row-major
        uniform float uWhite;
        uniform vec4 uWb;           // R, G_even, G_odd, B
        uniform ivec2 uRedOffset;
        uniform bool uFixDefects;   // repair isolated hot/dead pixels

        float rawAt(ivec2 q, float black) {
            q = clamp(q, ivec2(0), textureSize(uRaw, 0) - 1);
            return (float(texelFetch(uRaw, q, 0).r) - black) / (uWhite - black);
        }

        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = textureSize(uRaw, 0);
            if (p.x >= size.x || p.y >= size.y) return;

            float black = uBlack[(p.y & 1) * 2 + (p.x & 1)];
            float v = (float(texelFetch(uRaw, p, 0).r) - black) / (uWhite - black);
            if (uFixDefects) {
                // Same-colour neighbours are 2 px away. Only a pixel far outside all eight of them
                // (diagonals too: with just the four straight ones, thin diagonal lines and small
                // glints looked like defects and were erased) is repaired, from the straight four.
                float a = rawAt(p + ivec2(2, 0), black), b = rawAt(p - ivec2(2, 0), black);
                float c = rawAt(p + ivec2(0, 2), black), d = rawAt(p - ivec2(0, 2), black);
                float e = rawAt(p + ivec2(2, 2), black), f = rawAt(p - ivec2(2, 2), black);
                float g = rawAt(p + ivec2(2, -2), black), h = rawAt(p + ivec2(-2, 2), black);
                float hi = max(max(max(a, b), max(c, d)), max(max(e, f), max(g, h)));
                float lo = min(min(min(a, b), min(c, d)), min(min(e, f), min(g, h)));
                if (v > hi * 2.0 + 0.02 || v < lo * 0.5 - 0.02) v = 0.25 * (a + b + c + d);
            }

            // Colour channel of this site: 0 = R, 1 = G on a red row, 2 = G on a blue row, 3 = B.
            ivec2 site = (p + uRedOffset) & 1;
            int ch = site == ivec2(0, 0) ? 0 : site == ivec2(1, 1) ? 3 : site == ivec2(1, 0) ? 1 : 2;

            vec2 uv = (vec2(p) + 0.5) / vec2(size);
            vec4 gains = texture(uShading, uv) * uWb;
            // A sensor pixel clips at v = 1, i.e. at gains[ch] after scaling. Clip every channel at
            // the lowest of those so blown highlights stay white instead of turning pink.
            float neutralClip = min(min(gains.r, gains.g), min(gains.b, gains.a));
            imageStore(uOut, p, vec4(min(v * gains[ch], neutralClip), 0.0, 0.0, 1.0));
        }
    """.trimIndent()

    /**
     * The view half of the pipeline (eDR, LUT input conversion, tetrahedral LUT, saturation and
     * vibrance), pasted into the develop shader after its declarations.
     */
    private val viewCommon = """
        layout(binding = 1) uniform mediump sampler3D uLut;

        uniform bool uWriteLog;      // the clean log image is wanted (recording log, or a capture)
        uniform bool uUseLut;
        uniform bool uLutConvert;    // the LUT expects another log encoding than ours
        uniform int uLutCurve;       // the LUT's
        uniform mat3 uLutGamut;      // linear: our primaries → the LUT's
        uniform bool uTone;          // tone balance on (see balanceTone)
        uniform float uToneHi;       // -1..1: < 0 pulls highlights down, > 0 pushes them up
        uniform float uToneLo;       // -1..1: > 0 lifts shadows, < 0 deepens them
        uniform vec3 uLumaW;         // luminance weights of our gamut (Y row of RGB → XYZ)
        uniform float uLutSize;
        uniform float uLutStrength;  // 0 = plain log, 1 = full LUT
        uniform float uSaturation;   // 1 = unchanged
        uniform float uVibrance;     // 0 = unchanged; boosts muted colours more than saturated ones

        float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

        // Same curves as LogProfile (encode in the develop pass).
        float decodeLog(int curve, float y) {
            if (curve == 0) {  // Apple Log
                if (y >= 0.20855532) return exp2((y - 0.69336945) / 0.08550479) - 0.00964052;
                if (y > 0.0) return sqrt(y / 47.28711236) - 0.05641088;
                return -0.05641088;
            } else if (curve == 1) {  // S-Log3
                float v = y * 1023.0;
                if (v >= 171.2102946929) return pow(10.0, (v - 420.0) / 261.5) * 0.19 - 0.01;
                return (v - 95.0) * 0.01125 / (171.2102946929 - 95.0);
            } else {  // LogC3 EI 800
                if (y > 5.367655 * 0.010591 + 0.092809) return (pow(10.0, (y - 0.385537) / 0.247190) - 0.052272) / 5.555556;
                return (y - 0.092809) / 5.367655;
            }
        }

        float encodeLogAs(int curve, float x) {
            if (curve == 0) {
                if (x >= 0.01) return 0.08550479 * log2(x + 0.00964052) + 0.69336945;
                if (x >= -0.05641088) return 47.28711236 * (x + 0.05641088) * (x + 0.05641088);
                return 0.0;
            } else if (curve == 1) {
                if (x >= 0.01125) return (420.0 + log((x + 0.01) / 0.19) / log(10.0) * 261.5) / 1023.0;
                return (x * (171.2102946929 - 95.0) / 0.01125 + 95.0) / 1023.0;
            } else {
                if (x > 0.010591) return 0.247190 * log(5.555556 * x + 0.052272) / log(10.0) + 0.385537;
                return 5.367655 * x + 0.092809;
            }
        }

        float softplus(float x, float w) { return w * log(1.0 + exp(x / w)); }

        /**
         * Global tone balance, what a phone's ISP does locally when its AE has exposed for the sky:
         * in stops around middle grey (0.18), highlights above +1 stop are compressed (slope
         * 1 + 0.6·uToneHi) and shadows below −1 stop lifted (up to 2.5 stops at full strength, easing
         * off towards black so noise isn't dragged up without limit). Soft half-stop knees keep
         * the curve smooth and middle grey in place. The gain comes from luminance and applies to
         * all three channels, so colours keep their ratios.
         */
        vec3 balanceTone(vec3 c) {
            vec3 lin = vec3(decodeLog(uCurve, c.r), decodeLog(uCurve, c.g), decodeLog(uCurve, c.b));
            float y = dot(lin, uLumaW);
            if (y <= 1e-5) return c;
            float e = log2(y / 0.18);
            float hi = 0.6 * uToneHi * softplus(e - 1.0, 0.5);
            float lo = 2.5 * uToneLo * tanh(softplus(-1.0 - e, 0.5) / 5.0);
            lin *= exp2(hi + lo);
            return vec3(encodeLogAs(uCurve, lin.r), encodeLogAs(uCurve, lin.g), encodeLogAs(uCurve, lin.b));
        }

        /** Our log → the log encoding (curve and gamut) the LUT was built for. */
        vec3 toLutInput(vec3 c) {
            vec3 lin = uLutGamut * vec3(decodeLog(uCurve, c.r), decodeLog(uCurve, c.g), decodeLog(uCurve, c.b));
            return vec3(encodeLogAs(uLutCurve, lin.r), encodeLogAs(uLutCurve, lin.g), encodeLogAs(uLutCurve, lin.b));
        }

        /** Saturation and vibrance on the display-referred view, around Rec.709 luma. */
        vec3 look(vec3 c) {
            float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
            float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
            float amount = uSaturation * (1.0 + uVibrance * (1.0 - clamp(sat, 0.0, 1.0)));
            return clamp(vec3(luma) + (c - vec3(luma)) * amount, 0.0, 1.0);
        }

        /**
         * Tetrahedral 3D-LUT interpolation (what Resolve uses). Hardware trilinear filtering
         * shows faceting/blotches on steep creative LUTs; tetrahedral follows them much better.
         * The LUT texture holds red along x, green along y, blue along z.
         *
         * Branch-free: the tetrahedron is picked by which of the cell's coordinates is largest
         * and smallest, as weights rather than six if/else cases. Neighbouring pixels pick
         * different cases, and a GPU runs a group of them in lockstep, so the branchy version
         * executed several cases for every group (4K: ~12.6 M lookups a frame).
         */
        vec3 lutTetra(vec3 c) {
            float n = uLutSize - 1.0;
            vec3 p = clamp(c, 0.0, 1.0) * n;
            ivec3 i0 = min(ivec3(floor(p)), ivec3(int(n) - 1));
            vec3 f = p - vec3(i0);
            // x >= y, y >= z, z > x: one comparison strict, so ties (greys: all three equal)
            // still give exactly one largest and one smallest coordinate.
            vec3 ge = vec3(step(f.y, f.x), step(f.z, f.y), 1.0 - step(f.z, f.x));
            vec3 big = ge * (1.0 - ge.zxy);           // one-hot: the largest coordinate
            vec3 small = (1.0 - ge) * ge.zxy;         // one-hot: the smallest
            vec3 two = 1.0 - small;                   // the two largest
            float fMax = dot(f, big);
            float fMin = dot(f, small);
            float fMid = dot(f, two) - fMax;
            vec3 c000 = texelFetch(uLut, i0, 0).rgb;
            vec3 cA = texelFetch(uLut, i0 + ivec3(big), 0).rgb;
            vec3 cB = texelFetch(uLut, i0 + ivec3(two), 0).rgb;
            vec3 c111 = texelFetch(uLut, i0 + 1, 0).rgb;
            return c000 * (1.0 - fMax) + cA * (fMax - fMid) + cB * (fMid - fMin) + c111 * fMin;
        }

        /** The view of log [c]: eDR, then the look/LUT at its strength (looks only), saturation and vibrance. */
        vec3 viewOf(vec3 c) {
            vec3 view = c;
            if (uUseLut) {
                // Tone balance belongs to the view (and a baked recording), never the clean log.
                vec3 t = uTone ? balanceTone(c) : c;
                view = mix(t, lutTetra(uLutConvert ? toLutInput(t) : t), uLutStrength);
            }
            return look(view);
        }
    """.trimIndent() + "\n"

    /**
     * Colour of each 4×4 block of the prep pass's mosaic (mean of its 4 red, 8 green, 4 blue
     * samples), for colour NR's change test: quiet enough to see a colour change that brightness
     * alone misses, cheap at a sixteenth of the pixels.
     */
    val coarseColour = """
        #version 310 es
        precision highp float;
        layout(local_size_x = 16, local_size_y = 16) in;
        layout(binding = 0) uniform highp sampler2D uLinear;
        layout(rgba16f, binding = 0) writeonly uniform highp image2D uOut;
        uniform ivec2 uRedOffset;

        void main() {
            ivec2 o = ivec2(gl_GlobalInvocationID.xy);
            if (any(greaterThanEqual(o, textureSize(uLinear, 0) / 4))) return;
            vec3 sum = vec3(0.0);
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
                ivec2 p = o * 4 + ivec2(x, y);
                float v = texelFetch(uLinear, p, 0).r;
                ivec2 site = (p + uRedOffset) & 1;
                if (site == ivec2(0, 0)) sum.r += v;
                else if (site == ivec2(1, 1)) sum.b += v;
                else sum.g += v;
            }
            imageStore(uOut, o, vec4(sum / vec3(4.0, 8.0, 4.0), 1.0));
        }
    """.trimIndent()

    /**
     * Develop: camera RGB → (temporal colour noise reduction) → target gamut → log → the clean log
     * (when something uses it) and the view, in one pass.
     *
     * Colour noise reduction is temporal and touches colour only, on the image being recorded as
     * you'd see it: the finished view (after eDR and the look/LUT), or the log when recording log.
     * Each pixel's colour, its channels minus their Rec.709-weighted mean, is blended with the
     * previous frames' where nothing moved, and that mean (the brightness you see, grain and all)
     * stays exactly as it was. Measured on the X14 (dark magenta-lit room, ISO 3200):
     * - in linear light (colour as a difference from, or share of, the noisy luminance), the
     *   luminance noise went into all three channels at once: ~50 % more grain in the log;
     * - in the log before the view, levels held in the log but the view's darks dropped 9–10
     *   levels: eDR and the LUT bend dark tones, which turns noise into extra brightness there,
     *   and with less colour noise that lift was gone (it read as crushed blacks);
     * - the spatial chroma filter this replaces left blotches (fine colour grain gone, the coarse
     *   kind not) and darkened noisy shadows by up to 29 %.
     * Motion is judged against the sensor's own noise profile for that frame, twice: a 3×3-smoothed
     * brightness per pixel (fine detail), and the colour of the 4×4 sensor blocks around it
     * (coarseColour): a grey cable sliding over an orange mat at much the same brightness passed
     * the first test and left grey trails behind (owner, X14 2.6x, 2026-10-08).
     */
    private val developBase = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x = 16, local_size_y = 16) in;

        layout(binding = 0) uniform highp sampler2D uLinear;     // r32f from the prep pass
        layout(binding = 2) uniform highp sampler2D uHistIn;     // previous frames: colour (rgb), smoothed brightness (a)
        layout(binding = 3) uniform highp sampler2D uCoarseNow;  // block colours of this frame (coarseColour)
        layout(binding = 4) uniform highp sampler2D uCoarsePrev; // and of the previous one
        layout(rgba16f, binding = 0) writeonly uniform highp image2D uOutView;
        layout(rgba16f, binding = 1) writeonly uniform highp image2D uOutLog;
        layout(rgba16f, binding = 2) writeonly uniform highp image2D uHistOut;

        uniform ivec2 uRedOffset;
        uniform mat3 uCamToTarget;   // white-balanced camera RGB → linear target gamut
        uniform float uExposure;     // linear gain on top of the ISP's auto exposure
        uniform int uCurve;          // LogProfile.shaderId
        uniform int uTnr;            // colour NR: 0 off, 1 on, 2 on and starting over (no usable history)
        uniform bool uTnrOnLog;      // denoise the log (recording log) instead of the view
        uniform float uTnrAlpha;     // weight of the history where nothing moved
        uniform vec2 uNoise;         // variance of one prep-pass sample at brightness x: x * uNoise.x + uNoise.y

        float px(ivec2 p) {
            p = clamp(p, ivec2(0), textureSize(uLinear, 0) - 1);
            return texelFetch(uLinear, p, 0).r;
        }

        float encodeLog(float x) {
            if (uCurve == 0) {  // Apple Log
                if (x >= 0.01) return 0.08550479 * log2(x + 0.00964052) + 0.69336945;
                if (x >= -0.05641088) return 47.28711236 * (x + 0.05641088) * (x + 0.05641088);
                return 0.0;
            } else if (uCurve == 1) {  // S-Log3
                if (x >= 0.01125) return (420.0 + log((x + 0.01) / 0.19) / log(10.0) * 261.5) / 1023.0;
                return (x * (171.2102946929 - 95.0) / 0.01125 + 95.0) / 1023.0;
            } else {  // LogC3 EI 800
                if (x > 0.010591) return 0.247190 * log(5.555556 * x + 0.052272) / log(10.0) + 0.385537;
                return 5.367655 * x + 0.092809;
            }
        }

        /**
         * [c] (view or log) with its colour, not its brightness, blended over time where nothing
         * moved. [rawUv]: where the pixel is on the sensor (0..1), for the block colours.
         */
        vec3 colourNr(ivec2 p, vec3 c, float smoothed, vec2 rawUv) {
            if (uTnr == 0) return c;
            float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
            vec3 colour = c - vec3(luma);
            if (uTnr == 1) {
                vec4 h = texelFetch(uHistIn, p, 0);
                // How far brightness moved, in units of this frame's noise: static areas stay
                // well under 1.5, a real change jumps past 3.
                float sigma = sqrt(max(smoothed, 0.0) * uNoise.x + uNoise.y);
                float moved = abs(smoothed - h.a) / max(sigma, 1e-7);
                // Block colours (means of 4 red, 8 green, 4 blue samples) against two frames' noise;
                // a static scene stays under ~3 on every channel.
                vec3 now = texture(uCoarseNow, rawUv).rgb;
                vec3 prev = texture(uCoarsePrev, rawUv).rgb;
                vec3 blockVar = 2.0 * (max(now, 0.0) * uNoise.x + uNoise.y) / vec3(4.0, 8.0, 4.0);
                vec3 z = abs(now - prev) / sqrt(max(blockVar, vec3(1e-12)));
                float changed = max(z.r, max(z.g, z.b));
                float keep = (1.0 - smoothstep(1.5, 3.0, moved)) * (1.0 - smoothstep(3.5, 7.0, changed));
                colour = mix(colour, h.rgb, uTnrAlpha * keep);
            }
            imageStore(uHistOut, p, vec4(colour, smoothed));
            return vec3(luma) + colour;
        }
    """.trimIndent() + "\n" + viewCommon + """
        void develop(ivec2 p, vec3 cam, float smoothed, vec2 rawUv) {
            vec3 lin = uCamToTarget * cam * uExposure;
            vec3 c = vec3(encodeLog(lin.r), encodeLog(lin.g), encodeLog(lin.b));
            if (uTnrOnLog) c = colourNr(p, c, smoothed, rawUv);
            if (uWriteLog) imageStore(uOutLog, p, vec4(c, 1.0));
            vec3 v = viewOf(c);
            if (!uTnrOnLog) v = colourNr(p, v, smoothed, rawUv);
            imageStore(uOutView, p, vec4(v, 1.0));
        }
    """.trimIndent() + "\n"

    /** Full-resolution output with Malvar-He-Cutler 5×5 linear demosaic. */
    private val demosaicMain = """
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = textureSize(uLinear, 0);
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
            float gAtRB = (4.0 * C + 2.0 * cross - axial2) / 8.0;
            float oppAtRB = (6.0 * C + 2.0 * diag - 1.5 * axial2) / 8.0;
            float rowAtG = (5.0 * C + 4.0 * (W + E) - diag - (WW + EE) + 0.5 * (NN + SS)) / 8.0;
            float colAtG = (5.0 * C + 4.0 * (N + S) - diag - (NN + SS) + 0.5 * (WW + EE)) / 8.0;

            ivec2 site = (p + uRedOffset) & 1;
            vec3 cam;
            if (site == ivec2(0, 0))      cam = vec3(C, gAtRB, oppAtRB);
            else if (site == ivec2(1, 1)) cam = vec3(oppAtRB, gAtRB, C);
            else if (site == ivec2(1, 0)) cam = vec3(rowAtG, C, colAtG);
            else                          cam = vec3(colAtG, C, rowAtG);
            // 3×3 binomial over the (white-balanced) mosaic: a brightness that's quiet enough to
            // tell motion from noise.
            develop(p, cam, (4.0 * C + 2.0 * cross + diag) / 16.0, (vec2(p) + 0.5) / vec2(size));
        }
    """.trimIndent()

    /** Half-resolution output: one RGB pixel per 2×2 CFA block, no interpolation at all. */
    private val superpixelMain = """
        void main() {
            ivec2 o = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = textureSize(uLinear, 0) / 2;
            if (o.x >= size.x || o.y >= size.y) return;

            ivec2 base = o * 2;
            vec3 cam = vec3(
                px(base + uRedOffset),
                0.5 * (px(base + ivec2(1 - uRedOffset.x, uRedOffset.y)) + px(base + ivec2(uRedOffset.x, 1 - uRedOffset.y))),
                px(base + ivec2(1) - uRedOffset));
            develop(o, cam, 0.25 * (cam.r + 2.0 * cam.g + cam.b), (vec2(base) + 1.0) / vec2(size * 2));
        }
    """.trimIndent()

    val develop = developBase + demosaicMain
    val developSuperpixel = developBase + superpixelMain

    val displayVertex = """
        #version 300 es
        out vec2 vUv;
        uniform int uRotation;  // quarter turns clockwise
        uniform vec4 uCrop;     // x, y, width, height of the texture to show (a recorded frame); unset = all
        uniform bool uMirror;   // left-right, as seen on screen (selfie style)
        void main() {
            // One oversized triangle covering the viewport; no vertex buffers needed.
            vec2 pos = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);
            gl_Position = vec4(pos, 0.0, 1.0);
            vec2 uv = vec2(pos.x * 0.5 + 0.5, 0.5 - pos.y * 0.5);  // texture row 0 = top of screen
            if (uMirror) uv.x = 1.0 - uv.x;
            for (int i = 0; i < uRotation; i++) uv = vec2(uv.y, 1.0 - uv.x);
            vec4 crop = uCrop.z > 0.0 ? uCrop : vec4(0.0, 0.0, 1.0, 1.0);
            vUv = crop.xy + uv * crop.zw;
        }
    """.trimIndent()

    /** One flat colour (with alpha) wherever the scissor lets it: the frame mask. */
    val solidFragment = """
        #version 300 es
        precision mediump float;
        uniform vec4 uColor;
        out vec4 fragColor;
        void main() { fragColor = uColor; }
    """.trimIndent()

    /** Draws a pipeline image; on the preview only, optional focus peaking marks sharp edges red. */
    val displayFragment = """
        #version 300 es
        precision mediump float;
        in highp vec2 vUv;  // texel positions in a 4K image need more than mediump's 11 bits
        uniform sampler2D uImage;
        uniform bool uPeaking;
        uniform bool uSmooth;  // B-spline instead of bilinear (scales where noise beats)
        uniform vec2 uTexel;  // one source pixel in uv units
        out vec4 fragColor;

        /**
         * Quintic B-spline sampling in 9 bilinear taps (each pair of its 6×6 weights is one tap).
         * Its weights' sum of squares hardly changes with the sampling phase, so noise looks the
         * same everywhere: measured on screen, the front camera's noise energy varied 3× across
         * each 50-px beat with bilinear, 1.3× with a cubic B-spline, 1.07× with this.
         */
        vec3 bspline(highp vec2 uv) {
            highp vec2 size = vec2(textureSize(uImage, 0));
            highp vec2 t = uv * size - 0.5;
            highp vec2 i = floor(t);
            highp vec2 f = t - i;
            highp vec2 f2 = f * f;
            highp vec2 f3 = f2 * f;
            highp vec2 f4 = f2 * f2;
            highp vec2 f5 = f4 * f;
            highp vec2 w0 = (1.0 - f) * (1.0 - f) * (1.0 - f) * (1.0 - f) * (1.0 - f) / 120.0;
            highp vec2 w1 = (26.0 - 50.0 * f + 20.0 * f2 + 20.0 * f3 - 20.0 * f4 + 5.0 * f5) / 120.0;
            highp vec2 w2 = (66.0 - 60.0 * f2 + 30.0 * f4 - 10.0 * f5) / 120.0;
            highp vec2 w3 = (26.0 + 50.0 * f + 20.0 * f2 - 20.0 * f3 - 20.0 * f4 + 10.0 * f5) / 120.0;
            highp vec2 w5 = f5 / 120.0;
            highp vec2 w4 = 1.0 - w0 - w1 - w2 - w3 - w5;
            highp vec2 g0 = w0 + w1;
            highp vec2 g1 = w2 + w3;
            highp vec2 g2 = w4 + w5;
            // Tap positions in texel-centre units, then back to uv.
            highp vec2 p0 = (i - 2.0 + w1 / g0 + 0.5) / size;
            highp vec2 p1 = (i + w3 / g1 + 0.5) / size;
            highp vec2 p2 = (i + 2.0 + w5 / max(g2, 1e-8) + 0.5) / size;
            vec3 r0 = g0.x * texture(uImage, vec2(p0.x, p0.y)).rgb + g1.x * texture(uImage, vec2(p1.x, p0.y)).rgb + g2.x * texture(uImage, vec2(p2.x, p0.y)).rgb;
            vec3 r1 = g0.x * texture(uImage, vec2(p0.x, p1.y)).rgb + g1.x * texture(uImage, vec2(p1.x, p1.y)).rgb + g2.x * texture(uImage, vec2(p2.x, p1.y)).rgb;
            vec3 r2 = g0.x * texture(uImage, vec2(p0.x, p2.y)).rgb + g1.x * texture(uImage, vec2(p1.x, p2.y)).rgb + g2.x * texture(uImage, vec2(p2.x, p2.y)).rgb;
            return g0.y * r0 + g1.y * r1 + g2.y * r2;
        }

        float luma(vec2 uv) { return dot(texture(uImage, uv).rgb, vec3(0.2126, 0.7152, 0.0722)); }

        /** Luma averaged over ~3×3 source pixels (4 bilinear taps), so sensor noise doesn't read as edges. */
        float lumaSmooth(vec2 uv) {
            vec2 h = uTexel * 0.5;
            return 0.25 * (luma(uv + vec2(-h.x, -h.y)) + luma(uv + vec2(h.x, -h.y)) +
                           luma(uv + vec2(-h.x, h.y)) + luma(uv + vec2(h.x, h.y)));
        }

        void main() {
            vec3 c = uSmooth ? bspline(vUv) : texture(uImage, vUv).rgb;
            if (uPeaking) {
                vec2 d = uTexel * 2.0;
                float gx = lumaSmooth(vUv + vec2(d.x, 0.0)) - lumaSmooth(vUv - vec2(d.x, 0.0));
                float gy = lumaSmooth(vUv + vec2(0.0, d.y)) - lumaSmooth(vUv - vec2(0.0, d.y));
                // Threshold scales with brightness so edges in dim scenes can still show.
                float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
                if (abs(gx) + abs(gy) > 0.08 + 0.2 * l) c = vec3(1.0, 0.1, 0.1);
            }
            fragColor = vec4(c, 1.0);
        }
    """.trimIndent()
}
