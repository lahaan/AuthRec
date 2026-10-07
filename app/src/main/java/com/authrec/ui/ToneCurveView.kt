package com.authrec.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh

/**
 * The eDR curve: scene brightness in → view brightness out, both in stops around middle grey,
 * with one handle for the highlights (drag down to pull them in) and one for the shadows (drag up
 * to lift them). It draws the same function the finish shader applies (`balanceTone` in
 * PipelineShaders), so what you see is the curve you get. Drags are relative and slow (one step
 * per dp, the whole range in ~200 dp) so small changes are easy; double-tap resets.
 */
internal class ToneCurveView(
    context: Context,
    /** Called with highlights and shadows (−100..100 each) while dragging. */
    private val onChange: (Int, Int) -> Unit,
    private val onReset: () -> Unit,
) : View(context) {

    var highlights = 0
        private set
    var shadows = 0
        private set
    /** Drawn dimmed while eDR is off. */
    var active = true
        set(v) { field = v; invalidate() }

    fun set(highlights: Int, shadows: Int) {
        if (dragging != null) return
        this.highlights = highlights
        this.shadows = shadows
        invalidate()
    }

    private val range = 6f // ± stops shown
    private val padL = Glass.dp(context, 12f)
    private val padR = Glass.dp(context, 12f)
    private val padT = Glass.dp(context, 12f)
    private val padB = Glass.dp(context, 8f)

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x30FFFFFF; strokeWidth = 1.5f }
    private val identity = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF; strokeWidth = 2f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Glass.ACCENT; strokeWidth = Glass.dp(context, 3f); style = Paint.Style.STROKE
        setShadowLayer(Glass.dp(context, 6f), 0f, 0f, Glass.ACCENT)
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(6f, 0f, 1f, 0xA0000000.toInt()) }
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.ACCENT; style = Paint.Style.STROKE; strokeWidth = Glass.dp(context, 2.5f) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = Glass.dp(context, 11f)
        setShadowLayer(3f, 0f, 1f, 0x80000000.toInt())
    }
    private val path = Path()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // the curve's glow (shadow layer on a path)
    }

    private fun softplus(x: Float, w: Float) = w * ln(1f + exp(x / w))

    /** Output stops for input stops [e], as in the shader. */
    private fun out(e: Float, hi: Float = highlights / 100f, lo: Float = shadows / 100f): Float {
        val h = 0.6f * hi * softplus(e - 1f, 0.5f)
        val l = 2.5f * lo * tanh(softplus(-1f - e, 0.5f) / 5f)
        return e + h + l
    }

    private fun xOf(e: Float) = padL + (e + range) / (2 * range) * (width - padL - padR)
    private fun yOf(o: Float) = padT + (1 - (o + range) / (2 * range)) * (height - padT - padB)

    override fun onDraw(c: Canvas) {
        val a = if (active) 1f else 0.45f
        for (s in -6..6 step 2) {
            c.drawLine(xOf(s.toFloat()), padT, xOf(s.toFloat()), height - padB, grid)
            c.drawLine(padL, yOf(s.toFloat()), width - padR, yOf(s.toFloat()), grid)
        }
        c.drawLine(xOf(-range), yOf(-range), xOf(range), yOf(range), identity)
        path.reset()
        var e = -range
        path.moveTo(xOf(e), yOf(out(e)))
        while (e < range) {
            e += 0.1f
            path.lineTo(xOf(e), yOf(out(e).coerceIn(-range - 1, range + 1)))
        }
        curve.alpha = (255 * a).toInt()
        c.drawPath(path, curve)
        for ((handleE, text) in listOf(HI_AT to "Highlights %+d".format(highlights), LO_AT to "Shadows %+d".format(shadows))) {
            val x = xOf(handleE)
            val y = yOf(out(handleE))
            knob.alpha = (255 * a).toInt()
            c.drawCircle(x, y, Glass.dp(context, 9f), knob)
            c.drawCircle(x, y, Glass.dp(context, 9f), knobRing)
            label.textAlign = if (handleE > 0) Paint.Align.RIGHT else Paint.Align.LEFT
            label.alpha = (255 * a).toInt()
            val ty = if (handleE > 0) y + Glass.dp(context, 26f) else y - Glass.dp(context, 16f)
            c.drawText(text, x + if (handleE > 0) Glass.dp(context, 12f) else -Glass.dp(context, 12f), ty, label)
        }
    }

    /** Which handle a drag moves: true = highlights. */
    private var dragging: Boolean? = null
    private var downY = 0f
    private var downValue = 0

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            onReset()
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The nearer handle (by x) takes the drag.
                dragging = abs(e.x - xOf(HI_AT)) < abs(e.x - xOf(LO_AT))
                downY = e.y
                downValue = if (dragging == true) highlights else shadows
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val hi = dragging ?: return true
                // Up raises that end of the curve, down lowers it.
                val v = (downValue - (e.y - downY) / resources.displayMetrics.density).toInt().coerceIn(-100, 100)
                val step = (v / 5) * 5
                if (hi && step != highlights) { highlights = step; tickIfZero(step); onChange(highlights, shadows) }
                if (!hi && step != shadows) { shadows = step; tickIfZero(step); onChange(highlights, shadows) }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = null
        }
        return true
    }

    private fun tickIfZero(v: Int) {
        if (v == 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    companion object {
        /** Input stops where the handles sit on the curve. */
        const val HI_AT = 3.5f
        const val LO_AT = -3.5f
    }
}
