package com.authrec.gl

import com.authrec.color.CubeLut
import com.authrec.color.LogProfile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Exposure Value Optimizer (owner's idea, 2026-11-12: "relative to what light areas are blown out
 * it optimizes the exposure accordingly … it removes the effort"): watches each frame's RAW and
 * moves the exposure so bright surfaces aren't blown out, without chasing what can't be saved
 * (lamps, neon, headlights at night) and without burying the rest of the picture in black or noise.
 *
 * Two shifts, chosen separately:
 * - [viewEv]: how much darker (or, rarely, brighter) the picture is shown: digital gain, changed a
 *   little every frame, so it never steps. The X14's main camera shows white about a stop before
 *   its sensor clips, so most protection is this and leaves shutter and ISO alone.
 * - [sensorEv]: real exposure (AE compensation, in the phone's steps), for when the sensor itself
 *   clips a surface. The renderer evens the picture out against each frame's actual exposure, so a
 *   step shows as a little more noise, not as a brightness step.
 *
 * Each is the cheapest of a row of candidate shifts, by one sum: the share of the picture blown
 * out + the share pushed into black (or under the sensor's noise) + a price for moving, small at
 * first and growing with every stop.
 * So a blown plate or sky is worth a stop or two, a few specular highlights aren't, and a dark
 * scene with lights stays where the meter put it. Clipped samples whose surroundings are dark are
 * light sources and don't count; clipped surfaces are taken to be up to [SAT_SPREAD] stops over
 * (nobody knows until the exposure comes down and shows them).
 *
 * No Android in here: `tools/evosim/run.sh` runs this class on the desktop against made-up scenes.
 */
class Evo {

    /** What one frame shows, from a grid of green RAW samples (see [fill]). */
    class Measure {
        /** Shares of the samples by level (log2 of the lens-shading-corrected value, clip = 1), unclipped ones. */
        val view = FloatArray(BINS)
        /** The same samples by their level on the sensor (no shading gain): what clips and what's in the noise. */
        val sensor = FloatArray(BINS)
        /** Clipped samples of bright surfaces, by the level their clip point shows at. */
        val clipped = FloatArray(BINS)
        /** Share of clipped samples that are light sources (dark around them): not counted. */
        var sources = 0f
        /** Share of clipped surface samples (the sum of [clipped]). */
        var surfaces = 0f

        /**
         * [x]: green samples on a [cols]×[rows] grid, normalised (black 0, white level 1);
         * [shade]: the lens shading gain at each (1 where unknown).
         */
        fun fill(x: FloatArray, shade: FloatArray, cols: Int, rows: Int) {
            view.fill(0f)
            sensor.fill(0f)
            clipped.fill(0f)
            val n = cols * rows
            val one = 1f / n
            var src = 0
            var surf = 0
            for (j in 0 until rows) for (i in 0 until cols) {
                val k = j * cols + i
                val v = x[k]
                if (v < CLIP) {
                    view[bin(v * shade[k])] += one
                    sensor[bin(v)] += one
                    continue
                }
                // A surface if most of what's around it is bright too; a lamp or a neon tube has dark around it.
                var bright = 0
                var around = 0
                for (dj in -1..1) for (di in -1..1) {
                    if (di == 0 && dj == 0) continue
                    val ii = i + di
                    val jj = j + dj
                    if (ii < 0 || jj < 0 || ii >= cols || jj >= rows) continue
                    around++
                    if (x[jj * cols + ii] > NEAR_CLIP) bright++
                }
                if (bright * 8 >= around * 5) {
                    clipped[bin(CLIP * shade[k])] += one
                    surf++
                } else src++
            }
            sources = src * one
            surfaces = surf * one
        }

        fun blendInto(avg: Measure, a: Float) {
            for (i in 0 until BINS) {
                avg.view[i] += (view[i] - avg.view[i]) * a
                avg.sensor[i] += (sensor[i] - avg.sensor[i]) * a
                avg.clipped[i] += (clipped[i] - avg.clipped[i]) * a
            }
            avg.sources += (sources - avg.sources) * a
            avg.surfaces += (surfaces - avg.surfaces) * a
        }
    }

    enum class Mode {
        /** Following the scene. */
        RUN,
        /** Exposure locked: stays where it is. */
        HOLD,
        /** Off (or exposure by hand): eases back to nothing. */
        OFF,
    }

    /**
     * How readily the picture is darkened: 0 gentle, 1 balanced, 2 strong (Settings → EVO). The
     * owner, on a bright room through a LUT that clips early: balanced's −0.8…−1.2 "a bit weak",
     * −2 "more accurate", −1.2 to −1.8 the best: balanced is tuned to land there.
     */
    @Volatile var strength = 1

    /** Stops the picture is shown darker (−) or brighter (+) than the meter's, eased every frame. */
    var viewEv = 0f
        private set
    /** Stops of real exposure asked of the camera, in whole steps of the phone's AE compensation. */
    var sensorEv = 0f
        private set
    /** What the last frame alone would want (for the log and the simulation). */
    var viewWanted = 0f
        private set
    var sensorWanted = 0f
        private set
    /** Share of the picture blown out as shown now, and as it would be without EVO. */
    var blownNow = 0f
        private set
    var blownWithout = 0f
        private set

    private val avg = Measure()
    private var started = false
    private var viewTarget = 0f
    private var viewHeld = 0f
    private var viewSpeed = 0f
    private var sensorTarget = 0f
    private var sinceStep = 0f

    /**
     * One frame. [gain]: the digital gain the picture has without EVO's [viewEv] and the user's own
     * EV (linear; value × gain is scene brightness with middle grey at 0.18); [white]: the scene
     * brightness the view shows as white ([whiteOfLook]); [recordedWhite]: the
     * same for what a clip keeps ([white] with the look baked in, [LOG_WHITE] for a log clip: its
     * file holds everything the sensor does); [noiseFloor]: the
     * sensor's read noise as a normalised value (0: unknown); [sensorActualEv]: EVO's exposure
     * shift as the frames really have it (measured: requests take a few frames, and a camera
     * metering a clipped scene moves further than asked); [sensorSettled]: the last request has
     * had its effect (or isn't going to); [step]: the phone's AE
     * compensation step, [sensorMin]..[sensorMax] what's left of its range for EVO;
     * [allowPush]: brightening is on the table (a scene with light to spare, not a dim room).
     */
    fun update(m: Measure, gain: Float, white: Float, recordedWhite: Float, noiseFloor: Float, sensorActualEv: Float,
               sensorSettled: Boolean, step: Float, sensorMin: Float, sensorMax: Float, allowPush: Boolean, mode: Mode, dt: Float) {
        val t = dt.coerceIn(0.001f, 0.2f)
        if (mode == Mode.HOLD) {
            viewSpeed = 0f
            return
        }
        if (mode == Mode.RUN) {
            // ~0.25 s of frames: one frame's noise and a passing glint don't move anything.
            if (started) m.blendInto(avg, 1f - exp(-t / 0.25f)) else m.blendInto(avg, 1f)
            started = true
            viewWanted = bestView(gain, white, allowPush)
            sensorWanted = (sensorActualEv + bestSensor(noiseFloor, sensorActualEv, max(viewWanted, 0f), sensorMin, sensorMax,
                log2(gain / recordedWhite) + viewWanted))
                .coerceIn(max(-MAX_DOWN, sensorMin), min(MAX_UP, sensorMax))
            blownNow = blown(gain, white, viewEv)
            blownWithout = blown(gain, white, 0f)
        } else {
            started = false
            viewWanted = 0f
            sensorWanted = 0f
        }
        // The wish, calmed (~1 s), then followed like a damped spring: it starts and stops gently,
        // and never faster than VIEW_DOWN / VIEW_UP stops a second (owner: choppy is unusable).
        viewTarget += (viewWanted - viewTarget) * (1f - exp(-t / 0.8f))
        // Held until the calmed wish has moved a tenth of a stop: a steady scene gets a steady picture.
        if (abs(viewTarget - viewHeld) > HOLD_WITHIN || (abs(viewTarget) < 0.02f && viewHeld != 0f)) viewHeld = viewTarget
        val goal = if (abs(viewHeld) < 0.04f) 0f else viewHeld
        viewSpeed += (SPRING * SPRING * (goal - viewEv) - 2f * SPRING * viewSpeed) * t
        viewSpeed = viewSpeed.coerceIn(-VIEW_DOWN, VIEW_UP)
        viewEv += viewSpeed * t
        if (goal == 0f && abs(viewEv) < 0.005f && abs(viewSpeed) < 0.01f) {
            viewEv = 0f
            viewSpeed = 0f
        }
        // Real exposure: one of the phone's steps at a time, once the step before has shown what it
        // does (asking on blind overshot by half a stop where the camera was at its ISO limit), and
        // only for a difference worth a step (no dithering between two). The request is in the
        // camera's terms, the target in what the sensor really does: they needn't be the same number.
        sensorTarget += (sensorWanted - sensorTarget) * (1f - exp(-t / 1.2f))
        sinceStep += t
        val s = step.coerceAtLeast(0.05f)
        val off = sensorTarget - sensorActualEv
        if (sensorSettled && sinceStep >= STEP_EVERY && abs(off) >= s * 0.75f) {
            val lowest = max(-MAX_DOWN, sensorMin)
            val highest = max(min(if (viewWanted > 0f) MAX_UP else 0f, sensorMax), lowest)
            val next = (Math.round((sensorEv + (if (off > 0) s else -s)) / s) * s).coerceIn(lowest, highest)
            if (next != sensorEv) {
                sensorEv = next
                sinceStep = 0f
            }
        }
    }

    /** Real exposure is no longer EVO's to ask for (another AE mode): the request is gone at once. */
    fun dropSensor() {
        sensorEv = 0f
        sensorTarget = 0f
        sensorWanted = 0f
    }

    /** Share of the picture shown blown out with the view shifted by [shift] stops. */
    private fun blown(gain: Float, white: Float, shift: Float): Float {
        val over = log2(gain / white) + shift
        var sum = 0f
        for (i in 0 until BINS) {
            if (avg.view[i] > 0f) sum += avg.view[i] * ramp(level(i) + over, -BLOWN_KNEE, 0f)
            if (avg.clipped[i] > 0f) sum += avg.clipped[i] * clippedBlown(level(i) + over)
        }
        return sum
    }

    /** A clipped surface's share still blown when its clip point shows [over] stops over white. */
    private fun clippedBlown(over: Float): Float {
        var sum = 0f
        for (k in 0 until 3) sum += ramp(over + SAT_SPREAD * (k + 0.5f) / 3f, -BLOWN_KNEE, 0f)
        return sum / 3f
    }

    private fun bestView(gain: Float, white: Float, allowPush: Boolean): Float {
        val toGrey = log2(gain / 0.18f)
        val count = Math.round((MAX_DOWN + if (allowPush) MAX_UP else 0f) / GRID) + 1
        var best = -1
        for (k in 0 until count) {
            val b = -MAX_DOWN + k * GRID
            var crushed = 0f
            for (i in 0 until BINS) if (avg.view[i] > 0f) crushed += avg.view[i] * (1f - ramp(level(i) + toGrey + b, BLACK - 1f, BLACK + 1f))
            costs[k] = blown(gain, white, b) + CRUSH_WEIGHT * crushed +
                if (b < 0f) -b * PRICE_DOWN[strength.coerceIn(0, 2)] + b * b * PRICE_DOWN_FAR[strength.coerceIn(0, 2)]
                else b * PRICE_UP + b * b * PRICE_UP_FAR
            // Among near-equals, the smaller move.
            if (best < 0 || costs[k] < costs[best] - 1e-4f ||
                (costs[k] < costs[best] + 1e-4f && abs(b) < abs(-MAX_DOWN + best * GRID))) best = k
        }
        var b = -MAX_DOWN + best * GRID
        // Between the candidates, where a parabola through the best and its neighbours is lowest:
        // the wish then moves evenly with the scene instead of hopping from one candidate to the next.
        if (best > 0 && best < count - 1 && b != 0f) {
            val bend = costs[best - 1] - 2f * costs[best] + costs[best + 1]
            if (bend > 1e-6f) b += (0.5f * (costs[best - 1] - costs[best + 1]) / bend).coerceIn(-0.5f, 0.5f) * GRID
        }
        return b
    }

    private val costs = FloatArray(Math.round((MAX_DOWN + MAX_UP) / GRID) + 1)

    /**
     * The change of real exposure (from where this frame is) that costs least; [prefer]: where the
     * view would like it (never above it: more exposure for less noise alone would be the camera
     * raising ISO or blurring motion); [over]: stops a level shows over what's recorded as white.
     * A clipped surface only counts where its clip point shows under white: what's white in the
     * clip anyway gains nothing from a darker sensor (a window with the look baked in).
     */
    private fun bestSensor(noiseFloor: Float, actual: Float, prefer: Float, lo: Float, hi: Float, over: Float): Float {
        val floor = log2((if (noiseFloor > 0f) noiseFloor else DEFAULT_NOISE) * 4f)
        var visible = 0f
        for (i in 0 until BINS) if (avg.clipped[i] > 0f) visible += avg.clipped[i] * (1f - ramp(level(i) + over, -BLOWN_KNEE, 0f))
        var best = 0f
        var bestCost = Float.MAX_VALUE
        // Outside what's allowed (the view no longer wants a push, the range shrank): straight back in.
        val bottom = max(-MAX_DOWN, lo)
        val top = max(min(prefer, hi), bottom)
        if (actual > top + 1e-3f) return top - actual
        if (actual < bottom - 1e-3f) return bottom - actual
        val reach = Math.round((MAX_DOWN + MAX_UP) / GRID)
        for (k in -reach..reach) {
            // (A grid around "no change": where the exposure is now is always a candidate.)
            val d = k * GRID
            val total = actual + d
            if (total >= bottom - 1e-3f && total <= top + 1e-3f) {
                var clip = visible * (1f + d / SAT_SPREAD).coerceIn(0f, 1f)
                var noisy = 0f
                for (i in 0 until BINS) {
                    val s = avg.sensor[i]
                    if (s <= 0f) continue
                    val l = level(i) + d
                    if (d > 0f) clip += s * ramp(l, CLIP_LEVEL - 0.25f, CLIP_LEVEL)
                    noisy += s * (1f - ramp(l, floor - 1f, floor + 1f))
                }
                val cost = clip + NOISE_WEIGHT * noisy + SENSOR_PRICE * abs(total - prefer)
                if (cost < bestCost - 1e-4f || (cost < bestCost + 1e-4f && abs(d) < abs(best))) {
                    bestCost = min(cost, bestCost)
                    best = d
                }
            }
        }
        return best
    }

    companion object {
        const val BINS = 64
        /** Bins are quarter stops from 14 stops under the clip point to 2 over (shading gain lifts corners past it). */
        private const val LOW = -14f
        private const val BIN = 0.25f
        /** A sample this close to the white level counts as clipped, and one this bright as "bright around a clipped one". */
        const val CLIP = 0.9f
        private const val NEAR_CLIP = 0.2f
        private val CLIP_LEVEL = log2(CLIP)
        /** Clipped surfaces are taken to lie evenly up to this many stops over the clip point. */
        private const val SAT_SPREAD = 1.5f
        /** Near-white counts as blown from this many stops under white (a plate at white has no texture left). */
        private const val BLOWN_KNEE = 0.5f
        /** Stops under middle grey where the picture is black for most looks (dark foliage a stop above still reads). */
        private const val BLACK = -6.5f
        /** A crushed share weighs this much of a blown one; a share under the sensor's noise likewise. */
        private const val CRUSH_WEIGHT = 0.5f
        private const val NOISE_WEIGHT = 0.5f
        /**
         * What darkening / brightening must save, as a share of the picture: per stop, plus per
         * stop squared, so each further stop has to save more. With a flat price a bright room
         * through a LUT that clips 2 stops over middle grey went the whole 2 stops (X14,
         * 2026-11-12): whites that span more than the look holds can't all be kept, and the
         * picture's brightness is worth something too. How much is taste, hence [strength]
         * (gentle, balanced, strong): that room, 18–24 % of it blown, came out about −0.9, −1.3
         * and −1.7.
         */
        private val PRICE_DOWN = floatArrayOf(0.04f, 0.03f, 0.02f)
        private val PRICE_DOWN_FAR = floatArrayOf(0.05f, 0.025f, 0.005f)
        private const val PRICE_UP = 0.08f
        private const val PRICE_UP_FAR = 0.04f
        private const val SENSOR_PRICE = 0.01f
        /** At most this far from the meter. */
        const val MAX_DOWN = 2f
        const val MAX_UP = 1f
        private const val GRID = 0.125f
        /** The view's fastest (stops a second) and its spring (rad/s: ~2.5 s to settle). */
        private const val VIEW_DOWN = 0.6f
        private const val VIEW_UP = 0.35f
        private const val SPRING = 2f
        private const val STEP_EVERY = 0.45f
        private const val HOLD_WITHIN = 0.1f
        /** "White" of a log clip: far above anything a sensor holds, so nothing in it counts as blown by the curve. */
        const val LOG_WHITE = 64f
        /** Read noise assumed where the phone reports none: 11 stops under the clip point. */
        private const val DEFAULT_NOISE = 1f / 2048f

        private fun log2(v: Float) = (ln(v.toDouble()) / ln(2.0)).toFloat()
        private fun bin(v: Float): Int = if (v <= 0f) 0 else floor((log2(v) - LOW) / BIN).toInt().coerceIn(0, BINS - 1)
        private fun level(i: Int) = LOW + (i + 0.5f) * BIN
        /** 0 at [a] and below, 1 at [b] and above, smooth between. */
        private fun ramp(x: Float, a: Float, b: Float): Float {
            val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /**
         * The scene brightness (middle grey 0.18) at which a grey surface comes out white through
         * the look: [lut] fed our [profile]'s log (converted to [lutInput]'s where the LUT expects
         * another), mixed in at [strength], after eDR's highlight curve ([edrHighlights] −1..1, 0 =
         * off; PipelineShaders `balanceTone`). "White" is where the look has given 97 % of the
         * brightest it gives: looks differ by a stop and more (a plain Rec.709 look goes white
         * ~3.5 stops over middle grey; a punchy LUT well before), and assuming one point for all
         * read 1 % blown on a picture that looked half white (owner's CineStill preset, 2026-11-12).
         * The dials' bloom isn't counted: it's a glow put on top on purpose.
         */
        fun whiteOfLook(lut: CubeLut, profile: LogProfile, lutInput: LogProfile?, strength: Float, edrHighlights: Float): Float {
            val n = lut.size
            val steps = 9 * 8  // middle grey to 9 stops over, in eighths
            val out = FloatArray(steps + 1)
            var top = 0f
            for (k in 0..steps) {
                val e = k / 8.0
                val tone = 0.6 * edrHighlights * 0.5 * ln(1.0 + exp((e - 1.0) / 0.5))  // softplus(e − 1, 0.5)
                val y = 0.18 * Math.pow(2.0, e + tone)
                val log = profile.encode(y).coerceIn(0.0, 1.0)
                // Neutral in, so the LUT is read along its grey diagonal.
                val pos = ((lutInput ?: profile).encode(y).coerceIn(0.0, 1.0) * (n - 1)).toFloat()
                val i = pos.toInt().coerceIn(0, max(n - 2, 0))
                val f = pos - i
                fun luma(node: Int): Float {
                    val at = (node + node * n + node * n * n) * 3
                    return 0.2126f * lut.rgb[at] + 0.7152f * lut.rgb[at + 1] + 0.0722f * lut.rgb[at + 2]
                }
                val looked = if (n < 2) luma(0) else luma(i) * (1f - f) + luma(i + 1) * f
                out[k] = (log * (1f - strength) + looked * strength).toFloat()
                top = max(top, out[k])
            }
            for (k in 0..steps) if (out[k] >= 0.97f * top) return 0.18f * Math.pow(2.0, k / 8.0).toFloat()
            return LOG_WHITE
        }

        /** The sensor's usable range in stops at this noise floor (clip point over read noise). */
        fun rangeStops(noiseFloor: Float) = log2(CLIP / (if (noiseFloor > 0f) noiseFloor else DEFAULT_NOISE))
    }
}
