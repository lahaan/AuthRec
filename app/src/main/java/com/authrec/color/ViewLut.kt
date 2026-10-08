package com.authrec.color

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.tanh

/**
 * The view's whole log → display mapping in one 3D LUT: eDR's tone curve, the conversion to the
 * log format an imported LUT expects, the LUT itself and its strength. The finish shader can do
 * all of that per pixel (`viewOf` in PipelineShaders), but at 4K that is a dozen pow/log/exp a
 * pixel, 12.6 million times a frame (~10 ms on the X14); baked here on the CPU when a setting
 * changes, the GPU does one tetrahedral lookup instead. The same functions as the shader, in
 * doubles.
 */
object ViewLut {

    const val SIZE = 33

    /** Our log ([profile]) → display, as the shader would compute it for these settings. */
    fun bake(source: CubeLut, profile: LogProfile, lutInput: LogProfile?, toneHi: Float, toneLo: Float, strength: Float,
             size: Int = SIZE): CubeLut {
        val toneOn = kotlin.math.abs(toneHi) > 0.001f || kotlin.math.abs(toneLo) > 0.001f
        val toXyz = ColorMath.rgbToXyz(profile.primaries)
        val lumaW = doubleArrayOf(toXyz[3], toXyz[4], toXyz[5])
        val convert = lutInput?.takeIf { it != profile }
        val gamut = convert?.let { ColorMath.convert(profile.primaries, it.primaries) }
        val out = FloatArray(size * size * size * 3)
        val c = DoubleArray(3)
        val lin = DoubleArray(3)
        val sample = FloatArray(3)
        var i = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            c[0] = r.toDouble() / (size - 1)
            c[1] = g.toDouble() / (size - 1)
            c[2] = b.toDouble() / (size - 1)
            // eDR (balanceTone): a luminance gain in stops, applied to all three channels.
            if (toneOn) {
                for (k in 0..2) lin[k] = profile.decode(c[k])
                val y = lin[0] * lumaW[0] + lin[1] * lumaW[1] + lin[2] * lumaW[2]
                if (y > 1e-5) {
                    val e = log2(y / 0.18)
                    val hi = 0.6 * toneHi * softplus(e - 1, 0.5)
                    val lo = 2.5 * toneLo * tanh(softplus(-1 - e, 0.5) / 5)
                    val gain = 2.0.pow(hi + lo)
                    for (k in 0..2) c[k] = profile.encode(lin[k] * gain)
                }
            }
            // Into the LUT's own log encoding, if it expects another one.
            var u0 = c[0]
            var u1 = c[1]
            var u2 = c[2]
            if (convert != null && gamut != null) {
                for (k in 0..2) lin[k] = profile.decode(c[k])
                val m = ColorMath.apply(gamut, lin)
                u0 = convert.encode(m[0])
                u1 = convert.encode(m[1])
                u2 = convert.encode(m[2])
            }
            source.sampleTetra(u0.toFloat(), u1.toFloat(), u2.toFloat(), sample)
            for (k in 0..2) out[i++] = (c[k] + (sample[k] - c[k]) * strength).toFloat()
        }
        return CubeLut("${source.name} (view)", size, out)
    }

    private fun softplus(x: Double, w: Double) = w * ln(1 + exp(x / w))
}
