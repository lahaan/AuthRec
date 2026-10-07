package com.authrec.color

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A simple, LUT-bakeable grade on top of a log profile: log → linear → Rec.709 → exposure and
 * white balance → filmic tone curve → contrast / highlights / shadows → colour (saturation,
 * vibrance, colour mixer) → split toning → fade. Every built-in "film" look is just a preset of
 * these parameters, and the look editor edits the same parameters, so any look can be tweaked
 * and exported as a .cube.
 */
data class LookParams(
    /** Stops. */
    val exposure: Float = 0f,
    /** −1..1 */
    val contrast: Float = 0f,
    /** −1..1; negative rolls highlights off softly. */
    val highlights: Float = 0f,
    /** −1..1; positive opens shadows up. */
    val shadows: Float = 0f,
    /** 0..1; lifts blacks and lowers whites like a faded print. */
    val fade: Float = 0f,
    /** −1 (cool) .. 1 (warm). */
    val temperature: Float = 0f,
    /** −1 (green) .. 1 (magenta). */
    val tint: Float = 0f,
    /** 0..2 */
    val saturation: Float = 1f,
    /** −1..1; changes muted colours more than already-saturated ones. */
    val vibrance: Float = 0f,
    /** Hue in degrees and amount 0..1 tinting the shadows. */
    val shadowHue: Float = 210f,
    val shadowTint: Float = 0f,
    val highlightHue: Float = 40f,
    val highlightTint: Float = 0f,
    val mono: Boolean = false,
    /**
     * Colour mixer, one value per [Looks.MIX_BANDS] band: hue shift in degrees (−30..30, positive
     * turns red towards yellow, yellow towards green and so on), saturation −1..1 (−1 = grey) and
     * lightness −1..1. Colours between two bands get a blend of both.
     */
    val mixHue: List<Float> = NO_MIX,
    val mixSat: List<Float> = NO_MIX,
    val mixLum: List<Float> = NO_MIX,
) {
    val hasMix get() = mixHue.any { it != 0f } || mixSat.any { it != 0f } || mixLum.any { it != 0f }

    fun toJson(): JSONObject = JSONObject().apply {
        put("exposure", exposure.toDouble()); put("contrast", contrast.toDouble())
        put("highlights", highlights.toDouble()); put("shadows", shadows.toDouble())
        put("fade", fade.toDouble()); put("temperature", temperature.toDouble()); put("tint", tint.toDouble())
        put("saturation", saturation.toDouble()); put("vibrance", vibrance.toDouble())
        put("shadowHue", shadowHue.toDouble()); put("shadowTint", shadowTint.toDouble())
        put("highlightHue", highlightHue.toDouble()); put("highlightTint", highlightTint.toDouble())
        put("mono", mono)
        if (hasMix) {
            put("mixHue", JSONArray(mixHue.map { it.toDouble() }))
            put("mixSat", JSONArray(mixSat.map { it.toDouble() }))
            put("mixLum", JSONArray(mixLum.map { it.toDouble() }))
        }
    }

    companion object {
        val NO_MIX: List<Float> = List(8) { 0f }

        private fun bands(j: JSONObject, key: String): List<Float> {
            val a = j.optJSONArray(key) ?: return NO_MIX
            return List(NO_MIX.size) { i -> a.optDouble(i, 0.0).toFloat() }
        }

        fun fromJson(j: JSONObject) = LookParams(
            exposure = j.optDouble("exposure", 0.0).toFloat(),
            contrast = j.optDouble("contrast", 0.0).toFloat(),
            highlights = j.optDouble("highlights", 0.0).toFloat(),
            shadows = j.optDouble("shadows", 0.0).toFloat(),
            fade = j.optDouble("fade", 0.0).toFloat(),
            temperature = j.optDouble("temperature", 0.0).toFloat(),
            tint = j.optDouble("tint", 0.0).toFloat(),
            saturation = j.optDouble("saturation", 1.0).toFloat(),
            vibrance = j.optDouble("vibrance", 0.0).toFloat(),
            shadowHue = j.optDouble("shadowHue", 210.0).toFloat(),
            shadowTint = j.optDouble("shadowTint", 0.0).toFloat(),
            highlightHue = j.optDouble("highlightHue", 40.0).toFloat(),
            highlightTint = j.optDouble("highlightTint", 0.0).toFloat(),
            mono = j.optBoolean("mono", false),
            mixHue = bands(j, "mixHue"),
            mixSat = bands(j, "mixSat"),
            mixLum = bands(j, "mixLum"),
        )
    }
}

/** A named look; user looks live as `<name>.look.json` in the app's luts folder. */
data class Look(val name: String, val params: LookParams, val file: File? = null) {

    fun toLut(profile: LogProfile, size: Int = 33): CubeLut = Looks.bake(name, params, profile, size)

    fun save(dir: File): File {
        val f = File(dir, "${safeName(name)}$SUFFIX")
        f.writeText(JSONObject().put("name", name).put("params", params.toJson()).toString(2))
        return f
    }

