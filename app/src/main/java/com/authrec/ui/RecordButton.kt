package com.authrec.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min
import kotlin.math.sin

/**
 * The big record button: a glossy red core in a glass ring. Recording turns the core into a
 * rounded square with a soft pulsing glow; while the pipeline warms up or the file is being saved
 * an accent arc runs round the ring (a press while starting cancels; saving ignores presses).
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
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Glass.ACCENT
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

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f - dp(4)
        val ringW = outer * 0.10f
        val busy = state == State.STARTING || state == State.SAVING
        val t = SystemClock.uptimeMillis()

        if (state == State.RECORDING) {
            // Soft red halo, breathing about once a second.
            val a = (0.35f + 0.25f * sin(t / 1000.0 * Math.PI * 2).toFloat())
            glow.shader = RadialGradient(cx, cy, outer + dp(4), intArrayOf(Color.argb((a * 255).toInt(), 255, 59, 48), Color.TRANSPARENT),
                floatArrayOf(0.7f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, outer + dp(4), glow)
        }

        c.drawCircle(cx, cy, outer - ringW / 2, disc)
        ring.strokeWidth = ringW
        ring.alpha = if (isEnabled) 235 else 110
        c.drawCircle(cx, cy, outer - ringW / 2, ring)

        // Core: a circle that becomes a rounded square.
        val coreR = outer - ringW - outer * 0.07f
        val half = coreR * (1f - 0.45f * morph)
        val corner = half * (1f - 0.7f * morph)
        rect.set(cx - half, cy - half, cx + half, cy + half)
        val dim = if (busy || !isEnabled) 0.55f else 1f
        core.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            Color.argb((255 * dim).toInt(), 255, 110, 96), Color.argb((255 * dim).toInt(), 214, 24, 14), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, corner, corner, core)
        // Gloss on the upper part of the core.
        val g = RectF(rect.left + half * 0.18f, rect.top + half * 0.08f, rect.right - half * 0.18f, rect.top + half * 0.95f)
        gloss.shader = LinearGradient(0f, g.top, 0f, g.bottom, Color.argb((150 * dim).toInt(), 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRoundRect(g, corner * 0.8f, corner * 0.8f, gloss)

        if (busy) {
            arc.strokeWidth = ringW
            val r = outer - ringW / 2
            rect.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(rect, (t % 1000) / 1000f * 360f, 80f, false, arc)
        }
        if (busy || state == State.RECORDING) postInvalidateOnAnimation()
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
