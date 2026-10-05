package com.authrec.color

import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Log encodings AuthRec records in. Using published camera log curves (instead of a custom one)
 * means existing .cube LUTs built for them work as-is.
 *
 * [shaderId] must match the switch in the pipeline's GLSL `encodeLog()`.
 */
enum class LogProfile(val label: String, val primaries: Primaries, val shaderId: Int) {

    /** Apple Log (Apple "Apple Log Profile" white paper), Rec.2020 primaries. */
    APPLE_LOG("Apple Log", Primaries.REC2020, 0) {
        override fun encode(x: Double) = when {
            x >= AL_RT -> AL_G * log2(x + AL_B) + AL_D
            x >= AL_R0 -> AL_C * (x - AL_R0).pow(2)
            else -> 0.0
        }

        override fun decode(y: Double) = when {
            y >= AL_PT -> 2.0.pow((y - AL_D) / AL_G) - AL_B
            y > 0 -> sqrt(y / AL_C) + AL_R0
            else -> AL_R0
        }
    },

    /** Sony S-Log3 with S-Gamut3.Cine primaries. */
    SLOG3("S-Log3", Primaries.S_GAMUT3_CINE, 1) {
        override fun encode(x: Double) = if (x >= 0.01125) {
            (420.0 + log10((x + 0.01) / 0.19) * 261.5) / 1023.0
        } else {
            (x * (171.2102946929 - 95.0) / 0.01125 + 95.0) / 1023.0
        }

        override fun decode(y: Double): Double {
            val v = y * 1023.0
            return if (v >= 171.2102946929) {
                10.0.pow((v - 420.0) / 261.5) * 0.19 - 0.01
            } else {
                (v - 95.0) * 0.01125 / (171.2102946929 - 95.0)
            }
        }
    },

    /** ARRI LogC3 at EI 800 with ARRI Wide Gamut 3 primaries. */
    LOGC3("LogC3", Primaries.ARRI_WIDE_GAMUT3, 2) {
        override fun encode(x: Double) = if (x > LC_CUT) LC_C * log10(LC_A * x + LC_B) + LC_D else LC_E * x + LC_F

        override fun decode(y: Double) = if (y > LC_E * LC_CUT + LC_F) {
            (10.0.pow((y - LC_D) / LC_C) - LC_B) / LC_A
        } else {
            (y - LC_F) / LC_E
        }
    };

    /** Scene-linear (0.18 = middle grey) → 0..1 code value. */
    abstract fun encode(x: Double): Double

    /** 0..1 code value → scene-linear. */
    abstract fun decode(y: Double): Double
}

private const val AL_R0 = -0.05641088
private const val AL_RT = 0.01
private const val AL_C = 47.28711236
private const val AL_B = 0.00964052
private const val AL_G = 0.08550479
private const val AL_D = 0.69336945
private const val AL_PT = AL_C * (AL_RT - AL_R0) * (AL_RT - AL_R0)

private const val LC_CUT = 0.010591
private const val LC_A = 5.555556
private const val LC_B = 0.052272
private const val LC_C = 0.247190
private const val LC_D = 0.385537
private const val LC_E = 5.367655
private const val LC_F = 0.092809
