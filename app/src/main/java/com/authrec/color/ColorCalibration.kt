package com.authrec.color

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.ColorSpaceTransform
import kotlin.math.abs

/**
 * The sensor's own colour calibration (the matrices DNG files carry), used when a lens doesn't
 * report the ISP's per-frame white balance / colour matrix. On the Xiaomi 14 main camera this
 * route matched the ISP's colours within ~2.5° of hue in daylight tests.
 */
class ColorCalibration private constructor(
    private val cm1: DoubleArray, private val cm2: DoubleArray,
    private val fm1: DoubleArray, private val fm2: DoubleArray,
    private val cct1: Double, private val cct2: Double,
) {
    /** White-balanced camera RGB → linear sRGB/Rec.709 for a scene whose neutral is [neutral] (camera RGB). */
    fun camToRec709(neutral: DoubleArray): DoubleArray {
        val w = illuminantWeight(neutral)
        val fm = DoubleArray(9) { w * fm1[it] + (1 - w) * fm2[it] }
        return ColorMath.multiply(ColorMath.multiply(XYZ_TO_REC709, BRADFORD_D50_TO_D65), fm)
    }

    /** Camera-RGB neutral (white) under daylight: a sane white balance when nothing better is known. */
    fun daylightNeutral(): DoubleArray {
        val n = ColorMath.apply(if (cct1 > cct2) cm1 else cm2, D65_XYZ)
        return DoubleArray(3) { n[it] / n[1] }
    }

    /** DNG spec: find the scene's colour temperature from its neutral, interpolate in 1/CCT. */
    private fun illuminantWeight(neutral: DoubleArray): Double {
        var cct = 5000.0
        repeat(20) {
            val w = weightFor(cct)
            val cm = DoubleArray(9) { w * cm1[it] + (1 - w) * cm2[it] }
            val xyz = ColorMath.apply(ColorMath.invert(cm), neutral)
            val sum = xyz.sum()
            cct = mcCamy(xyz[0] / sum, xyz[1] / sum)
        }
        return weightFor(cct)
    }

    private fun weightFor(cct: Double) = ((1 / cct - 1 / cct2) / (1 / cct1 - 1 / cct2)).coerceIn(0.0, 1.0)

    private fun mcCamy(x: Double, y: Double): Double {
        val n = (x - 0.3320) / (0.1858 - y)
        return (449 * n * n * n + 3525 * n * n + 6823.3 * n + 5520.33).coerceIn(2000.0, 12000.0)
    }

    companion object {
        private val XYZ_TO_REC709 = doubleArrayOf(
            3.2404542, -1.5371385, -0.4985314,
            -0.9692660, 1.8760108, 0.0415560,
            0.0556434, -0.2040259, 1.0572252,
        )
        private val BRADFORD_D50_TO_D65 = doubleArrayOf(
            0.9555766, -0.0230393, 0.0631636,
            -0.0282895, 1.0099416, 0.0210077,
            0.0122982, -0.0204830, 1.3299098,
        )
        private val D65_XYZ = doubleArrayOf(0.95047, 1.0, 1.08883)

        /** EXIF light-source codes (what SENSOR_REFERENCE_ILLUMINANT uses) → colour temperature. */
        private fun cctOf(illuminant: Int?) = when (illuminant) {
            17, 3 -> 2856.0     // Standard A / tungsten
            18 -> 4874.0        // Standard B
            19 -> 6774.0        // Standard C
            20 -> 5503.0        // D55
            21, 1 -> 6504.0     // D65 / daylight
            22 -> 7504.0        // D75
            23 -> 5003.0        // D50
            24 -> 3200.0        // ISO studio tungsten
            2, 14 -> 4230.0     // fluorescent (cool white)
            else -> 5003.0
        }

        private fun det(m: DoubleArray) =
            m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])

        private fun ColorSpaceTransform.toArray() = DoubleArray(9) { getElement(it % 3, it / 3).toDouble() }

        /** null if the lens doesn't publish calibration matrices. */
        fun from(c: CameraCharacteristics): ColorCalibration? {
            val cm1 = c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.toArray() ?: return null
            val fm1 = c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.toArray() ?: return null
            val cal1 = c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)?.toArray()
            val cm2 = c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.toArray() ?: cm1
            val fm2 = c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.toArray() ?: fm1
            val cal2 = c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)?.toArray()
            val cct1 = cctOf(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1))
            val cct2 = cctOf(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()).let { if (it == cct1) it + 1 else it }
            val c1 = cal1?.let { ColorMath.multiply(it, cm1) } ?: cm1
            val c2 = cal2?.let { ColorMath.multiply(it, cm2) } ?: cm2
            // Some lenses (e.g. Xiaomi's hidden telephoto) publish zeroed placeholder matrices.
            if (listOf(c1, c2, fm1, fm2).any { abs(det(it)) < 1e-4 }) return null
            return ColorCalibration(c1, c2, fm1, fm2, cct1, cct2)
        }
    }
}
