package com.authrec

import android.os.Handler
import android.os.SystemClock
import com.authrec.gl.Renderer
import kotlin.math.sqrt

/**
 * Contrast-detect autofocus for lenses whose ISP autofocus doesn't run for third-party apps
 * (Xiaomi's hidden telephoto). Sweeps the lens coarsely across its range, then finely around the
 * sharpest position, and fits a parabola through the best three points.
 *
 * Timing matters more than anything else here: the camera *reports* a new focus distance at
 * once, but the lens physically takes ~4–5 frames to get there, and a measurement taken early is
 * the previous position's sharpness. So the coarse pass moves fast (it only has to find the
 * neighbourhood, and its fine window is wide enough to absorb the lag) and the fine pass waits
 * [FINE_SETTLE_FRAMES] frames per position, only counting frames exposed after the move.
 *
 * In continuous mode it keeps watching the same region after locking and refocuses when
 * sharpness drops and stays down (subject or framing changed): a short local search first,
 * a full sweep only if the peak has moved outside it.
 *
 * Positions are spaced evenly in sqrt(diopters), which puts more of them near infinity.
 */
class SoftwareAf(
    private val handler: Handler,
    private val renderer: Renderer,
    private val minFocusDiopters: Float,
    /** Now, on the clock the camera's frame timestamps use. */
    private val sensorClockNs: () -> Long,
    private val moveLens: (diopters: Float) -> Unit,
    private val onLocked: (diopters: Float) -> Unit,
) {
    private enum class Phase { IDLE, COARSE, FINE, LOCAL, MONITOR }

    private var phase = Phase.IDLE
    private var continuous = false
    private var plan: List<Float> = emptyList()
    private val results = mutableListOf<Pair<Float, Float>>()
    private var step = 0
    private var minFrame = 0L
    private var stepStarted = 0L
    private var locked = 0f
    private var baseline = 0f
    private var lowFrames = 0
    private var lastFrameSeen = 0L
    /** Frames exposed before this (sensor clock) predate the current move and are ignored. */
    private var movedAtNs = 0L

    /** [region]: left, top, right, bottom in 0..1 image coordinates. */
    fun start(region: FloatArray, continuous: Boolean) {
        cancel()
        this.continuous = continuous
        renderer.sharpnessRegion = region
        sweep(Phase.COARSE, List(COARSE_STEPS) { i -> toDiopters(i / (COARSE_STEPS - 1f)) })
    }

    fun cancel() {
        phase = Phase.IDLE
        handler.removeCallbacks(tick)
        renderer.sharpnessRegion = null
    }

    val isSearching get() = phase == Phase.COARSE || phase == Phase.FINE || phase == Phase.LOCAL

    private fun sweep(p: Phase, positions: List<Float>) {
        phase = p
        // Warm-up: travel to the first position and discard that reading. A pass can start far
        // from where the lens is (e.g. after manual focus), and the first frames would otherwise
        // carry the previous position's sharpness into the curve.
        plan = listOf(positions.first()) + positions
        results.clear()
        step = 0
        moveTo(plan[0])
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun moveTo(d: Float) {
        movedAtNs = sensorClockNs()
        moveLens(d)
        val settle = if (phase == Phase.COARSE) COARSE_SETTLE_FRAMES else FINE_SETTLE_FRAMES
        // The warm-up move can be a long way: allow double.
        minFrame = renderer.frameCounter + if (step == 0) settle * 2 else settle
        stepStarted = SystemClock.elapsedRealtime()
    }

    /** The newest measurement comes from a frame exposed after the move, once the lens had time to arrive. */
    private fun settledFrame() = renderer.frameCounter >= minFrame && renderer.sharpnessTimestamp > movedAtNs

    private val tick: Runnable = object : Runnable {
        override fun run() {
            when (phase) {
                Phase.IDLE -> return
                Phase.MONITOR -> monitor()
                else -> searchStep()
            }
            if (phase != Phase.IDLE) handler.postDelayed(this, 15)
        }
    }

    private fun searchStep() {
        // On timeout, settle for any frame exposed after the move (never one from before it).
        val timedOut = SystemClock.elapsedRealtime() - stepStarted > STEP_TIMEOUT_MS &&
            renderer.sharpnessTimestamp > movedAtNs
        if (!settledFrame() && !timedOut) return
        if (step > 0) results += plan[step] to renderer.sharpness
        step++
        if (step < plan.size) {
            moveTo(plan[step])
            return
        }
        android.util.Log.d("AuthRec", "contrast AF $phase: " + results.joinToString { (d, v) -> "%.2fD=%.6f".format(d, v) })
        val bestIndex = results.indices.maxBy { results[it].second }
        val best = results[bestIndex].first
        when (phase) {
            Phase.COARSE -> {
                // ±1.5 coarse steps: wide enough that the coarse pass's lag can't hide the peak.
                val u = toUnit(best)
                val span = 1.5f / (COARSE_STEPS - 1)
                sweep(Phase.FINE, List(FINE_STEPS) { i -> toDiopters((u - span + 2 * span * i / (FINE_STEPS - 1)).coerceIn(0f, 1f)) })
            }
            Phase.LOCAL -> {
                // Peak at the edge of the local window: it moved further, do a full sweep.
                if (bestIndex == 0 || bestIndex == results.lastIndex) {
                    sweep(Phase.COARSE, List(COARSE_STEPS) { i -> toDiopters(i / (COARSE_STEPS - 1f)) })
                } else {
                    lock(refine(bestIndex))
                }
            }
            else -> lock(refine(bestIndex))
        }
    }

    /** Parabola through the best point and its neighbours (in sqrt-diopter space) for a sub-step peak. */
    private fun refine(i: Int): Float {
        if (i == 0 || i == results.lastIndex) return results[i].first
        val (d0, s0) = results[i - 1]
        val (d1, s1) = results[i]
        val (d2, s2) = results[i + 1]
        val u0 = toUnit(d0)
        val u1 = toUnit(d1)
        val u2 = toUnit(d2)
        val denom = s0 - 2 * s1 + s2
        if (denom >= 0f) return d1 // not a peak shape; keep the measured best
        val h = (u2 - u0) / 2
        val offset = (h * (s0 - s2) / (2 * denom)).coerceIn(-h, h)
        return toDiopters((u1 + offset).coerceIn(0f, 1f))
    }

    private fun lock(d: Float) {
        locked = d
        moveTo(d)
        onLocked(d)
        if (continuous) {
            phase = Phase.MONITOR
            baseline = 0f
            lowFrames = 0
            lastFrameSeen = renderer.frameCounter
        } else {
            phase = Phase.IDLE
            renderer.sharpnessRegion = null
        }
    }

    /**
     * Continuous mode: remember the in-focus sharpness once the lens is back on the peak, then
     * refocus if sharpness stays below [REFOCUS_DROP] of it for [REFOCUS_FRAMES] frames.
     */
    private fun monitor() {
        val frame = renderer.frameCounter
        if (frame == lastFrameSeen || frame < minFrame) return
        lastFrameSeen = frame
        val s = renderer.sharpness
        if (baseline == 0f) {
            baseline = s
            return
        }
        // Slowly follow gentle changes (light, small movement) so only real defocus triggers.
        if (s > baseline * REFOCUS_DROP) {
            baseline = baseline * 0.98f + s * 0.02f
            lowFrames = 0
            return
        }
        if (++lowFrames < REFOCUS_FRAMES) return
        val u = toUnit(locked)
        val span = 1f / (COARSE_STEPS - 1)
        sweep(Phase.LOCAL, List(LOCAL_STEPS) { i -> toDiopters((u - span + 2 * span * i / (LOCAL_STEPS - 1)).coerceIn(0f, 1f)) })
    }

    private fun toDiopters(u: Float) = minFocusDiopters * u * u
    private fun toUnit(d: Float) = sqrt((d / minFocusDiopters).coerceIn(0f, 1f))

    companion object {
        private const val COARSE_STEPS = 12
        private const val FINE_STEPS = 9
        private const val COARSE_SETTLE_FRAMES = 3
        private const val FINE_SETTLE_FRAMES = 6
        private const val LOCAL_STEPS = 5
        private const val STEP_TIMEOUT_MS = 800L
        private const val REFOCUS_DROP = 0.6f
        private const val REFOCUS_FRAMES = 15
    }
}
