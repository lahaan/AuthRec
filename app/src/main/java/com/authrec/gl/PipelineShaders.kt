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

    /**
     * Work-group side of the develop pass. 8×8 (one Adreno wave, so its barriers cost nothing)
     * left Quality colour NR as slow as 16×16 and made the pass without NR ~2.7 ms slower at 4K
     * (X14, 2026-10-21): the NR's cost is the state held across its barriers, not the barriers.
     */
    const val DEVELOP_GROUP = 16

    /** Quads per side of photo alignment's last tiles (Renderer.ALIGN_TILE's level 0). */
    const val PHOTO_SHIFT_TILE = 16

    /** The prep pass's declarations and its last step, shared by its two variants. */
    private val prepCommon = """
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

        /** Pixel [p]'s normalised RAW [v] through lens shading and white balance, clipped neutral. */
        float shade(ivec2 p, float v, ivec2 size) {
            // Colour channel of this site: 0 = R, 1 = G on a red row, 2 = G on a blue row, 3 = B.
            ivec2 site = (p + uRedOffset) & 1;
            int ch = site == ivec2(0, 0) ? 0 : site == ivec2(1, 1) ? 3 : site == ivec2(1, 0) ? 1 : 2;

            vec2 uv = (vec2(p) + 0.5) / vec2(size);
            vec4 gains = texture(uShading, uv) * uWb;
            // A sensor pixel clips at v = 1, i.e. at gains[ch] after scaling. Clip every channel at
            // the lowest of those so blown highlights stay white instead of turning pink.
            float neutralClip = min(min(gains.r, gains.g), min(gains.b, gains.a));
            return min(v * gains[ch], neutralClip);
        }
    """.trimIndent() + "\n"

    /** Prep without the pixel fix (clean-up off): one thread per pixel. */
    val prep = prepCommon + """
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = textureSize(uRaw, 0);
            if (p.x >= size.x || p.y >= size.y) return;
            float black = uBlack[(p.y & 1) * 2 + (p.x & 1)];
            float v = (float(texelFetch(uRaw, p, 0).r) - black) / (uWhite - black);
            imageStore(uOut, p, vec4(shade(p, v, size), 0.0, 0.0, 1.0));
        }
    """.trimIndent()

    /**
     * Prep with the pixel fix (clean-up 1 and up), one thread per 2×2 quad: its pixels and their
     * same-colour neighbours (2 px away) are the 6×6 RAW around it, nine gathers, where a thread
     * per pixel fetched its own nine texels (36 reads a quad). X14 idle preview at clean 1: GPU
     * busy ~33 → ~31 % at the same clock (2026-10-21). Without the fix the per-pixel pass is the
     * cheaper one (quads took clean 0 from 25 to ~29 %: the 36 values each thread holds). The same
     * operations in the same order as the per-pixel pass, so the values are bit for bit the same
     * (A/B on the X14: every output of ~800 frames identical).
     */
    val prepFixDefects = prepCommon + """
        // The RAW from q0 - 2 to q0 + 3 around this thread's quad (q0: its top-left pixel).
        float raw[36];

        /** The 2×2 block at q0 + 2 (gx, gy) - 2 into raw. */
        void gatherRaw(ivec2 q0, int gx, int gy, vec2 texel) {
            // Centred between the block's texels: x = (i0, j1), y = (i1, j1), z = (i1, j0), w = (i0, j0).
            // Clamp-to-edge clamps each texel like the per-pixel reads did.
            uvec4 g = textureGather(uRaw, vec2(q0 + ivec2(gx * 2 - 1, gy * 2 - 1)) * texel);
            int b = gy * 12 + gx * 2;
            raw[b] = float(g.w);
            raw[b + 1] = float(g.z);
            raw[b + 6] = float(g.x);
            raw[b + 7] = float(g.y);
        }

        float rawAt(int x, int y, float black) {
            return (raw[(y + 2) * 6 + x + 2] - black) / (uWhite - black);
        }

        /** The prepared value of pixel q0 + (x, y), x and y 0 or 1. */
        float prepAt(ivec2 q0, int x, int y, ivec2 size) {
            ivec2 p = q0 + ivec2(x, y);
            float black = uBlack[(p.y & 1) * 2 + (p.x & 1)];
            float v = rawAt(x, y, black);
            // Same-colour neighbours are 2 px away. Only a pixel far outside all eight of them
            // (diagonals too: with just the four straight ones, thin diagonal lines and small
            // glints looked like defects and were erased) is repaired, from the straight four.
            float a = rawAt(x + 2, y, black), b = rawAt(x - 2, y, black);
            float c = rawAt(x, y + 2, black), d = rawAt(x, y - 2, black);
            float e = rawAt(x + 2, y + 2, black), f = rawAt(x - 2, y - 2, black);
            float g = rawAt(x + 2, y - 2, black), h = rawAt(x - 2, y + 2, black);
            float hi = max(max(max(a, b), max(c, d)), max(max(e, f), max(g, h)));
            float lo = min(min(min(a, b), min(c, d)), min(min(e, f), min(g, h)));
            if (v > hi * 2.0 + 0.02 || v < lo * 0.5 - 0.02) v = 0.25 * (a + b + c + d);
            return shade(p, v, size);
        }

        void main() {
            ivec2 q0 = ivec2(gl_GlobalInvocationID.xy) * 2;
            ivec2 size = textureSize(uRaw, 0);
            if (q0.x >= size.x || q0.y >= size.y) return;
            vec2 texel = 1.0 / vec2(size);
            // Constant loop bounds: the block's indices are constants, so it stays in registers.
            for (int gy = 0; gy < 3; gy++) for (int gx = 0; gx < 3; gx++) gatherRaw(q0, gx, gy, texel);
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    ivec2 p = q0 + ivec2(x, y);
                    if (p.x < size.x && p.y < size.y) imageStore(uOut, p, vec4(prepAt(q0, x, y, size), 0.0, 0.0, 1.0));
                }
            }
        }
    """.trimIndent()

    /**
     * The view half of the pipeline (eDR, LUT input conversion, tetrahedral LUT, saturation and
     * vibrance), pasted into the develop shader after its declarations.
     */
    private val viewCommon = """
        layout(binding = 1) uniform mediump sampler3D uLut;

        uniform bool uWriteLog;      // the clean log image is wanted (recording log, or a capture)
        uniform bool uWriteView;     // the view is wanted (not when recording log at full size: see viewFromLog)
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
     * samples), the building block of colour NR's change test ([colourChange]).
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
            // The block's 16 samples in four gathers (x = (i0, j1), y = (i1, j1), z = (i1, j0),
            // w = (i0, j0)) rather than 16 fetches, summed in the same (row) order as before.
            vec2 texel = 1.0 / vec2(textureSize(uLinear, 0));
            float v[16];
            for (int gy = 0; gy < 2; gy++) for (int gx = 0; gx < 2; gx++) {
                vec4 g = textureGather(uLinear, vec2(o * 4 + ivec2(gx * 2 + 1, gy * 2 + 1)) * texel);
                int k = gy * 8 + gx * 2;
                v[k] = g.w;
                v[k + 1] = g.z;
                v[k + 4] = g.x;
                v[k + 5] = g.y;
            }
            vec3 sum = vec3(0.0);
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
                ivec2 p = o * 4 + ivec2(x, y);
                float c = v[y * 4 + x];
                ivec2 site = (p + uRedOffset) & 1;
                if (site == ivec2(0, 0)) sum.r += c;
                else if (site == ivec2(1, 1)) sum.b += c;
                else sum.g += c;
            }
            imageStore(uOut, o, vec4(sum / vec3(4.0, 8.0, 4.0), 1.0));
        }
    """.trimIndent()

    /** colourChange's halving steps over its 256 threads, each followed by a barrier. */
    private val REDUCE = (7 downTo 0).joinToString("") { k ->
        val h = 1 shl k
        """
            if (i < ${h}u) {
                sSum[i] += sSum[i + ${h}u];
                sScale[i] += sScale[i + ${h}u];
            }
            memoryBarrierShared();
            barrier();"""
    }

    /**
     * Colour NR's change test, per 8×8 and 16×16 sensor window (centred on every 4×4 block
     * corner, made of [coarseColour]'s blocks): has its colour changed against a running average of
     * its past frames by more than the sensor's noise explains?
     *
     * - Colour is (R−G, B−G) over R+2G+B: exposure changes leave it alone, and dividing by the
     *   whole brightness instead of green keeps it quiet in the dark.
     * - Noise per channel from the noise profile and that channel's own gain here (white balance ×
     *   lens shading), scaled by what each window has learned while still: the running mean of its
     *   z²/2. The profiles alone were off by a different factor per lens (X14 on a still scene:
     *   1x ×0.5, 2.6x ×0.2, 0.6x ×3), which made the test blind on one and NR give up on another.
     * - Against a running average, not just the previous frame: less noise, and slow movement adds
     *   up until it's caught. The result is a chi-square with 2 degrees of freedom (z² ≈ 2 for pure
     *   noise); keep fades from 1 to 0 between z 2.5 and 4.5.
     * - The frame-wide mean of z² (clipped, so one moving object barely counts) rises when the
     *   camera moves even where no single window can tell: [Motion] lets the develop pass back off.
     * In a simulation at the X14's ISO 3200 noise (2026-10-09): a grey bar sliding over a
     * yellow-orange wall left a trail 27× fainter than with the previous test (4×4 blocks against
     * the previous frame, average gain), and a pan over dark green leaves under orange light kept
     * 91 % of their colour instead of 19 %; static NR within 10 % of before.
     */
    val colourChange = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x = 16, local_size_y = 16) in;
        layout(binding = 0) uniform highp sampler2D uBlocks;      // 4×4 block means (coarseColour)
        layout(binding = 1) uniform mediump sampler2D uShading;  // R, G_even, G_odd, B gains, as in prep
        layout(binding = 2) uniform highp sampler2D uModel8In;    // running colour of each 8×8 window, a: noise scale
        layout(binding = 3) uniform highp sampler2D uModel16In;   // and of each 16×16 one
        layout(rgba16f, binding = 0) writeonly uniform highp image2D uModel8Out;
        layout(rgba16f, binding = 1) writeonly uniform highp image2D uModel16Out;
        layout(rgba16f, binding = 2) writeonly uniform highp image2D uKeepOut;     // r: keep (0..1)
        // [slot]: this frame's sum of clipped z² × 16; [2 + slot]: of the noise scales × 16 (debug).
        layout(std430, binding = 0) buffer Motion { uint motion[]; };

        uniform vec4 uWb;         // the prep pass's white balance (with the user's trim)
        uniform vec2 uNoiseRaw;   // variance of normalised RAW at x: x * S + O
        uniform bool uFresh;      // no running average yet
        uniform int uSlot;        // this frame's motion counter
        uniform ivec2 uRawSize;

        shared uint sSum[256];
        shared uint sScale[256];

        /** z² of a window's colour change; [v]: the variance of its R, G, B means. */
        float chromaZ2(vec3 now, vec3 model, vec3 v) {
            float floorY = 3.0 * sqrt(v.r + 4.0 * v.g + v.b);
            float yNow = now.r + 2.0 * now.g + now.b;
            float yModel = model.r + 2.0 * model.g + model.b;
            float y = max(yNow, floorY);
            float ym = max(yModel, floorY);
            vec2 d = vec2(now.r - now.g, now.b - now.g) / y - vec2(model.r - model.g, model.b - model.g) / ym;
            // 1.15: the running average's own noise.
            vec2 var = vec2(v.r + v.g, v.b + v.g) / (y * y) * 1.15;
            float z2 = d.x * d.x / var.x + d.y * d.y / var.y;
            // In the noise floor a colour as a share of brightness is mostly noise, which held z
            // under ~5 even for a saturated object leaving a black background: there, compare the
            // colour differences themselves (exposure changes are tiny in absolute terms that dark).
            if (min(yNow, yModel) < floorY) {
                vec2 da = vec2(now.r - now.g, now.b - now.g) - vec2(model.r - model.g, model.b - model.g);
                vec2 va = vec2(v.r + v.g, v.b + v.g) * 1.15;
                z2 = max(z2, da.x * da.x / va.x + da.y * da.y / va.y);
            }
            return z2;
        }

        void main() {
            ivec2 b = ivec2(gl_GlobalInvocationID.xy);
            ivec2 n = textureSize(uBlocks, 0);
            uint sum = 0u;     // this window's share of the frame-wide sums
            uint scale = 0u;
            if (all(lessThan(b, n))) {
                // Windows centred on the block corner at 4b: 8×8 = the 2×2 blocks around it,
                // 16×16 = the 4×4 around it.
                vec3 now8 = vec3(0.0);
                vec3 now16 = vec3(0.0);
                for (int y = -2; y < 2; y++) for (int x = -2; x < 2; x++) {
                    vec3 c = texelFetch(uBlocks, clamp(b + ivec2(x, y), ivec2(0), n - 1), 0).rgb;
                    now16 += c;
                    if (x >= -1 && x < 1 && y >= -1 && y < 1) now8 += c;
                }
                now8 *= 0.25;
                now16 *= 0.0625;
                vec4 g4 = texture(uShading, vec2(b * 4) / vec2(uRawSize)) * uWb;
                vec3 g = vec3(g4.r, 0.5 * (g4.g + g4.b), g4.a);
                vec3 var8 = (g * max(now8, 0.0) * uNoiseRaw.x + g * g * uNoiseRaw.y) / vec3(16.0, 32.0, 16.0);
                vec3 var16 = (g * max(now16, 0.0) * uNoiseRaw.x + g * g * uNoiseRaw.y) / vec3(64.0, 128.0, 64.0);
                vec4 m8 = texelFetch(uModel8In, b, 0);
                vec4 m16 = texelFetch(uModel16In, b, 0);
                // z² by the noise profile, then by what this window's noise has turned out to be.
                float raw8 = chromaZ2(now8, m8.rgb, var8);
                float raw16 = chromaZ2(now16, m16.rgb, var16);
                float scale8 = uFresh ? 1.0 : m8.a;
                float scale16 = uFresh ? 1.0 : m16.a;
                float z8 = uFresh ? 1e4 : raw8 / scale8;
                float z16 = uFresh ? 1e4 : raw16 / scale16;
                float keep8 = 1.0 - smoothstep(2.5, 4.5, sqrt(z8));
                float keep16 = 1.0 - smoothstep(2.5, 4.5, sqrt(z16));
                // Learn the noise scale while the window is still (a change isn't noise). Where it
                // looks moving the scale still creeps up, or a lens far noisier than its profile
                // would look moving everywhere and never learn; but only 1 % a frame and never past
                // 3× the last frame's typical window: creeping towards a moving window's huge z²
                // reached the 20 limit in a few frames, so an object's leading edge blinded its
                // windows to its trailing edge (grey over a yellowish wall passed), and in pans
                // every window went blind and the frame-wide back-off let go.
                if (!uFresh) {
                    float typical = float(motion[3 - uSlot]) / (16.0 * float(n.x * n.y));
                    float cap = typical > 0.05 ? min(20.0, 3.0 * typical) : 20.0;
                    scale8 = keep8 > 0.5 || 0.5 * raw8 < scale8 ? mix(0.5 * raw8, scale8, 0.95) : scale8 * 1.01;
                    scale16 = keep16 > 0.5 || 0.5 * raw16 < scale16 ? mix(0.5 * raw16, scale16, 0.95) : scale16 * 1.01;
                    scale8 = clamp(scale8, 0.05, max(cap, 0.05));
                    scale16 = clamp(scale16, 0.05, max(cap, 0.05));
                }
                imageStore(uModel8Out, b, vec4(mix(now8, m8.rgb, 0.8 * keep8), scale8));
                imageStore(uModel16Out, b, vec4(mix(now16, m16.rgb, 0.8 * keep16), scale16));
                imageStore(uKeepOut, b, vec4(min(keep8, keep16), 0.0, 0.0, 1.0));
                if (!uFresh) {
                    sum = uint(min(z8, 25.0) * 16.0);
                    scale = uint(min(scale8, 20.0) * 16.0);
                }
            }
            // The work group's sums by halving (integers: the same in any order), then one atomic
            // per group. Every thread adding to one shared counter serialised 256 atomics per
            // group on each; with coarseColour's gathers the change test went 5.4 → 4.5 ms in the
            // X14's idle preview (pipetiming, 2026-10-21). (GLSL ES allows barrier() only directly
            // in main, so it's unrolled.)
            uint i = gl_LocalInvocationIndex;
            sSum[i] = sum;
            sScale[i] = scale;
            memoryBarrierShared();
            barrier();$REDUCE
            if (i == 0u) {
                atomicAdd(motion[uSlot], sSum[0]);
                atomicAdd(motion[2 + uSlot], sScale[0]);
            }
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
     * Motion is judged against the sensor's own noise profile for that frame: a 3×3-smoothed
     * brightness per pixel (fine detail), the colour of the 8×8 and 16×16 sensor windows around it
     * (colourChange: a grey cable sliding over an orange mat at much the same brightness passed
     * the brightness test and left grey trails, owner, X14 2.6x), and, frame-wide, how much the
     * windows changed overall: while the camera moves the blend backs off everywhere, or dark,
     * noisy detail too faint to test smeared (greens under street lights washed out, owner 0.4.0).
     */
    private val developBase = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x = $DEVELOP_GROUP, local_size_y = $DEVELOP_GROUP) in;

        layout(binding = 0) uniform highp sampler2D uLinear;     // r32f from the prep pass
        layout(binding = 2) uniform highp sampler2D uHistIn;     // previous frames: colour (rgb), smoothed brightness (a)
        layout(binding = 3) uniform highp sampler2D uChange;     // colourChange's keep (r)
        layout(std430, binding = 0) readonly buffer Motion { uint motion[]; };
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
        uniform int uSlot;           // this frame's motion counter
        uniform float uWindows;      // how many windows colourChange tested
        uniform bool uStill;         // a photo's colour NR passes: the history is this same frame, nothing moved

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
    """.trimIndent() + "\n" + viewCommon

    /**
     * Quality colour NR (the default): see [developBase]'s notes; the history's colour is clamped
     * to its 3×3 neighbourhood, which needs the work group's colours shared (barriers). At 4K it
     * adds ~7 ms to the develop pass on the X14 (0.5.0's per-pixel NR: ~2 ms).
     */
    private val qualityNr = """
        // Colour NR works on each work group (DEVELOP_GROUP²) in three steps (developA, B, C) with the
        // pixels' colours shared between them; every main() calls them with barriers in between
        // (GLSL ES allows barrier() only in main, outside any branch, before any return).
        shared mediump vec4 sCur[${DEVELOP_GROUP * DEVELOP_GROUP}];   // the NR target's colour (rgb) and 3×3 brightness (a) per pixel
        shared mediump vec4 sOut[${DEVELOP_GROUP * DEVELOP_GROUP}];   // its blended colour (rgb) and the history's weight (a)
        shared float sValid[${DEVELOP_GROUP * DEVELOP_GROUP}];

        ivec2 gP;
        bool gValid;
        vec2 gRawUv;
        vec3 gLog;        // the clean log
        float gLuma;      // brightness of the NR target (the view, or the log when recording log)
        vec3 gColour;     // its colour: channels minus that brightness
        vec3 gNr;         // the colour after blending
        float gQuadB;     // mean 3×3 brightness of the pixel's 2×2 quad (the history's resolution)
        float gHistB;     // the quad's brightness in the history
        float gBlend;     // the history's weight

        int quadIndex(int x, int y) {
            ivec2 l = ivec2(gl_LocalInvocationID.xy) / 2 * 2;
            return (l.y + y) * $DEVELOP_GROUP + l.x + x;
        }

        /** Step A: develop the pixel and share its colour. [rawUv]: where it is on the sensor (0..1). */
        void developA(ivec2 p, vec3 cam, float smoothed, vec2 rawUv, bool valid) {
            gP = p;
            gValid = valid;
            gRawUv = rawUv;
            vec3 lin = uCamToTarget * cam * uExposure;
            gLog = vec3(encodeLog(lin.r), encodeLog(lin.g), encodeLog(lin.b));
            vec3 t = uTnrOnLog || !valid || !uWriteView ? gLog : viewOf(gLog);
            gLuma = dot(t, vec3(0.2126, 0.7152, 0.0722));
            gColour = t - vec3(gLuma);
            gNr = gColour;
            gBlend = 0.0;
            int i = int(gl_LocalInvocationIndex);
            sCur[i] = vec4(gColour, smoothed);
            sValid[i] = valid ? 1.0 : 0.0;
        }

        /**
         * Step B: blend the colour with the previous frames' where nothing moved. The history is
         * kept at half resolution (one colour per 2×2 quad, sampled bilinearly): recordings carry
         * colour at half resolution anyway (4:2:0), and full-size history cost 2×100 MB of memory
         * and ~200 MB of traffic a frame at 4K, part of why frames got lost and colour NR shed.
         *
         * Before blending, the history's colour is clamped to what this frame has around the
         * pixel (mean ± 1 σ of its 3×3 neighbours, as temporal anti-aliasing does): where the
         * tests below miss a change (a grey object over a yellowish wall, red over a dark
         * background, motion blur spreading an edge over frames) an old colour can't outlive the
         * frame that no longer has it nearby. Pure noise almost never leaves that box: the
         * history sits within σ/3 of the neighbours' mean.
         */
        void developB() {
            if (uTnr == 0 || !gValid) return;
            float qb = 0.0;
            float qn = 0.0;
            for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
                int j = quadIndex(x, y);
                if (sValid[j] > 0.5) { qb += sCur[j].a; qn += 1.0; }
            }
            gQuadB = qb / max(qn, 1.0);
            // Starting over: nothing to blend with, and the history may be uninitialised memory
            // (a NaN there survives even a zero-weight mix and would stay in the history).
            gHistB = gQuadB;
            if (uTnr != 1) return;
            gHistB = texelFetch(uHistIn, gP / 2, 0).a;
            ivec2 l = ivec2(gl_LocalInvocationID.xy);
            vec3 s1 = vec3(0.0);
            vec3 s2 = vec3(0.0);
            float n = 0.0;
            for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
                ivec2 q = l + ivec2(x, y);
                if (q.x < 0 || q.y < 0 || q.x >= $DEVELOP_GROUP || q.y >= $DEVELOP_GROUP) continue;
                int j = q.y * $DEVELOP_GROUP + q.x;
                if (sValid[j] < 0.5) continue;
                vec3 c = sCur[j].rgb;
                s1 += c;
                s2 += c * c;
                n += 1.0;
            }
            vec3 mean = s1 / n;
            vec3 box = sqrt(max(s2 / n - mean * mean, vec3(0.0))) + 0.002;
            vec3 h = texture(uHistIn, (vec2(gP) + 0.5) / vec2(imageSize(uOutView))).rgb;
            h = clamp(h, mean - box, mean + box);
            if (uStill) {
                gBlend = uTnrAlpha;
                gNr = mix(gColour, h, gBlend);
                return;
            }
            // How far the quad's brightness moved from its running average, in units of one
            // sample's noise (the 2×2 mean of 3×3 means against an average: ~0.35 of that).
            float sigma = sqrt(max(gQuadB, 0.0) * uNoise.x + uNoise.y);
            float moved = abs(gQuadB - gHistB) / max(sigma, 1e-7);
            // The windows' colour test; their centres sit on block corners (4 px apart).
            float windowKeep = texture(uChange, gRawUv + 2.0 / vec2(textureSize(uLinear, 0))).r;
            // Frame-wide excess of the windows' z² over noise's 2: ~0 when still, 1–3 in pans.
            float excess = float(motion[uSlot]) / (16.0 * uWindows) - 2.0;
            float alpha = uTnrAlpha * (1.0 - 0.7 * smoothstep(1.0, 3.0, excess));
            float keep = (1.0 - smoothstep(1.0, 2.0, moved)) * windowKeep;
            gBlend = alpha * keep;
            gNr = mix(gColour, h, gBlend);
        }

        /**
         * Step C: each quad's top-left pixel writes the quad's history (its blended colours, and
         * its brightness blended the same way: against a running average slow changes add up,
         * where frame to frame each step of a blurred edge could pass), then every pixel its output.
         */
        void developC() {
            ivec2 l = ivec2(gl_LocalInvocationID.xy);
            if (uTnr != 0 && gValid && l.x % 2 == 0 && l.y % 2 == 0) {
                vec3 c = vec3(0.0);
                float a = 0.0;
                float n = 0.0;
                for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
                    int j = quadIndex(x, y);
                    if (sValid[j] > 0.5) { c += sOut[j].rgb; a += sOut[j].a; n += 1.0; }
                }
                imageStore(uHistOut, gP / 2, vec4(c / n, mix(gQuadB, gHistB, a / n)));
            }
            if (!gValid) return;
            vec3 t = vec3(gLuma) + gNr;
            if (uTnrOnLog) {
                if (uWriteLog) imageStore(uOutLog, gP, vec4(t, 1.0));
                if (uWriteView) imageStore(uOutView, gP, vec4(viewOf(t), 1.0));
            } else {
                if (uWriteLog) imageStore(uOutLog, gP, vec4(gLog, 1.0));
                if (uWriteView) imageStore(uOutView, gP, vec4(t, 1.0));
            }
        }
    """.trimIndent() + "\n"

    /**
     * Fast colour NR: the same temporal blend and motion tests, but every pixel alone, so no
     * barriers and nothing held across them. Its history is full resolution (read as 2×2 means
     * through the bilinear filter, so noise is averaged in space as well): colour in two
     * channels (the third follows from the Rec.709 weights, as the colour sums to zero with
     * them), the running 3×3 brightness, and the running variance of the pixel's colour about
     * its history, learned where nothing moved. The trail guard clamps the history's colour to
     * the current one ± 2 σ of that variance (Quality uses the 3×3 neighbours' spread instead):
     * an old colour that this pixel's own noise can't explain doesn't linger. Variance grows only
     * 1 % a frame while the clamp is engaged, so a trail can't teach it to look like noise; it
     * starts wide (no clamping until learned, ~1 s) so NR works from the first frame.
     */
    private val fastNr = """
        const vec3 W709 = vec3(0.2126, 0.7152, 0.0722);

        void developA(ivec2 p, vec3 cam, float smoothed, vec2 rawUv, bool valid) {
            if (!valid) return;
            vec3 lin = uCamToTarget * cam * uExposure;
            vec3 logc = vec3(encodeLog(lin.r), encodeLog(lin.g), encodeLog(lin.b));
            vec3 t = uTnrOnLog || !uWriteView ? logc : viewOf(logc);
            if (uTnr != 0) {
                float luma = dot(t, W709);
                vec2 cur = vec2(t.r - luma, t.b - luma);
                vec2 col = cur;
                float bright = smoothed;
                float var = 0.01; // starting over: wide, so nothing is clamped until learned
                if (uTnr == 1) {
                    // Read at a pixel corner through the bilinear filter: the mean of 2×2 history
                    // pixels, the corner alternating each frame so the history doesn't drift.
                    // Colour noise is then averaged in space too (Quality's half-res history does
                    // the same); the clamp below keeps edges from bleeding.
                    vec4 h = texture(uHistIn, (vec2(p) + float(uSlot)) / vec2(textureSize(uHistIn, 0)));
                    // How far the 3×3 brightness moved from its running average, in units of one
                    // sample's noise (that difference has ~0.4 of it).
                    float sigma = sqrt(max(smoothed, 0.0) * uNoise.x + uNoise.y);
                    float moved = abs(smoothed - h.b) / max(sigma, 1e-7);
                    float windowKeep = texture(uChange, rawUv + 2.0 / vec2(textureSize(uLinear, 0))).r;
                    float excess = float(motion[uSlot]) / (16.0 * uWindows) - 2.0;
                    float alpha = uTnrAlpha * (1.0 - 0.7 * smoothstep(1.0, 3.0, excess));
                    float keep = (1.0 - smoothstep(1.0, 2.0, moved)) * windowKeep;
                    vec2 d = cur - h.rg;
                    float len = length(d);
                    float lim = 2.0 * sqrt(h.a + 1e-7);
                    vec2 hc = len > lim ? cur - d * (lim / len) : h.rg;
                    float blend = alpha * keep;
                    col = mix(cur, hc, blend);
                    bright = mix(smoothed, h.b, blend);
                    var = len > lim ? min(h.a * 1.01, 1.0) : (keep > 0.5 ? mix(len * len, h.a, 0.9) : h.a);
                }
                imageStore(uHistOut, p, vec4(col, bright, var));
                float g = -(W709.r * col.x + W709.b * col.y) / W709.g;
                t = vec3(luma) + vec3(col.x, g, col.y);
            }
            if (uTnrOnLog) {
                if (uWriteLog) imageStore(uOutLog, p, vec4(t, 1.0));
                if (uWriteView) imageStore(uOutView, p, vec4(viewOf(t), 1.0));
            } else {
                if (uWriteLog) imageStore(uOutLog, p, vec4(logc, 1.0));
                if (uWriteView) imageStore(uOutView, p, vec4(t, 1.0));
            }
        }
    """.trimIndent() + "\n"

    /** Where a main() runs the NR steps after [developA]; Quality fills in [DEVELOP_STEPS], Fast nothing. */
    private const val STEPS = "/*STEPS*/"

    /** The three steps of [developBase] around a main()'s own sampling; GLSL needs them in main itself. */
    private const val DEVELOP_STEPS = """
            memoryBarrierShared();
            barrier();
            developB();
            sOut[gl_LocalInvocationIndex] = vec4(gNr, gBlend);
            memoryBarrierShared();
            barrier();
            developC();"""

    /**
     * Malvar-He-Cutler 5×5 linear demosaic at RAW pixel [p]: camera RGB, and (a) a 3×3 binomial
     * brightness over the white-balanced mosaic, quiet enough to tell motion from noise.
     */
    private val mhc = """
        vec4 mhcAt(ivec2 p) {
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
            return vec4(cam, (4.0 * C + 2.0 * cross + diag) / 16.0);
        }
    """.trimIndent() + "\n"

    /** Full-resolution output. */
    private val demosaicMain = mhc + """
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = textureSize(uLinear, 0);
            bool valid = p.x < size.x && p.y < size.y;
            vec4 m = mhcAt(p); // clamped reads: harmless outside the image
            developA(p, m.rgb, m.a, (vec2(p) + 0.5) / vec2(size), valid);$STEPS
        }
    """.trimIndent()

    /**
     * Output smaller than the RAW (recording where the encoder takes less, e.g. the Xiaomi 12's
     * 2880×2160 from 4096×3072), so colour, log, the view, colour NR and the writes run on the
     * smaller image only. Each colour is interpolated straight at the output pixel's position
     * from its own samples (green: at the four pixels around it, the cross average where a pixel
     * isn't green, then blended), from one 4×4 block of the mosaic in four gathers: softer than
     * Malvar-He-Cutler, which the downscale mostly hides. MHC at the four pixels around each
     * output pixel (even gathered) cost as much as the full-size pass (X14 ~10 ms, X12 ~18 ms
     * either way); shrinking a full-size image afterwards means writing and reading it back.
     */
    private val scaledMain = """
        uniform vec2 uRawPerOut;  // RAW pixels per output pixel (> 1)

        // The 4×4 mosaic block p-1 .. p+2 around the output pixel's position.
        float nb[16];

        float at(int x, int y) { return nb[y * 4 + x]; }

        /** Is the block's pixel (x, y) green? Red sites have ((pos + uRedOffset) & 1) == 0. */
        bool greenAt(ivec2 p, int x, int y) {
            ivec2 s = (p + ivec2(x - 1, y - 1) + uRedOffset) & 1;
            return s.x != s.y;
        }

        float greenOf(ivec2 p, int x, int y) {
            return greenAt(p, x, y) ? at(x, y) : 0.25 * (at(x - 1, y) + at(x + 1, y) + at(x, y - 1) + at(x, y + 1));
        }

        /**
         * Bilinear over the 2-pixel lattice of the colour whose sites have parity [site] (0 red,
         * 1 blue); [c] is the position, [p] = floor(c).
         */
        float latticeAt(ivec2 p, vec2 c, int site) {
            ivec2 off = (p + uRedOffset + ivec2(site)) & 1;   // how far p sits past the lattice line
            ivec2 l = ivec2(1) - off;                         // the lattice's first column/row in the block
            vec2 f = (c - vec2(p - off)) * 0.5;
            float top = mix(at(l.x, l.y), at(l.x + 2, l.y), f.x);
            float bottom = mix(at(l.x, l.y + 2), at(l.x + 2, l.y + 2), f.x);
            return mix(top, bottom, f.y);
        }

        void main() {
            ivec2 o = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = imageSize(uOutView);
            bool valid = o.x < size.x && o.y < size.y;
            vec2 c = (vec2(o) + 0.5) * uRawPerOut - 0.5;
            ivec2 p = ivec2(floor(c));
            vec2 f = c - vec2(p);
            vec2 texel = 1.0 / vec2(textureSize(uLinear, 0));
            for (int gy = 0; gy < 2; gy++) {
                for (int gx = 0; gx < 2; gx++) {
                    // Centred between the 2×2 block's texels: x = (i0, j1), y = (i1, j1), z = (i1, j0), w = (i0, j0).
                    vec4 g = textureGather(uLinear, (vec2(p + ivec2(gx * 2 - 1, gy * 2 - 1)) + 1.0) * texel);
                    int b = gy * 8 + gx * 2;
                    nb[b] = g.w;
                    nb[b + 1] = g.z;
                    nb[b + 4] = g.x;
                    nb[b + 5] = g.y;
                }
            }
            float green = mix(mix(greenOf(p, 1, 1), greenOf(p, 2, 1), f.x), mix(greenOf(p, 1, 2), greenOf(p, 2, 2), f.x), f.y);
            vec3 cam = vec3(latticeAt(p, c, 0), green, latticeAt(p, c, 1));
            // The 2×2 mean around the position: a quiet brightness for colour NR's motion test.
            float smoothed = 0.25 * (at(1, 1) + at(2, 1) + at(1, 2) + at(2, 2));
            developA(o, cam, smoothed, (c + 0.5) / vec2(textureSize(uLinear, 0)), valid);$STEPS
        }
    """.trimIndent()

    /** Half-resolution output: one RGB pixel per 2×2 CFA block, no interpolation at all. */
    private val superpixelMain = """
        void main() {
            ivec2 o = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = textureSize(uLinear, 0) / 2;
            bool valid = o.x < size.x && o.y < size.y;

            ivec2 base = o * 2;
            vec3 cam = vec3(
                px(base + uRedOffset),
                0.5 * (px(base + ivec2(1 - uRedOffset.x, uRedOffset.y)) + px(base + ivec2(uRedOffset.x, 1 - uRedOffset.y))),
                px(base + ivec2(1) - uRedOffset));
            developA(o, cam, 0.25 * (cam.r + 2.0 * cam.g + cam.b), (vec2(base) + 1.0) / vec2(size * 2), valid);$STEPS
        }
    """.trimIndent()

    /**
     * The viewfinder's image while recording log at full size: the develop pass then writes only
     * the log (writing the full-size view as well cost 100 MB of memory traffic and the view's
     * math on 12.6 M pixels a frame, for a screen ~1600 px wide). This makes the view at half
     * size from the log, each pixel from the mean of its 2×2 (one bilinear read at their shared
     * corner), and only for frames the viewfinder shows. The view of the mean rather than the mean
     * of the views, like the half-res preview between recordings (it bins the RAW). X14, 4K Log
     * clips with Fast colour NR: GPU busy × clock −25 %, clean 0 −12 % (2026-10-21); the recorded
     * log is bit for bit the same.
     */
    val viewFromLog = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x = 16, local_size_y = 16) in;
        layout(binding = 0) uniform highp sampler2D uLog;
        layout(rgba16f, binding = 0) writeonly uniform highp image2D uOutView;
        uniform int uCurve;
    """.trimIndent() + "\n" + viewCommon + """
        void main() {
            ivec2 o = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = imageSize(uOutView);
            if (o.x >= size.x || o.y >= size.y) return;
            vec3 c = texture(uLog, (vec2(o) + 0.5) / vec2(size)).rgb;
            imageStore(uOutView, o, vec4(viewOf(c), 1.0));
        }
    """.trimIndent()

    private fun variant(fast: Boolean, main: String) =
        developBase + (if (fast) fastNr else qualityNr) + main.replace(STEPS, if (fast) "" else DEVELOP_STEPS)

    val develop = variant(false, demosaicMain)
    val developSuperpixel = variant(false, superpixelMain)
    val developScaled = variant(false, scaledMain)
    // Fast colour NR (Settings → Colour NR → Fast), compiled when first chosen.
    val developFast get() = variant(true, demosaicMain)
    val developSuperpixelFast get() = variant(true, superpixelMain)
    val developScaledFast get() = variant(true, scaledMain)

    /**
     * Photo mode's alignment pyramid, level 0: each 2×2 quad of the prep pass's mosaic as one
     * grey value (its mean: white-balanced, so the colours weigh alike), times [uGain] (the
     * reference's exposure over this frame's). Half the RAW's size, one value per quad.
     */
    val photoGrey = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x = 16, local_size_y = 16) in;
        layout(binding = 0) uniform highp sampler2D uLinear;
        layout(r32f, binding = 0) writeonly uniform highp image2D uOut;
        uniform float uGain;
        uniform ivec2 uDebugShift;   // see photoCommon

        void main() {
            ivec2 q = ivec2(gl_GlobalInvocationID.xy);
            if (q.x >= imageSize(uOut).x || q.y >= imageSize(uOut).y) return;
            ivec2 p = clamp(q + uDebugShift, ivec2(0), textureSize(uLinear, 0) / 2 - 1) * 2;
            float s = texelFetch(uLinear, p, 0).r + texelFetch(uLinear, p + ivec2(1, 0), 0).r +
                texelFetch(uLinear, p + ivec2(0, 1), 0).r + texelFetch(uLinear, p + ivec2(1, 1), 0).r;
            imageStore(uOut, q, vec4(s * 0.25 * uGain));
        }
    """.trimIndent()

    /** The pyramid's next level: the mean of each [uFactor]² block of the one below. */
    val photoDown = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x = 16, local_size_y = 16) in;
        layout(binding = 0) uniform highp sampler2D uIn;
        layout(r32f, binding = 0) writeonly uniform highp image2D uOut;
        uniform int uFactor;

        void main() {
            ivec2 o = ivec2(gl_GlobalInvocationID.xy);
            if (o.x >= imageSize(uOut).x || o.y >= imageSize(uOut).y) return;
            ivec2 last = textureSize(uIn, 0) - 1;
            float s = 0.0;
            for (int y = 0; y < uFactor; y++) for (int x = 0; x < uFactor; x++)
                s += texelFetch(uIn, min(o * uFactor + ivec2(x, y), last), 0).r;
            imageStore(uOut, o, vec4(s / float(uFactor * uFactor)));
        }
    """.trimIndent()

    /**
     * Photo mode's alignment, first step: one offset for the whole frame (the hand's shake), on
     * the pyramid's coarsest level, from every pixel. Tiles alone found it only where they had
     * structure: in a dark room most are flat noise and kept no offset, and a structured tile's
     * neighbours then pulled its offset back toward none (a 12 px shake, 2026-11-02). One work
     * group per offset within [uRadius] (pixels within [uRadius] of the edge left out); its sum
     * of absolute differences into [cost].
     */
    val photoGlobal = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x = 256) in;
        layout(binding = 0) uniform highp sampler2D uRefLevel;
        layout(binding = 1) uniform highp sampler2D uAltLevel;
        layout(std430, binding = 1) writeonly buffer Costs { float cost[]; };
        uniform int uRadius;

        shared float sSum[256];

        void main() {
            uint i = gl_LocalInvocationIndex;
            int c = int(gl_WorkGroupID.x);
            int side = 2 * uRadius + 1;
            ivec2 d = ivec2(c % side - uRadius, c / side - uRadius);
            ivec2 size = textureSize(uRefLevel, 0) - 2 * uRadius;
            float s = 0.0;
            for (int k = int(i); k < size.x * size.y; k += 256) {
                ivec2 p = ivec2(k % size.x, k / size.x) + uRadius;
                s += abs(texelFetch(uRefLevel, p, 0).r - texelFetch(uAltLevel, p + d, 0).r);
            }
            sSum[i] = s;
            memoryBarrierShared();
            barrier();
            $GLOBAL_REDUCE
            if (i == 0u) cost[c] = sSum[0];
        }
    """.trimIndent()

    /** photoGlobal's halving steps over its 256 threads, each followed by a barrier. */
    private val GLOBAL_REDUCE get() = (7 downTo 0).joinToString("") { k ->
        val h = 1 shl k
        """
            if (i < ${h}u) sSum[i] += sSum[i + ${h}u];
            memoryBarrierShared();
            barrier();"""
    }

    /** [photoGlobal]'s least cost, to a fraction of a pixel (a parabola each way), as a 1×1 offset image. */
    val photoGlobalPick = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x = 1) in;
        layout(std430, binding = 1) readonly buffer Costs { float cost[]; };
        layout(rgba32f, binding = 0) writeonly uniform highp image2D uOut;
        uniform int uRadius;

        void main() {
            int side = 2 * uRadius + 1;
            int b = 0;
            for (int c = 1; c < side * side; c++) if (cost[c] < cost[b]) b = c;
            int bx = b % side;
            int by = b / side;
            vec2 sub = vec2(0.0);
            if (bx > 0 && bx < side - 1) {
                float cx = cost[b - 1] - 2.0 * cost[b] + cost[b + 1];
                if (cx > 0.0) sub.x = clamp(0.5 * (cost[b - 1] - cost[b + 1]) / cx, -0.5, 0.5);
            }
            if (by > 0 && by < side - 1) {
                float cy = cost[b - side] - 2.0 * cost[b] + cost[b + side];
                if (cy > 0.0) sub.y = clamp(0.5 * (cost[b - side] - cost[b + side]) / cy, -0.5, 0.5);
            }
            imageStore(uOut, ivec2(0), vec4(vec2(bx - uRadius, by - uRadius) + sub, 0.0, 0.0));
        }
    """.trimIndent()

    /**
     * Photo mode's alignment, one level of the pyramid per pass, per tile: where this tile of the
     * reference is in this frame (as HDR+ does it): the offset with the least absolute difference
     * within [uRadius] of the best of three guesses from the coarser level (the tile's own coarse
     * tile and the two nearest neighbours, so a tile on a coarse tile's edge can follow the other
     * side; at the coarsest level, [photoGlobal]'s offset). An offset that beats the guess by no more than noise would
     * (flat, dark tiles) keeps the guess, so a featureless tile follows the coarser level rather
     * than a random offset its neighbours' interpolation would lean toward. The last level adds a
     * fraction of a pixel (a parabola through the squared differences around the best).
     * Output per tile: the offset in this level's pixels (xy).
     */
    val photoAlign = """
        #version 310 es
        precision highp float;
        precision highp int;
        // One work group per tile, its threads each trying offsets: a thread per tile looping over
        // every offset left the GPU waiting on fetches (~100 ms a burst frame at 4K, X14).
        layout(local_size_x = 64) in;
        layout(binding = 0) uniform highp sampler2D uRefLevel;
        layout(binding = 1) uniform highp sampler2D uAltLevel;
        layout(binding = 2) uniform highp sampler2D uCoarse;   // the coarser level's tiles
        layout(rgba32f, binding = 0) writeonly uniform highp image2D uOut;
        uniform int uTile;          // tile side, this level's pixels (at most 16)
        uniform int uRadius;        // at most 4
        uniform int uCoarseScale;   // this level's pixels per coarser-level pixel; 0 at the coarsest
        uniform int uCoarseTile;
        uniform bool uSubpixel;

        shared float sRef[256];     // the reference's tile
        shared float sCost[81];
        shared ivec2 sGuesses[3];
        shared float sGuessCost[3];
        shared ivec2 sGuess;
        shared ivec2 sBest;
        shared float sFit[5];

        ivec2 gT0;
        ivec2 gLast;

        float sad(ivec2 d) {
            float s = 0.0;
            for (int y = 0; y < uTile; y++) for (int x = 0; x < uTile; x++) {
                ivec2 p = min(gT0 + ivec2(x, y), gLast);
                s += abs(sRef[y * uTile + x] - texelFetch(uAltLevel, clamp(p + d, ivec2(0), gLast), 0).r);
            }
            return s;
        }

        float ssd(ivec2 d) {
            float s = 0.0;
            for (int y = 0; y < uTile; y++) for (int x = 0; x < uTile; x++) {
                ivec2 p = min(gT0 + ivec2(x, y), gLast);
                float e = sRef[y * uTile + x] - texelFetch(uAltLevel, clamp(p + d, ivec2(0), gLast), 0).r;
                s += e * e;
            }
            return s;
        }

        void main() {
            ivec2 t = ivec2(gl_WorkGroupID.xy);
            int li = int(gl_LocalInvocationIndex);
            gLast = textureSize(uRefLevel, 0) - 1;
            gT0 = t * uTile;
            int n = uTile * uTile;
            for (int i = li; i < n; i += 64) sRef[i] = texelFetch(uRefLevel, min(gT0 + ivec2(i % uTile, i / uTile), gLast), 0).r;
            memoryBarrierShared();
            barrier();

            // The guesses: no offset at the coarsest level, else the coarser level's for this tile's
            // coarse tile and the two nearest neighbours (a tile on its edge can follow the other side).
            int guesses = uCoarseScale == 0 ? 1 : 3;
            if (li < guesses) {
                ivec2 d = ivec2(0);
                if (uCoarseScale > 0) {
                    ivec2 last = textureSize(uCoarse, 0) - 1;
                    vec2 c = (vec2(gT0) + 0.5 * float(uTile)) / float(uCoarseScale * uCoarseTile);
                    ivec2 nb = ivec2(sign(fract(c) - 0.5));
                    ivec2 ci = clamp(ivec2(floor(c)) + (li == 1 ? ivec2(nb.x, 0) : li == 2 ? ivec2(0, nb.y) : ivec2(0)), ivec2(0), last);
                    d = ivec2(round(texelFetch(uCoarse, ci, 0).xy * float(uCoarseScale)));
                }
                sGuesses[li] = d;
                sGuessCost[li] = sad(d);
            }
            memoryBarrierShared();
            barrier();
            if (li == 0) {
                int g = 0;
                for (int i = 1; i < guesses; i++) if (sGuessCost[i] < sGuessCost[g]) g = i;
                sGuess = sGuesses[g];
                sGuessCost[0] = sGuessCost[g];
            }
            memoryBarrierShared();
            barrier();

            ivec2 guess = sGuess;
            int side = 2 * uRadius + 1;
            for (int c = li; c < side * side; c += 64) sCost[c] = sad(guess + ivec2(c % side - uRadius, c / side - uRadius));
            memoryBarrierShared();
            barrier();
            if (li == 0) {
                int b = 0;
                for (int c = 1; c < side * side; c++) if (sCost[c] < sCost[b]) b = c;
                // Two noise-only sums of absolute differences over n pixels differ by ~1 · sum / √n.
                float guessCost = sGuessCost[0];
                sBest = guessCost - sCost[b] < 3.0 * sCost[b] / sqrt(float(n)) ? guess
                    : guess + ivec2(b % side - uRadius, b / side - uRadius);
            }
            memoryBarrierShared();
            barrier();

            ivec2 best = sBest;
            if (uSubpixel && li < 5) {
                ivec2 o = li == 1 ? ivec2(-1, 0) : li == 2 ? ivec2(1, 0) : li == 3 ? ivec2(0, -1) : li == 4 ? ivec2(0, 1) : ivec2(0);
                sFit[li] = ssd(best + o);
            }
            memoryBarrierShared();
            barrier();
            if (li == 0) {
                vec2 sub = vec2(0.0);
                if (uSubpixel) {
                    float cx = sFit[1] - 2.0 * sFit[0] + sFit[2];
                    float cy = sFit[3] - 2.0 * sFit[0] + sFit[4];
                    if (cx > 0.0) sub.x = clamp(0.5 * (sFit[1] - sFit[2]) / cx, -0.5, 0.5);
                    if (cy > 0.0) sub.y = clamp(0.5 * (sFit[3] - sFit[4]) / cy, -0.5, 0.5);
                }
                imageStore(uOut, t, vec4(vec2(best) + sub, 0.0, 0.0));
            }
        }
    """.trimIndent()

    /**
     * Photo mode's burst merge, on the prep pass's mosaic (shared by [photoDiff], [photoMerge] and
     * [photoNoise]): this frame's 2×2 quad where the reference has it ([photoAlign]'s offsets,
     * interpolated between tiles; each colour's samples interpolated between quads), and how much
     * it differs from the burst's first frame, the reference, in units of the sensor's noise. The
     * noise of each prep sample follows from the noise profile and that pixel's own gain (white
     * balance × lens shading), for both frames; interpolating averages a frame's noise down.
     */
    private val photoCommon = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x = 16, local_size_y = 16) in;

        layout(binding = 0) uniform highp sampler2D uLinear;     // this frame's prep output
        layout(binding = 1) uniform mediump sampler2D uShading;  // R, G_even, G_odd, B gains, as in prep
        layout(binding = 2) uniform highp sampler2D uShift;      // photoAlign's last level: per tile, the offset in quads

        uniform vec4 uWb;         // the prep pass's white balance (with the user's trim)
        uniform vec2 uNoise;      // variance of normalised RAW at x: x * S + O
        uniform float uGain;      // the reference's exposure over this frame's
        uniform ivec2 uRedOffset;
        uniform ivec2 uDebugShift; // debug (adb photoshake): this frame read that many quads off, a known shake

        const float SHIFT_TILE = $PHOTO_SHIFT_TILE.0;  // quads per side of photoAlign's last tiles

        float cur[4];
        float ref[4];
        float curVar;   // the share of one sample's noise variance left in cur

        /** This frame's offset from the reference at quad [q] (quads), between the tiles' centres. */
        vec2 shiftAt(ivec2 q) {
            ivec2 last = textureSize(uShift, 0) - 1;
            vec2 tc = (vec2(q) + 0.5) / SHIFT_TILE - 0.5;
            ivec2 t = ivec2(floor(tc));
            vec2 f = tc - vec2(t);
            vec2 a = texelFetch(uShift, clamp(t, ivec2(0), last), 0).xy;
            vec2 b = texelFetch(uShift, clamp(t + ivec2(1, 0), ivec2(0), last), 0).xy;
            vec2 c = texelFetch(uShift, clamp(t + ivec2(0, 1), ivec2(0), last), 0).xy;
            vec2 d = texelFetch(uShift, clamp(t + ivec2(1, 1), ivec2(0), last), 0).xy;
            return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
        }

        /** Catmull-Rom weights at fraction [t] (taps −1, 0, 1, 2). */
        vec4 catmullRom(float t) {
            float t2 = t * t;
            float t3 = t2 * t;
            return vec4(-0.5 * t3 + t2 - 0.5 * t, 1.5 * t3 - 2.5 * t2 + 1.0, -1.5 * t3 + 2.0 * t2 + 0.5 * t, 0.5 * t3 - 0.5 * t2);
        }

        /**
         * Quad [q]'s samples of this frame where the reference has them, times uGain, into [cur],
         * Catmull-Rom between quads (each colour's own samples): bilinear left what's averaged in
         * ~0.8 px softer (by a fraction of a quad, 2 px, that varies frame to frame).
         */
        void fetchAlignedSharp(ivec2 q, ivec2 size) {
            vec2 at = vec2(q + uDebugShift) + shiftAt(q);
            ivec2 a = ivec2(floor(at));
            vec2 f = at - vec2(a);
            vec4 wx = catmullRom(f.x);
            vec4 wy = catmullRom(f.y);
            ivec2 qLast = size / 2 - 1;
            for (int i = 0; i < 4; i++) cur[i] = 0.0;
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
                ivec2 p = clamp(a + ivec2(x - 1, y - 1), ivec2(0), qLast) * 2;
                float w = wx[x] * wy[y];
                for (int i = 0; i < 4; i++) cur[i] += w * texelFetch(uLinear, p + ivec2(i & 1, i >> 1), 0).r;
            }
            for (int i = 0; i < 4; i++) cur[i] *= uGain;
        }

        /** Quad [q]'s samples of this frame where the reference has them, times uGain, into [cur] (bilinear: for the tests). */
        void fetchAligned(ivec2 q, ivec2 size) {
            vec2 at = vec2(q + uDebugShift) + shiftAt(q);
            ivec2 a = ivec2(floor(at));
            vec2 f = at - vec2(a);
            ivec2 qLast = size / 2 - 1;
            ivec2 p00 = clamp(a, ivec2(0), qLast) * 2;
            ivec2 p10 = clamp(a + ivec2(1, 0), ivec2(0), qLast) * 2;
            ivec2 p01 = clamp(a + ivec2(0, 1), ivec2(0), qLast) * 2;
            ivec2 p11 = clamp(a + ivec2(1, 1), ivec2(0), qLast) * 2;
            for (int i = 0; i < 4; i++) {
                ivec2 o = ivec2(i & 1, i >> 1);
                float top = mix(texelFetch(uLinear, p00 + o, 0).r, texelFetch(uLinear, p10 + o, 0).r, f.x);
                float bottom = mix(texelFetch(uLinear, p01 + o, 0).r, texelFetch(uLinear, p11 + o, 0).r, f.x);
                cur[i] = mix(top, bottom, f.y) * uGain;
            }
            curVar = ((1.0 - f.x) * (1.0 - f.x) + f.x * f.x) * ((1.0 - f.y) * (1.0 - f.y) + f.y * f.y);
        }

        /** z² of quad [q0]'s change from the reference ([ref]) to this frame ([cur], from fetchAligned). */
        float quadZ2(ivec2 q0, ivec2 size) {
            vec4 gains = texture(uShading, (vec2(q0) + 1.0) / vec2(size)) * uWb;
            float d = 0.0;
            float v = 0.0;
            for (int i = 0; i < 4; i++) {
                ivec2 site = (q0 + ivec2(i & 1, i >> 1) + uRedOffset) & 1;
                float g = gains[site == ivec2(0, 0) ? 0 : site == ivec2(1, 1) ? 3 : site == ivec2(1, 0) ? 1 : 2];
                d += cur[i] - ref[i];
                // Prep value y = g · x: variance g·y·S + g²·O, for this frame and the reference.
                v += g * (max(cur[i], 0.0) * curVar + max(ref[i], 0.0)) * uNoise.x + (curVar + 1.0) * g * g * uNoise.y;
            }
            return d * d / max(v, 1e-12);
        }
    """.trimIndent() + "\n"

    /** Photo mode: each quad's [photoCommon] z² against the reference, for [photoMerge] (r32f, one per quad). */
    val photoDiff = photoCommon + """
        layout(r32f, binding = 0) readonly uniform highp image2D uRef;
        layout(r32f, binding = 1) writeonly uniform highp image2D uZ2;

        void main() {
            ivec2 q = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = imageSize(uRef);
            ivec2 q0 = q * 2;
            if (q0.x + 1 >= size.x || q0.y + 1 >= size.y) return;
            for (int i = 0; i < 4; i++) ref[i] = imageLoad(uRef, q0 + ivec2(i & 1, i >> 1)).r;
            fetchAligned(q, size);
            imageStore(uZ2, q, vec4(quadZ2(q0, size)));
        }
    """.trimIndent()

    /**
     * Photo mode: each burst frame, aligned, averaged into the first (the moment the shutter was
     * pressed) where it matches it within noise, one weight per 2×2 quad from the mean z² of the
     * 3×3 quads around it (noise alone: ~1, rarely past 2): full up to 2, none from 4, and none
     * where the quad alone is far off (z² 25). The mean, not each quad alone, decides, so pure
     * noise doesn't speckle an edge with quads in and out of the merge (the bus, 2026-11-01);
     * what moved within the scene, or wasn't aligned, keeps the first frame alone.
     * Modes: 0 the first frame (reference, sum and weight), 2 merge, 3 resolve (sum / weight into
     * the image at binding 0, the prep image then, for the final develop).
     */
    val photoMerge = photoCommon + """
        layout(binding = 3) uniform highp sampler2D uZ2;         // photoDiff's
        layout(r32f, binding = 0) uniform highp image2D uRef;     // the first frame; mode 3: the prep image
        layout(r32f, binding = 1) uniform highp image2D uSum;     // Σ weight × value per pixel
        layout(r32f, binding = 2) uniform highp image2D uWeight;  // Σ weight per 2×2 quad

        uniform int uMode;
        uniform float uScale;     // the lens's real noise (variance) over the profile's (photoNoise)

        void main() {
            ivec2 q = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = imageSize(uSum);
            ivec2 q0 = q * 2;
            if (q0.x + 1 >= size.x || q0.y + 1 >= size.y) return; // even sizes; an odd edge stays the first frame's
            if (uMode == 0) {
                for (int i = 0; i < 4; i++) {
                    ivec2 p = q0 + ivec2(i & 1, i >> 1);
                    float v = texelFetch(uLinear, p, 0).r;
                    imageStore(uRef, p, vec4(v));
                    imageStore(uSum, p, vec4(v));
                }
                imageStore(uWeight, q, vec4(1.0));
                return;
            }
            if (uMode == 3) {
                float w = imageLoad(uWeight, q).r;
                for (int i = 0; i < 4; i++) {
                    ivec2 p = q0 + ivec2(i & 1, i >> 1);
                    imageStore(uRef, p, vec4(imageLoad(uSum, p).r / w));
                }
                return;
            }
            ivec2 qLast = size / 2 - 1;
            float around = 0.0;
            for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++)
                around += texelFetch(uZ2, clamp(q + ivec2(x, y), ivec2(0), qLast), 0).r;
            float own = texelFetch(uZ2, q, 0).r;
            float w = (1.0 - smoothstep(2.0, 4.0, around / 9.0 / uScale)) * (1.0 - smoothstep(9.0, 25.0, own / uScale));
            if (w <= 0.0) return;
            fetchAlignedSharp(q, size);
            for (int i = 0; i < 4; i++) {
                ivec2 p = q0 + ivec2(i & 1, i >> 1);
                imageStore(uSum, p, vec4(imageLoad(uSum, p).r + w * cur[i]));
            }
            imageStore(uWeight, q, vec4(imageLoad(uWeight, q).r + w));
        }
    """.trimIndent()

    /**
     * Photo mode: a histogram of [photoCommon]'s z² over every 4th quad each way (quarter-stop
     * bins, bin 32 = z² 1), before a frame is merged. Its lower quartile gives the lens's real
     * noise: the profiles are off ×0.2–×3 per lens (X14), which would merge real differences on
     * one lens and refuse pure noise on another, and moving parts only raise the upper bins.
     */
    val photoNoise = photoCommon + """
        layout(r32f, binding = 0) readonly uniform highp image2D uRef;
        layout(std430, binding = 1) buffer Hist { uint hist[]; };

        shared uint sHist[64];

        void main() {
            uint li = gl_LocalInvocationIndex;
            if (li < 64u) sHist[li] = 0u;
            memoryBarrierShared();
            barrier();
            ivec2 size = imageSize(uRef);
            ivec2 q0 = ivec2(gl_GlobalInvocationID.xy) * 8;
            if (q0.x + 1 < size.x && q0.y + 1 < size.y) {
                for (int i = 0; i < 4; i++) ref[i] = imageLoad(uRef, q0 + ivec2(i & 1, i >> 1)).r;
                fetchAligned(q0 / 2, size);
                float z2 = quadZ2(q0, size);
                atomicAdd(sHist[clamp(int(floor(log2(max(z2, 1e-6)) * 4.0)) + 32, 0, 63)], 1u);
            }
            memoryBarrierShared();
            barrier();
            if (li < 64u && sHist[li] > 0u) atomicAdd(hist[li], sHist[li]);
        }
    """.trimIndent()

    /**
     * Photo mode's Texture / Clarity / Dehaze (Lightroom's Presence dials), step 1, one level per
     * pass: two pyramids of the view, level 0 at half its size, each level half the one before:
     * log brightness (r) and darkest channel (g); and the highlights' colour, what Dehaze's
     * negative side blooms. Level 0 is 2×2 means of the pixels; each level past it filters 4×4
     * texels of the one below, [1 3 3 1] each way: 2×2 means all the way down (0.8.1) alias, a
     * bright line or edge came out as a row of blobs that stepped along diagonals (owner: "jagged",
     * 2026-11-08). One texture per level: written as mip levels of one texture (the level below
     * read from it in the same pass) the Adreno 750 smeared bright areas along the rows.
     */
    val finishDown = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x = 16, local_size_y = 16) in;
        layout(binding = 0) uniform highp sampler2D uIn;     // level 0: the view; then the level below
        layout(binding = 1) uniform highp sampler2D uInGlow; // the glow level below (levels past 0)
        layout(rgba16f, binding = 0) writeonly uniform highp image2D uOut;
        layout(rgba16f, binding = 1) writeonly uniform highp image2D uOutGlow;
        uniform int uLevel;
        $FINISH_COMMON
        void main() {
            ivec2 o = ivec2(gl_GlobalInvocationID.xy);
            if (o.x >= imageSize(uOut).x || o.y >= imageSize(uOut).y) return;
            vec2 a = vec2(0.0);
            vec3 g = vec3(0.0);
            if (uLevel == 0) {
                // Measured per pixel, then averaged (brightness is a log).
                ivec2 last = textureSize(uIn, 0) - 1;
                for (int i = 0; i < 4; i++) {
                    vec3 c = clamp(texelFetch(uIn, min(o * 2 + ivec2(i & 1, i >> 1), last), 0).rgb, 0.0, 1.0);
                    a += 0.25 * brightnessAndDark(c);
                    g += 0.25 * glowSource(c);
                }
            } else {
                // The same filter in 4 bilinear taps: each pair of texels weighted 1:3 is one tap.
                vec2 texel = 1.0 / vec2(textureSize(uIn, 0));
                for (int i = 0; i < 4; i++) {
                    vec2 uv = (vec2(o * 2) + vec2((i & 1) == 0 ? 0.25 : 1.75, (i >> 1) == 0 ? 0.25 : 1.75)) * texel;
                    a += 0.25 * texture(uIn, uv).rg;
                    g += 0.25 * texture(uInGlow, uv).rgb;
                }
            }
            imageStore(uOut, o, vec4(a, 0.0, 1.0));
            imageStore(uOutGlow, o, vec4(g, 1.0));
        }
    """.trimIndent()

    /** What [finishDown] and [photoFinish] both measure per pixel. */
    private const val FINISH_COMMON = """
        const vec3 W709 = vec3(0.2126, 0.7152, 0.0722);
        const float EPS = 1.0 / 64.0;   // keeps log brightness out of the deepest shadows' noise

        /** log2 brightness and the darkest channel of display colour [c]. */
        vec2 brightnessAndDark(vec3 c) { return vec2(log2(dot(c, W709) + EPS), min(min(c.r, c.g), c.b)); }

        /**
         * The part of [c] that blooms: its colour, from mid-bright up. Bright partly by its
         * strongest channel: by brightness alone a saturated light (magenta LEDs, a red sign) is
         * dim, and didn't bloom (2026-11-07).
         */
        vec3 glowSource(vec3 c) { return c * smoothstep(0.35, 0.9, mix(dot(c, W709), max(max(c.r, c.g), c.b), 0.5)); }
    """

    /**
     * A level read as a cubic B-spline: smooth where bilinear, on a level 32× smaller than the
     * photo, shows its grid as creases, a staircase along diagonal edges (owner: "jagged", 2026-11-08).
     */
    private const val SMOOTH_READ = """
        /** [t] at [uv] as a cubic B-spline, in 4 bilinear taps (each pair of its 4×4 weights is one). */
        vec4 smoothRead(highp sampler2D t, vec2 uv) {
            vec2 size = vec2(textureSize(t, 0));
            vec2 x = uv * size - 0.5;
            vec2 i = floor(x);
            vec2 f = x - i;
            vec2 f2 = f * f;
            vec2 f3 = f2 * f;
            vec2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
            vec2 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
            vec2 w3 = f3 / 6.0;
            vec2 g0 = w0 + w1;
            vec2 g1 = 1.0 - g0;
            vec2 h0 = (i - 0.5 + w1 / g0) / size;
            vec2 h1 = (i + 1.5 + w3 / g1) / size;
            return g0.y * (g0.x * texture(t, h0) + g1.x * texture(t, vec2(h1.x, h0.y)))
                 + g1.y * (g0.x * texture(t, vec2(h0.x, h1.y)) + g1.x * texture(t, h1));
        }
    """

    /**
     * Step 1½: what [photoFinish] needs from the coarse levels, blended onto level 1's grid (a
     * quarter of the photo each way), so the full-size pass reads one image instead of a
     * B-spline of each level (29 taps a pixel; 14 now, 0.8.1 read 8): log brightness over ~50 px
     * (r) and the darkest channel over ~12 px (g); and the bloom, the highlights' colour over
     * ~3–50 px. Open gate 4K on the X14: the dials +2.5 ms a frame.
     */
    val finishUp = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x = 16, local_size_y = 16) in;
        // finishDown's levels as blurred as level n of the full photo's (the renderer binds them).
        layout(binding = 0) uniform highp sampler2D uB3;
        layout(binding = 1) uniform highp sampler2D uB5;
        layout(binding = 2) uniform highp sampler2D uG1;
        layout(binding = 3) uniform highp sampler2D uG2;
        layout(binding = 4) uniform highp sampler2D uG3;
        layout(binding = 5) uniform highp sampler2D uG4;
        layout(binding = 6) uniform highp sampler2D uG5;
        layout(rgba16f, binding = 0) writeonly uniform highp image2D uOut;
        layout(rgba16f, binding = 1) writeonly uniform highp image2D uOutGlow;
        uniform bool uGlow;  // Dehaze below 0
        $SMOOTH_READ
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = imageSize(uOut);
            if (p.x >= size.x || p.y >= size.y) return;
            vec2 uv = (vec2(p) + 0.5) / vec2(size);
            imageStore(uOut, p, vec4(smoothRead(uB5, uv).r, smoothRead(uB3, uv).g, 0.0, 1.0));
            if (uGlow) {
                vec3 glow = 0.05 * smoothRead(uG1, uv).rgb + 0.25 * smoothRead(uG2, uv).rgb + 0.25 * smoothRead(uG3, uv).rgb
                          + 0.35 * smoothRead(uG4, uv).rgb + 0.1 * smoothRead(uG5, uv).rgb;
                imageStore(uOutGlow, p, vec4(glow, 1.0));
            }
        }
    """.trimIndent()

    /**
     * Step 2, on the finished view (look and all, display-referred), tuned on the owner's photos
     * (a prototype with the same pyramids, 2026-11-07; the first version barely showed: absolute
     * differences in the dark, a midtone weight that left night scenes out, a veil that only
     * flattened):
     * - Texture and Clarity act on log brightness (a ratio, so dark scenes get them too): Texture
     *   the detail between ~1 and ~3 px of the full photo, Clarity the local contrast between ~3
     *   and ~50 px, softened where it's strong (less halo), left out only near black and white.
     * - Dehaze +: lifts a veil, the darkest channel over ~12 px (never above the pixel's own, at
     *   most 45 % of it, so white stays white), stretches back, and adds some local contrast and
     *   saturation. Dehaze −: a light veil toward the surroundings' brightness and a bloom: the
     *   highlights' colour spread over ~3–50 px, screened on (owner: "bring out highlights as
     *   bloomy"), easing into white instead of clipping (a flat, hard-edged band where it did).
     * Level 1 and finishUp's images are read as cubic B-splines ([SMOOTH_READ]). The levels and
     * weights keep 0.8.1's radii (its pyramid blurred ~1.5× less per level; numpy fit, 2026-11-08).
     * Radii are the full photo's: the half-size viewfinder gets the pyramids one level lower, so
     * it shows what the photo will get. Amounts −1..1.
     */
    val photoFinish = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x = 16, local_size_y = 16) in;
        layout(binding = 0) uniform highp sampler2D uView;
        // finishDown's levels 0 and 1 as blurred as the full photo's (log brightness; the renderer
        // binds the right ones for the view's size; uB0Self: at half size level 0 is the pixel
        // itself), and finishUp's images.
        layout(binding = 1) uniform highp sampler2D uB0;
        layout(binding = 2) uniform highp sampler2D uB1;
        layout(binding = 3) uniform highp sampler2D uCoarse;
        layout(binding = 4) uniform highp sampler2D uGlow;
        layout(rgba16f, binding = 0) writeonly uniform highp image2D uOut;
        uniform bool uB0Self;
        uniform float uTexture;
        uniform float uClarity;
        uniform float uDehaze;
        $FINISH_COMMON
        $SMOOTH_READ

        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = imageSize(uOut);
            if (p.x >= size.x || p.y >= size.y) return;
            vec2 uv = (vec2(p) + 0.5) / vec2(size);
            vec3 c = clamp(texelFetch(uView, p, 0).rgb, 0.0, 1.0);
            vec2 self = brightnessAndDark(c);
            float b0 = uB0Self ? self.x : texture(uB0, uv).r;
            float b1 = smoothRead(uB1, uv).r;
            vec2 coarse = smoothRead(uCoarse, uv).rg;
            float extraClarity = 0.0;
            if (uDehaze > 0.0) {
                float veil = 0.45 * uDehaze * min(coarse.y, self.y);
                c = (c - veil) / (1.0 - veil);
                extraClarity = 0.3 * uDehaze;
            }
            float detail = 1.15 * (b0 - b1);
            float local = 0.77 * (b1 - coarse.x);
            local /= 1.0 + 0.7 * abs(local);
            float lc = log2(dot(c, W709) + EPS);
            float midtones = smoothstep(-7.0, -4.5, lc) * (1.0 - smoothstep(-0.7, 0.0, lc));
            float kTexture = uTexture * (uTexture > 0.0 ? 1.6 : 1.0);
            float kClarity = uClarity * (uClarity > 0.0 ? 1.2 : 1.0) + extraClarity;
            c *= exp2(kTexture * detail + kClarity * local * midtones);
            if (uDehaze > 0.0) {
                float y = dot(c, W709);
                c = vec3(y) + (c - vec3(y)) * (1.0 + 0.25 * uDehaze);
            } else if (uDehaze < 0.0) {
                float s = -uDehaze;
                vec3 glow = smoothRead(uGlow, uv).rgb;
                c += (vec3(0.05 + 0.7 * exp2(coarse.x)) - c) * (0.12 * s);
                // The bloom's strength, easing into 1 over 0.65..1.35 (quadratic knee) instead of clipping.
                const float KNEE = 0.35;
                vec3 x = 2.5 * s * glow;
                vec3 over = clamp(x - (1.0 - KNEE), 0.0, 2.0 * KNEE);
                vec3 amount = min(x - over * over / (4.0 * KNEE), 1.0);
                c = 1.0 - (1.0 - clamp(c, 0.0, 1.0)) * (1.0 - amount);
            }
            imageStore(uOut, p, vec4(clamp(c, 0.0, 1.0), 1.0));
        }
    """.trimIndent()

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
