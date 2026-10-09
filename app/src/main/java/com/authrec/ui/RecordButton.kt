package com.authrec.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * The big record button: a white ring around a flat red core, with clear glass between them (the
 * GL backdrop, see [glassBox]). Recording turns the core into a rounded square; while the pipeline
 * warms up or the file is being saved the ring dims and a white arc runs round it (a press while
 * starting cancels; saving ignores presses). Replaces a glossy core with a pulsing halo that the
 * owner found scuffed.
 */
internal class RecordButton(context: Context, private val onPress: () -> Unit) : View(context) {

    enum class State { IDLE, STARTING, RECORDING, SAVING }

    var state = State.IDLE
        set(v) {
            if (field == v) return
            field = v
            animateMorph(if (v == State.RECORDING || v == State.SAVING) 1f else 0f)
            contentDescription = when (v) {
                State.IDLE -> "Record"
                State.STARTING -> "Starting"
                State.RECORDING -> "Stop"
                State.SAVING -> "Saving"
            }
            invalidate()
        }

    /** 0 = round core (ready), 1 = square core (recording). */
    private var morph = 0f
    private var morphAnim: ValueAnimator? = null

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    init {
        contentDescription = "Record"
        isClickable = true
    }

    private fun animateMorph(to: Float) {
        morphAnim?.cancel()
        morphAnim = ValueAnimator.ofFloat(morph, to).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { morph = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun outerRadius() = min(width, height) / 2f - dp(4)
    private fun ringWidth() = outerRadius() * 0.085f

    /** The clear glass inside the ring, in this view's coordinates (for the GL backdrop). */
    fun glassBox(): RectF {
        val r = outerRadius() - ringWidth()
        return RectF(width / 2f - r, height / 2f - r, width / 2f + r, height / 2f + r)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = outerRadius()
        val ringW = ringWidth()
        val busy = state == State.STARTING || state == State.SAVING
        val en = if (isEnabled) 1f else 0.45f

        ring.strokeWidth = ringW
        ring.alpha = ((if (busy) 0.35f else 0.95f) * en * 255).toInt()
        c.drawCircle(cx, cy, outer - ringW / 2, ring)
        if (busy) {
            val t = SystemClock.uptimeMillis()
            arc.strokeWidth = ringW
            val r = outer - ringW / 2
            rect.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(rect, (t % 1000) / 1000f * 360f, 90f, false, arc)
            postInvalidateOnAnimation()
        }

        // Core: a circle that becomes a rounded square, flat red with the faintest fall-off.
        val coreR = outer - ringW - outer * 0.075f
        val half = coreR * (1f - 0.48f * morph)
        val corner = half * (1f - 0.72f * morph)
        rect.set(cx - half, cy - half, cx + half, cy + half)
        val dim = (if (busy) 0.6f else 1f) * en
        core.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            Color.argb((255 * dim).toInt(), 255, 77, 66), Color.argb((255 * dim).toInt(), 224, 38, 28), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, corner, corner, core)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
            MotionEvent.ACTION_UP -> {
                animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                // A press while starting cancels the start; saving can't be interrupted.
                if (e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat() && state != State.SAVING) {
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    performClick()
                    onPress()
                }
            }
            MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(v: Int) = Glass.dp(context, v.toFloat())
}