    companion object {
        const val SUFFIX = ".look.json"

        fun load(f: File): Look {
            val j = JSONObject(f.readText())
            return Look(j.optString("name", f.name.removeSuffix(SUFFIX)), LookParams.fromJson(j.getJSONObject("params")), f)
        }

        fun safeName(name: String) = name.trim().replace(Regex("[^A-Za-z0-9 _.-]"), "_").ifEmpty { "Look" }
    }
}

object Looks {

    val REC709 = Look("Rec.709", LookParams())

    /** Colour mixer bands: name and hue centre in degrees (red 0, yellow 60, green 120, …, magenta 300). */
    val MIX_BANDS = listOf("Red" to 0f, "Orange" to 30f, "Yellow" to 60f, "Green" to 120f,
        "Aqua" to 180f, "Blue" to 240f, "Purple" to 270f, "Magenta" to 300f)

    /**
     * Built-in looks. Loosely inspired by classic film stocks and print processes, but these are
     * our own parameter sets, not reproductions of any product.
     */
    val presets = listOf(
        REC709,
        Look("Warm Portrait", LookParams(contrast = -0.15f, highlights = -0.35f, shadows = 0.15f, fade = 0.2f,
            temperature = 0.12f, saturation = 0.92f, vibrance = 0.15f,
            shadowHue = 170f, shadowTint = 0.15f, highlightHue = 40f, highlightTint = 0.25f)),
        Look("Slide", LookParams(contrast = 0.35f, highlights = -0.15f, shadows = -0.2f, temperature = -0.08f,
            saturation = 1.2f, vibrance = 0.25f, shadowHue = 225f, shadowTint = 0.25f)),
        Look("Tungsten Night", LookParams(contrast = 0.15f, temperature = -0.25f, saturation = 1.1f,
            shadowHue = 200f, shadowTint = 0.2f, highlightHue = 15f, highlightTint = 0.2f)),
        Look("Teal & Orange", LookParams(contrast = 0.2f, saturation = 1.05f,
            shadowHue = 190f, shadowTint = 0.45f, highlightHue = 30f, highlightTint = 0.35f)),
        Look("Bleach Bypass", LookParams(contrast = 0.5f, highlights = 0.1f, saturation = 0.45f)),
        Look("Faded Matte", LookParams(contrast = -0.25f, fade = 0.45f, saturation = 0.85f,
            shadowHue = 210f, shadowTint = 0.1f, highlightHue = 45f, highlightTint = 0.15f)),
        Look("Mono 400", LookParams(contrast = 0.35f, shadows = -0.1f, fade = 0.05f, mono = true)),
        // Moody automotive grade (the owner's reference: a black E38 by a rapeseed field at dusk):
        // lifted blue-black shadows, faintly mint greys, greens muted by about half while yellows
        // get stronger, warm capped highlights.
        Look("Autobahn", LookParams(exposure = -0.1f, contrast = 0.15f, highlights = -0.3f, shadows = -0.05f, fade = 0.42f,
            temperature = 0.02f, tint = -0.05f, saturation = 1f, vibrance = -0.45f,
            shadowHue = 212f, shadowTint = 0.3f, highlightHue = 52f, highlightTint = 0.3f,
            mixHue = listOf(0f, 2f, -3f, 4f, 0f, 0f, 0f, 0f),
            mixSat = listOf(0f, 0f, 0.35f, -0.55f, -0.3f, -0.15f, -0.15f, -0.15f),
            mixLum = listOf(0f, 0.05f, 0.08f, -0.2f, 0f, -0.05f, 0f, 0f))),
    )

