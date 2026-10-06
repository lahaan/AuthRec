package com.authrec.gl

/**
 * GLSL for the RAW → log pipeline.
 *
 * Pass 1 (prep): 16-bit RAW → float, per-pixel black level, lens shading and white balance,
 *   with highlights clipped where the first channel saturates so they stay neutral.
 * Pass 2 (develop): Malvar-He-Cutler demosaic (or 2×2 superpixel) → camera-to-target-gamut
 *   matrix → log curve.
 * Pass 3 (finish): optional chroma noise reduction in log space → the view (3D LUT at
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
                // Same-colour neighbours are 2 px away. Only a pixel far outside all four of them
                // is treated as a defect, so thin lines and real detail survive.
                float a = rawAt(p + ivec2(2, 0), black), b = rawAt(p - ivec2(2, 0), black);
                float c = rawAt(p + ivec2(0, 2), black), d = rawAt(p - ivec2(0, 2), black);
                float hi = max(max(a, b), max(c, d));
                float lo = min(min(a, b), min(c, d));
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

    /** Uniforms and helpers shared by both develop variants. */
    private val developCommon = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x = 16, local_size_y = 16) in;

        layout(binding = 0) uniform highp sampler2D uLinear;     // r32f from the prep pass
        layout(rgba16f, binding = 0) writeonly uniform highp image2D uOutLog;

        uniform ivec2 uRedOffset;
        uniform mat3 uCamToTarget;   // white-balanced camera RGB → linear target gamut
        uniform float uExposure;     // linear gain on top of the ISP's auto exposure
        uniform int uCurve;          // LogProfile.shaderId

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

        /** Camera RGB → log; the finish pass takes it from there. */
        void develop(ivec2 p, vec3 cam) {
            vec3 lin = uCamToTarget * cam * uExposure;
            imageStore(uOutLog, p, vec4(encodeLog(lin.r), encodeLog(lin.g), encodeLog(lin.b), 1.0));
        }
    """.trimIndent() + "\n"

    /** Full-resolution output with Malvar-He-Cutler 5×5 linear demosaic. */
    val develop = developCommon + """
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
            develop(p, cam);
        }
    """.trimIndent()

    /** Half-resolution output: one RGB pixel per 2×2 CFA block, no interpolation at all. */
    val developSuperpixel = developCommon + """
        void main() {
            ivec2 o = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = textureSize(uLinear, 0) / 2;
            if (o.x >= size.x || o.y >= size.y) return;

            ivec2 base = o * 2;
            vec3 cam = vec3(
                px(base + uRedOffset),
                0.5 * (px(base + ivec2(1 - uRedOffset.x, uRedOffset.y)) + px(base + ivec2(uRedOffset.x, 1 - uRedOffset.y))),
                px(base + ivec2(1) - uRedOffset));
            develop(o, cam);
        }
    """.trimIndent()

    /**
     * Chroma noise reduction keeps each pixel's brightness (luma) exactly and replaces only its
     * colour difference with a weighted average of a 5×5 neighbourhood (stride 2 or 3 px).
     * Neighbours whose brightness differs get little weight, so colour doesn't bleed across edges
     * and luma detail/grain is untouched; that's what keeps it from going blotchy.
     */
    val finish = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x = 16, local_size_y = 16) in;

        layout(binding = 0) uniform highp sampler2D uLogIn;
        layout(binding = 1) uniform mediump sampler3D uLut;
        layout(rgba16f, binding = 0) writeonly uniform highp image2D uOutView;
        layout(rgba16f, binding = 1) writeonly uniform highp image2D uOutLog;

        uniform int uChromaNr;       // 0 off, 1 low, 2 high
        uniform bool uWriteLog;      // the clean log image is wanted (recording log, or a capture)
        uniform bool uUseLut;
        uniform bool uLutConvert;    // the LUT expects another log encoding than ours
        uniform int uCurve;          // ours (LogProfile.shaderId)
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
         */
        vec3 lutTetra(vec3 c) {
            float n = uLutSize - 1.0;
            vec3 p = clamp(c, 0.0, 1.0) * n;
            ivec3 i0 = min(ivec3(floor(p)), ivec3(int(n) - 1));
            vec3 f = p - vec3(i0);
            ivec3 i1 = i0 + 1;
            vec3 c000 = texelFetch(uLut, i0, 0).rgb;
            vec3 c111 = texelFetch(uLut, i1, 0).rgb;
            if (f.r > f.g) {
                if (f.g > f.b) {
                    vec3 c100 = texelFetch(uLut, ivec3(i1.x, i0.y, i0.z), 0).rgb;
                    vec3 c110 = texelFetch(uLut, ivec3(i1.x, i1.y, i0.z), 0).rgb;
                    return c000 + f.r * (c100 - c000) + f.g * (c110 - c100) + f.b * (c111 - c110);
                } else if (f.r > f.b) {
                    vec3 c100 = texelFetch(uLut, ivec3(i1.x, i0.y, i0.z), 0).rgb;
                    vec3 c101 = texelFetch(uLut, ivec3(i1.x, i0.y, i1.z), 0).rgb;
                    return c000 + f.r * (c100 - c000) + f.b * (c101 - c100) + f.g * (c111 - c101);
                } else {
                    vec3 c001 = texelFetch(uLut, ivec3(i0.x, i0.y, i1.z), 0).rgb;
                    vec3 c101 = texelFetch(uLut, ivec3(i1.x, i0.y, i1.z), 0).rgb;
                    return c000 + f.b * (c001 - c000) + f.r * (c101 - c001) + f.g * (c111 - c101);
                }
            } else {
                if (f.b > f.g) {
                    vec3 c001 = texelFetch(uLut, ivec3(i0.x, i0.y, i1.z), 0).rgb;
                    vec3 c011 = texelFetch(uLut, ivec3(i0.x, i1.y, i1.z), 0).rgb;
                    return c000 + f.b * (c001 - c000) + f.g * (c011 - c001) + f.r * (c111 - c011);
                } else if (f.b > f.r) {
                    vec3 c010 = texelFetch(uLut, ivec3(i0.x, i1.y, i0.z), 0).rgb;
                    vec3 c011 = texelFetch(uLut, ivec3(i0.x, i1.y, i1.z), 0).rgb;
                    return c000 + f.g * (c010 - c000) + f.b * (c011 - c010) + f.r * (c111 - c011);
                } else {
                    vec3 c010 = texelFetch(uLut, ivec3(i0.x, i1.y, i0.z), 0).rgb;
                    vec3 c110 = texelFetch(uLut, ivec3(i1.x, i1.y, i0.z), 0).rgb;
                    return c000 + f.g * (c010 - c000) + f.r * (c110 - c010) + f.b * (c111 - c110);
                }
            }
        }

        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = textureSize(uLogIn, 0);
            if (p.x >= size.x || p.y >= size.y) return;

            vec3 c = texelFetch(uLogIn, p, 0).rgb;
            if (uChromaNr > 0) {
                int stride = uChromaNr == 1 ? 2 : 3;
                float y0 = luma(c);
                vec3 acc = vec3(0.0);
                float wsum = 0.0;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        ivec2 q = clamp(p + ivec2(dx, dy) * stride, ivec2(0), size - 1);
                        vec3 s = texelFetch(uLogIn, q, 0).rgb;
                        float ys = luma(s);
                        float dl = ys - y0;
                        // Gaussian in space; brightness differences of ~0.03 log units cut the weight hard.
                        float w = exp(-float(dx * dx + dy * dy) / 4.5 - dl * dl * 600.0);
                        acc += (s - vec3(ys)) * w;
                        wsum += w;
                    }
                }
                c = vec3(y0) + acc / wsum;
            }
            if (uWriteLog) imageStore(uOutLog, p, vec4(c, 1.0));
            vec3 view = c;
            if (uUseLut) {
                // Tone balance belongs to the view (and a baked recording), never the clean log above.
                vec3 t = uTone ? balanceTone(c) : c;
                view = mix(t, lutTetra(uLutConvert ? toLutInput(t) : t), uLutStrength);
            }
            imageStore(uOutView, p, vec4(look(view), 1.0));
        }
    """.trimIndent()

    val displayVertex = """
        #version 300 es
        out vec2 vUv;
        uniform int uRotation;  // quarter turns clockwise
        void main() {
            // One oversized triangle covering the viewport; no vertex buffers needed.
            vec2 pos = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);
            gl_Position = vec4(pos, 0.0, 1.0);
            vec2 uv = vec2(pos.x * 0.5 + 0.5, 0.5 - pos.y * 0.5);  // texture row 0 = top of screen
            for (int i = 0; i < uRotation; i++) uv = vec2(uv.y, 1.0 - uv.x);
            vUv = uv;
        }
    """.trimIndent()

    /** Draws a pipeline image; on the preview only, optional focus peaking marks sharp edges red. */
    val displayFragment = """
        #version 300 es
        precision mediump float;
        in vec2 vUv;
        uniform sampler2D uImage;
        uniform bool uPeaking;
        uniform vec2 uTexel;  // one source pixel in uv units
        out vec4 fragColor;

        float luma(vec2 uv) { return dot(texture(uImage, uv).rgb, vec3(0.2126, 0.7152, 0.0722)); }

        /** Luma averaged over ~3×3 source pixels (4 bilinear taps), so sensor noise doesn't read as edges. */
        float lumaSmooth(vec2 uv) {
            vec2 h = uTexel * 0.5;
            return 0.25 * (luma(uv + vec2(-h.x, -h.y)) + luma(uv + vec2(h.x, -h.y)) +
                           luma(uv + vec2(-h.x, h.y)) + luma(uv + vec2(h.x, h.y)));
        }

        void main() {
            vec3 c = texture(uImage, vUv).rgb;
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
