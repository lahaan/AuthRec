package com.authrec.color

import java.io.File
import java.io.OutputStream
import java.util.Locale

/**
 * A 3D LUT: [size]³ RGB entries, red varying fastest (the .cube layout), input domain 0..1.
 */
class CubeLut(val name: String, val size: Int, val rgb: FloatArray) {

    init {
        require(rgb.size == size * size * size * 3) { "LUT data doesn't match size $size" }
    }

    /** Writes Adobe/Resolve .cube text. */
    fun write(out: OutputStream) {
        out.bufferedWriter().apply {
            write("TITLE \"${name.replace("\"", "'")}\"\n")
            write("LUT_3D_SIZE $size\n")
            for (i in 0 until size * size * size) {
                write(String.format(Locale.US, "%.6f %.6f %.6f\n", rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2]))
            }
            flush()
        }
    }

    /**
     * Applies the LUT to packed ARGB pixels (input 0..255 per channel) with trilinear
     * interpolation; for CPU previews such as the look editor.
     */
    fun applyTo(src: IntArray, dst: IntArray) {
        val n = size
        val scale = (n - 1) / 255f
        val out = FloatArray(3)
        for (i in src.indices) {
            val p = src[i]
            val r = ((p shr 16) and 0xFF) * scale
            val g = ((p shr 8) and 0xFF) * scale
            val b = (p and 0xFF) * scale
            val r0 = r.toInt().coerceAtMost(n - 2)
            val g0 = g.toInt().coerceAtMost(n - 2)
            val b0 = b.toInt().coerceAtMost(n - 2)
            val fr = r - r0
            val fg = g - g0
            val fb = b - b0
            for (c in 0..2) {
                fun at(ri: Int, gi: Int, bi: Int) = rgb[((bi * n + gi) * n + ri) * 3 + c]
                val c00 = at(r0, g0, b0) + (at(r0 + 1, g0, b0) - at(r0, g0, b0)) * fr
                val c10 = at(r0, g0 + 1, b0) + (at(r0 + 1, g0 + 1, b0) - at(r0, g0 + 1, b0)) * fr
                val c01 = at(r0, g0, b0 + 1) + (at(r0 + 1, g0, b0 + 1) - at(r0, g0, b0 + 1)) * fr
                val c11 = at(r0, g0 + 1, b0 + 1) + (at(r0 + 1, g0 + 1, b0 + 1) - at(r0, g0 + 1, b0 + 1)) * fr
                val c0 = c00 + (c10 - c00) * fg
                val c1 = c01 + (c11 - c01) * fg
                out[c] = c0 + (c1 - c0) * fb
            }
            dst[i] = (0xFF shl 24) or
                ((out[0] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                ((out[1] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                (out[2] * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
    }

    /**
     * Tetrahedral interpolation at ([r], [g], [b]) (clamped to 0..1) into [out], the same split
     * into tetrahedra as the pipeline shader's `lutTetra`, so a LUT baked from this one on the CPU
     * matches what the GPU would show.
     */
    fun sampleTetra(r: Float, g: Float, b: Float, out: FloatArray) {
        val n = size - 1
        val pr = r.coerceIn(0f, 1f) * n
        val pg = g.coerceIn(0f, 1f) * n
        val pb = b.coerceIn(0f, 1f) * n
        val r0 = minOf(pr.toInt(), n - 1)
        val g0 = minOf(pg.toInt(), n - 1)
        val b0 = minOf(pb.toInt(), n - 1)
        val fr = pr - r0
        val fg = pg - g0
        val fb = pb - b0
        val r1 = r0 + 1
        val g1 = g0 + 1
        val b1 = b0 + 1
        for (c in 0..2) {
            fun at(ri: Int, gi: Int, bi: Int) = rgb[((bi * size + gi) * size + ri) * 3 + c]
            val c000 = at(r0, g0, b0)
            val c111 = at(r1, g1, b1)
            out[c] = if (fr > fg) {
                if (fg > fb) {
                    val c100 = at(r1, g0, b0); val c110 = at(r1, g1, b0)
                    c000 + fr * (c100 - c000) + fg * (c110 - c100) + fb * (c111 - c110)
                } else if (fr > fb) {
                    val c100 = at(r1, g0, b0); val c101 = at(r1, g0, b1)
                    c000 + fr * (c100 - c000) + fb * (c101 - c100) + fg * (c111 - c101)
                } else {
                    val c001 = at(r0, g0, b1); val c101 = at(r1, g0, b1)
                    c000 + fb * (c001 - c000) + fr * (c101 - c001) + fg * (c111 - c101)
                }
            } else {
                if (fb > fg) {
                    val c001 = at(r0, g0, b1); val c011 = at(r0, g1, b1)
                    c000 + fb * (c001 - c000) + fg * (c011 - c001) + fr * (c111 - c011)
                } else if (fb > fr) {
                    val c010 = at(r0, g1, b0); val c011 = at(r0, g1, b1)
                    c000 + fg * (c010 - c000) + fb * (c011 - c010) + fr * (c111 - c011)
                } else {
                    val c010 = at(r0, g1, b0); val c110 = at(r1, g1, b0)
                    c000 + fg * (c010 - c000) + fr * (c110 - c010) + fb * (c111 - c110)
                }
            }
        }
    }

    companion object {

        /** Parses an Adobe/Resolve .cube file (3D only). */
        fun parse(file: File): CubeLut {
            var size = 0
            val values = ArrayList<Float>()
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val parts = line.split(Regex("\\s+"))
                when {
                    parts[0] == "LUT_3D_SIZE" -> size = parts[1].toInt()
                    parts[0] == "LUT_1D_SIZE" -> error("1D LUTs aren't supported")
                    parts[0] == "DOMAIN_MIN" || parts[0] == "DOMAIN_MAX" -> {
                        require(parts.drop(1).all { it.toFloat() == if (parts[0] == "DOMAIN_MIN") 0f else 1f }) {
                            "custom LUT domains aren't supported"
                        }
                    }
                    parts[0][0].isLetter() -> Unit // TITLE and other keywords
                    else -> parts.take(3).forEach { values += it.toFloat() }
                }
            }
            require(size > 1) { "missing LUT_3D_SIZE" }
            return CubeLut(file.nameWithoutExtension, size, values.toFloatArray())
        }
    }
}