    fun bake(name: String, p: LookParams, profile: LogProfile, size: Int = 33): CubeLut {
        val toRec709 = ColorMath.convert(profile.primaries, Primaries.REC709)
        val out = FloatArray(size * size * size * 3)
        var i = 0
        val lin = DoubleArray(3)
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            lin[0] = profile.decode(r.toDouble() / (size - 1))
            lin[1] = profile.decode(g.toDouble() / (size - 1))
            lin[2] = profile.decode(b.toDouble() / (size - 1))
            val rgb = render(ColorMath.apply(toRec709, lin), p)
            for (c in 0..2) out[i++] = rgb[c].toFloat()
        }
        return CubeLut("$name (${profile.label})", size, out)
    }

    /** Scene-linear Rec.709 → display-referred sRGB 0..1. */
    fun render(lin: DoubleArray, p: LookParams): DoubleArray {
        val gain = 2.0.pow(p.exposure.toDouble())
        // White balance as simple channel gains in linear light.
        val r = lin[0] * gain * (1 + 0.3 * p.temperature)
        val g = lin[1] * gain * (1 - 0.25 * p.tint)
        val b = lin[2] * gain * (1 - 0.3 * p.temperature)

        // Filmic shoulder + display encoding, then the tonal sliders per channel (like film dyes).
        val rgb = doubleArrayOf(r, g, b).map { x ->
            var y = srgbEncode(filmic(x))
            val c = p.contrast.toDouble()
            y = if (c >= 0) y + (smooth(y) - y) * c else 0.5 + (y - 0.5) * (1 + c)
            y += p.highlights * 0.3 * smoothstep(0.45, 1.0, y) * (if (p.highlights > 0) 1 - y else y)
            y += p.shadows * 0.3 * (1 - smoothstep(0.0, 0.55, y)) * (if (p.shadows > 0) 1 - y else y)
            y.coerceIn(0.0, 1.0)
        }.toDoubleArray()

        // Colour.
        val toneLuma = luma(rgb)
        if (p.mono) {
            rgb.fill(toneLuma)
        } else {
            val sat = rgb.max() - rgb.min()
            val amount = p.saturation * (1 + p.vibrance * (1 - sat.coerceIn(0.0, 1.0)))
            for (c in 0..2) rgb[c] = toneLuma + (rgb[c] - toneLuma) * amount
            if (p.hasMix) mix(rgb, p)
        }

        // Split toning: push shadows and highlights towards a hue without changing brightness.
        val luma = luma(rgb)
        val shadowW = 1 - smoothstep(0.0, 0.55, luma)
        val highW = smoothstep(0.45, 1.0, luma)
        val sTone = hueOffset(p.shadowHue)
        val hTone = hueOffset(p.highlightHue)
        for (c in 0..2) {
            rgb[c] += p.shadowTint * 0.15 * shadowW * sTone[c] + p.highlightTint * 0.15 * highW * hTone[c]
        }

        // Fade: lifted blacks, slightly dulled whites.
        val f = p.fade.toDouble()
        for (c in 0..2) rgb[c] = (0.1 * f + rgb[c] * (1 - 0.15 * f)).coerceIn(0.0, 1.0)
        return rgb
    }

    /**
     * Colour mixer on display-referred RGB: hue rotation around the grey axis, saturation and
     * lightness per hue band, blended linearly between neighbouring band centres. Luma is restored
     * after the rotation and scaling, so only the lightness slider changes brightness, and only for
     * colourful pixels (greys have no hue to belong to a band).
     */
    private fun mix(rgb: DoubleArray, p: LookParams) {
        val chroma = rgb.max() - rgb.min()
        if (chroma < 1e-4) return
        val hue = (Math.toDegrees(atan2(sqrt(3.0) * (rgb[1] - rgb[2]), 2 * rgb[0] - rgb[1] - rgb[2])) + 360) % 360
        val j = MIX_BANDS.indices.last { MIX_BANDS[it].second <= hue }
        val start = MIX_BANDS[j].second
        val end = if (j == MIX_BANDS.lastIndex) 360f else MIX_BANDS[j + 1].second
        val f = (hue - start) / (end - start)
        val k = (j + 1) % MIX_BANDS.size
        fun band(values: List<Float>) = values[j] * (1 - f) + values[k] * f
        val angle = Math.toRadians(band(p.mixHue))
        val sat = 1 + band(p.mixSat)
        val lum = band(p.mixLum)

        val l0 = luma(rgb)
        val grey = (rgb[0] + rgb[1] + rgb[2]) / 3
        val c = DoubleArray(3) { rgb[it] - grey }
        // Rodrigues rotation around (1,1,1)/√3; c is perpendicular to it, so two terms suffice.
        val s3 = 1 / sqrt(3.0)
        val kxc = doubleArrayOf(s3 * (c[2] - c[1]), s3 * (c[0] - c[2]), s3 * (c[1] - c[0]))
        val ca = cos(angle)
        val sa = sin(angle)
        for (i in 0..2) rgb[i] = grey + sat * (c[i] * ca + kxc[i] * sa)
        val dl = luma(rgb) - l0
        val gain = 2.0.pow(lum * 0.6 * smoothstep(0.0, 0.25, chroma))
        for (i in 0..2) rgb[i] = (rgb[i] - dl) * gain
    }

    /** Fully saturated colour at [hue] minus its own luma: a brightness-neutral tint direction. */
    private fun hueOffset(hue: Float): DoubleArray {
        val h = ((hue % 360 + 360) % 360) / 60.0
        val x = 1 - abs(h % 2 - 1)
        val c = when (h.toInt()) {
            0 -> doubleArrayOf(1.0, x, 0.0)
            1 -> doubleArrayOf(x, 1.0, 0.0)
            2 -> doubleArrayOf(0.0, 1.0, x)
            3 -> doubleArrayOf(0.0, x, 1.0)
            4 -> doubleArrayOf(x, 0.0, 1.0)
            else -> doubleArrayOf(1.0, 0.0, x)
        }
        val l = luma(c)
        return DoubleArray(3) { c[it] - l }
    }

    private fun luma(c: DoubleArray) = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]

    private fun smooth(y: Double) = y * y * (3 - 2 * y)

    private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /** Narkowicz's ACES filmic fit; middle grey 0.18 lands around sRGB 0.42. */
    private fun filmic(x: Double): Double {
        val v = (x * 0.6).coerceAtLeast(0.0)
        return ((v * (2.51 * v + 0.03)) / (v * (2.43 * v + 0.59) + 0.14)).coerceIn(0.0, 1.0)
    }

    private fun srgbEncode(x: Double) = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055
}
