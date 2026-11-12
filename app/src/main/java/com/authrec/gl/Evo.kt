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

    /** A look's tone curve ([toneOfLook]): the brightness shown (0..1) per quarter stop of scene brightness. */
    class Tone(private val display: FloatArray) {
        /** The brightest the look gives. */
        val top: Float = display.max()
        /** The scene brightness (middle grey 0.18) from which a surface shows as white (97 % of [top]). */
        val white: Float = (display.indexOfFirst { it >= 0.97f * top }.takeIf { it >= 0 } ?: display.lastIndex)
            .let { 0.18f * Math.pow(2.0, (TONE_LOW + it * BIN).toDouble()).toFloat() }

        /** Brightness shown [e] stops from middle grey. */
        fun at(e: Float): Float {
            val x = ((e - TONE_LOW) / BIN).coerceIn(0f, display.size - 1.001f)
            val i = x.toInt()
            return display[i] + (display[i + 1] - display[i]) * (x - i)
        }

        /** How blown a surface [e] stops from middle grey looks: 0 with texture left, 1 flat white. */
        fun blown(e: Float): Float = ramp(at(e) / top, BLOWN_FROM, BLOWN_FULL)
    }

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

    /**
     * What a share of the picture sunk into the dark weighs against a blown one ([viewCost]): the
     * guard against holding a big white surface at the whole picture's expense. 0 = no guard
     * (adb evodim, for comparing).
     */
    @Volatile var dimWeight = DIM_WEIGHT

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
    private var wished = false
    private var viewTarget = 0f
    private var viewHeld = 0f
    private var viewSpeed = 0f
    private var sensorTarget = 0f
    private var sinceStep = 0f

    /**
     * One frame. [gain]: the digital gain the picture has without EVO's [viewEv] and the user's own
     * EV (linear; value × gain is scene brightness with middle grey at 0.18);
     * [tone]: how the look shows each scene brightness ([toneOfLook]); [recordedWhite]: the scene
     * brightness that comes out white in what a clip keeps (the tone's
     * [Tone.white] with the look baked in, [LOG_WHITE] for a log clip: its file holds everything
     * the sensor does); [noiseFloor]: the
     * sensor's read noise as a normalised value (0: unknown); [sensorActualEv]: EVO's exposure
     * shift as the frames really have it (measured: requests take a few frames, and a camera
     * metering a clipped scene moves further than asked); [sensorSettled]: the last request has
     * had its effect (or isn't going to); [step]: the phone's AE
     * compensation step, [sensorMin]..[sensorMax] what's left of its range for EVO;
     * [allowPush]: brightening is on the table (a scene with light to spare, not a dim room).
     */
    fun update(m: Measure, gain: Float, tone: Tone, recordedWhite: Float, noiseFloor: Float, sensorActualEv: Float,
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
            // The wish stays where it is until another shift is clearly better: a sky at the edge of
            // what can be held flipped between −2 and −0.3 as the light drifted (X14, 2026-11-12).
            val best = bestView(gain, tone, allowPush)
            if (!wished || viewCost(gain, tone, viewWanted) - viewCost(gain, tone, best) > STICK) viewWanted = best
            wished = true
            sensorWanted = (sensorActualEv + bestSensor(noiseFloor, sensorActualEv, max(viewWanted, 0f), sensorMin, sensorMax,
                log2(gain / recordedWhite) + viewWanted))
                .coerceIn(max(-MAX_DOWN, sensorMin), min(MAX_UP, sensorMax))
            blownNow = blown(gain, tone, viewEv)
            blownWithout = blown(gain, tone, 0f)
        } else {
            started = false
            wished = false
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

    /** The averaged measure as text (view | sensor | clipped | sources), for tuning on the desktop (adb evodump). */
    fun dump(): String = avg.view.joinToString(",") + "|" + avg.sensor.joinToString(",") + "|" + avg.clipped.joinToString(",") + "|" + avg.sources

    /** The view's cost at each candidate shift, from −[MAX_DOWN] in steps of [GRID] (after an [update]). */
    fun viewCosts(): FloatArray = costs.copyOf()

    /** Share of the picture shown blown out with the view shifted by [shift] stops. */
    private fun blown(gain: Float, tone: Tone, shift: Float): Float {
        val toGrey = log2(gain / 0.18f) + shift
        var sum = 0f
        for (i in 0 until BINS) {
            if (avg.view[i] > 0f) sum += avg.view[i] * tone.blown(level(i) + toGrey)
            if (avg.clipped[i] > 0f) sum += avg.clipped[i] * clippedBlown(tone, level(i) + toGrey)
        }
        return sum
    }

    /** A clipped surface's share still blown when its clip point sits [e] stops from middle grey. */
    private fun clippedBlown(tone: Tone, e: Float): Float {
        var sum = 0f
        for (k in 0 until 3) sum += tone.blown(e + SAT_SPREAD * (k + 0.5f) / 3f)
        return sum / 3f
    }

    /**
     * What showing the picture [b] stops off the meter costs: the share blown out, the share sunk
     * into the dark ([DIM_WEIGHT]: the guard against holding a big white surface at the whole
     * picture's expense) or gone to black, and the price of the move. All three shares are read
     * off the look's own tone curve: a bright LUT can come down a long way before its midtones
     * sink, a plain one can't.
     */
    private fun viewCost(gain: Float, tone: Tone, b: Float): Float {
        val toGrey = log2(gain / 0.18f) + b
        var dark = 0f
        for (i in 0 until BINS) if (avg.view[i] > 0f) {
            val d = tone.at(level(i) + toGrey)
            dark += avg.view[i] * (dimWeight * (1f - ramp(d, DIM_BLACK, DIM_GREY)) + BLACK_WEIGHT * (1f - ramp(d, BLACK_FULL, BLACK_FROM)))
        }
        val st = strength.coerceIn(0, 2)
        return blown(gain, tone, b) + dark + if (b < 0f) -b * PRICE_DOWN[st] + b * b * PRICE_DOWN_FAR[st] else b * PRICE_UP + b * b * PRICE_UP_FAR
    }

    private fun bestView(gain: Float, tone: Tone, allowPush: Boolean): Float {
        val count = Math.round((MAX_DOWN + if (allowPush) MAX_UP else 0f) / GRID) + 1
        var best = -1
        for (k in 0 until count) {
            val b = -MAX_DOWN + k * GRID
            costs[k] = viewCost(gain, tone, b)
            // Among near-equals, the smaller move.
            if (best < 0 || costs[k] < costs[best] - 1e-4f ||
                (costs[k] < costs[best] + 1e-4f && abs(b) < abs(-MAX_DOWN + best * GRID))) best = k
        }
        for (k in count until costs.size) costs[k] = Float.NaN
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
        /**
         * The dark side of the cost, in the brightness the look shows (0..1): sunk between
         * [DIM_GREY] and [DIM_BLACK] (a plain look: ~2 to ~3.5 stops under middle grey), black
         * below [BLACK_FROM]. An overcast sky filling a quarter of the frame, through a LUT with 2
         * stops of highlight room and a bloom on top, took the garden under it down 1.9 stops
         * while the sky stayed white (owner's scene, 2026-11-12): nothing was crushed to black,
         * so nothing held it back.
         */
        private const val DIM_GREY = 0.16f
        private const val DIM_BLACK = 0.06f
        const val DIM_WEIGHT = 0.2f
        private const val BLACK_FROM = 0.03f
        private const val BLACK_FULL = 0.005f
        private const val BLACK_WEIGHT = 0.3f
        /** A share counts as blown from this much of the brightest the look gives, fully from the second. */
        private const val BLOWN_FROM = 0.93f
        private const val BLOWN_FULL = 0.985f
        /** Another shift has to be this much cheaper before the wish moves to it. */
        private const val STICK = 0.015f
        /** The tone curve's range in stops from middle grey. */
        private const val TONE_LOW = -12f
        private const val TONE_HIGH = 10f
        /** A share under the sensor's noise weighs this much of a clipped one. */
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
         * How bright the view shows a large grey surface at each scene brightness: [lut] fed our
         * [profile]'s log (converted to [lutInput]'s where the LUT expects another; null [lut]:
         * the plain log view), mixed in at [strength], after eDR's curve ([edrHighlights] /
         * [edrShadows] −1..1, 0 = off; PipelineShaders `balanceTone`), and with what Dehaze
         * ([dehaze] −1..1, 0 where the dials don't apply) does to an even surface: its bloom
         * takes bright ones the rest of the way to white, its other side lifts a veil
         * (PipelineShaders `photoFinish`; Texture and Clarity leave an even surface alone).
         * Looks differ by stops (a plain Rec.709 look goes white ~4.5 stops over middle grey and
         * shows middle grey at 41 %; a punchy LUT at 2 and 50 %), and assuming one curve for all
         * read 1 % blown on a picture that looked half white (owner's CineStill preset,
         * 2026-11-12); without the bloom EVO took a sky down 2 stops that stayed white all the
         * same (owner: "take into account LUTs and settings … dehaze, clarity").
         */
        fun toneOfLook(lut: CubeLut?, profile: LogProfile, lutInput: LogProfile?, strength: Float,
                       edrHighlights: Float, edrShadows: Float, dehaze: Float): Tone {
            val steps = Math.round((TONE_HIGH - TONE_LOW) / BIN)
            val out = FloatArray(steps + 1)
            for (k in 0..steps) {
                val e = (TONE_LOW + k * BIN).toDouble()
                fun softplus(x: Double) = 0.5 * ln(1.0 + exp(x / 0.5))
                val shaped = e + 0.6 * edrHighlights * softplus(e - 1.0) + 2.5 * edrShadows * Math.tanh(softplus(-1.0 - e) / 5.0)
                val y = 0.18 * Math.pow(2.0, shaped)
                val log = profile.encode(y).coerceIn(0.0, 1.0).toFloat()
                var d = log
                if (lut != null) {
                    // Neutral in, so the LUT is read along its grey diagonal.
                    val n = lut.size
                    val pos = ((lutInput ?: profile).encode(y).coerceIn(0.0, 1.0) * (n - 1)).toFloat()
                    val i = pos.toInt().coerceIn(0, max(n - 2, 0))
                    val f = pos - i
                    fun luma(node: Int): Float {
                        val at = (node + node * n + node * n * n) * 3
                        return 0.2126f * lut.rgb[at] + 0.7152f * lut.rgb[at + 1] + 0.0722f * lut.rgb[at + 2]
                    }
                    val looked = if (n < 2) luma(0) else luma(i) * (1f - f) + luma(i + 1) * f
                    d = log * (1f - strength) + looked * strength
                }
                d = d.coerceIn(0f, 1f)
                if (dehaze > 0f) {
                    val veil = 0.45f * dehaze * d
                    d = (d - veil) / (1f - veil)
                } else if (dehaze < 0f) {
                    val amount = -dehaze
                    d += (0.05f + 0.7f * d - d) * 0.12f * amount
                    val x = 2.5f * amount * d * ramp(d, 0.35f, 0.9f)
                    val over = (x - 0.65f).coerceIn(0f, 0.7f)
                    d = 1f - (1f - d.coerceIn(0f, 1f)) * (1f - min(x - over * over / 1.4f, 1f))
                }
                out[k] = d.coerceIn(0f, 1f)
            }
            return Tone(out)
        }

        /** The sensor's usable range in stops at this noise floor (clip point over read noise). */
        fun rangeStops(noiseFloor: Float) = log2(CLIP / (if (noiseFloor > 0f) noiseFloor else DEFAULT_NOISE))
    }
}
