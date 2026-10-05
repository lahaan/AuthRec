package com.authrec.color

/** CIE xy chromaticities of a colour space's primaries and white point. */
data class Primaries(
    val rx: Double, val ry: Double,
    val gx: Double, val gy: Double,
    val bx: Double, val by: Double,
    val wx: Double = 0.3127, val wy: Double = 0.3290, // D65
) {
    companion object {
        val REC709 = Primaries(0.640, 0.330, 0.300, 0.600, 0.150, 0.060)
        val REC2020 = Primaries(0.708, 0.292, 0.170, 0.797, 0.131, 0.046)
        val S_GAMUT3_CINE = Primaries(0.766, 0.275, 0.225, 0.800, 0.089, -0.087)
        val ARRI_WIDE_GAMUT3 = Primaries(0.6840, 0.3130, 0.2210, 0.8480, 0.0861, -0.1020)
    }
}

/** 3×3 matrices as row-major DoubleArray(9). */
object ColorMath {

    fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    fun apply(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { r ->
        m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2]
    }

    fun invert(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        require(kotlin.math.abs(det) > 1e-12) { "singular matrix" }
        return doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
    }

    /** Linear RGB in the given primaries → CIE XYZ (standard derivation from chromaticities). */
    fun rgbToXyz(p: Primaries): DoubleArray {
        fun xyz(x: Double, y: Double) = doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
        val r = xyz(p.rx, p.ry)
        val g = xyz(p.gx, p.gy)
        val b = xyz(p.bx, p.by)
        val primaries = doubleArrayOf(r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2])
        val s = apply(invert(primaries), xyz(p.wx, p.wy))
        return DoubleArray(9) { i -> primaries[i] * s[i % 3] }
    }

    /** Linear RGB in [from] → linear RGB in [to]; both share D65, so no adaptation is needed. */
    fun convert(from: Primaries, to: Primaries) = multiply(invert(rgbToXyz(to)), rgbToXyz(from))

    /** Row-major → column-major floats, the layout glUniformMatrix3fv expects with transpose = false. */
    fun toGlColumnMajor(m: DoubleArray) = FloatArray(9) { i -> m[(i % 3) * 3 + i / 3].toFloat() }
}
