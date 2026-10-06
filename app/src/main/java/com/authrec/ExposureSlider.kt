package com.authrec

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Vertical exposure slider for the side of the screen, where the right thumb reaches it while
 * recording. A drag moves the value by how far the finger travels (never to where it touched, so
 * grabbing it can't make the exposure jump); double-tap resets to 0. Whole stops tick as they pass.
 *
 * The parts of the range the sensor can't deliver (beyond the camera's exposure compensation,
 * so plain digital gain) are drawn amber, and the value turns amber while [warn] is set (high
 * effective ISO: expect noise).
 */
class ExposureSlider(context: Context, private val onChange: (Float) -> Unit) : View(context) {

    /** ± range in EV. */
    var range = 5f
    var value = 0f
        private set
    /** EV the sensor can reach; outside it the slider is amber. */
    var sensorRange: ClosedFloatingPointRange<Float> = -range..range
        set(v) { field = v; invalidate() }
    var warn = false
        set(v) { if (field != v) { field = v; invalidate() } }

    fun setValue(v: Float) {
        if (v != value) { value = v; invalidate() }
    }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xC0FFFFFF.toInt(); strokeWidth = 5f }
    private val amberLine = Paint(line).apply { color = AMBER }
    private val tick = Paint(line).apply { strokeWidth = 3f }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(6f, 0f, 0f, Color.BLACK) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.RIGHT
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val title = Paint(label).apply { textSize = 28f; textAlign = Paint.Align.CENTER }

    private val pad = 56f
    private val trackX get() = width - 44f
    private fun yOf(v: Float) = height - pad - (v + range) / (2 * range) * (height - 2 * pad)

    override fun onDraw(c: Canvas) {
        val x = trackX
        c.drawText("EV", x, 30f, title)
        val lo = sensorRange.start.coerceIn(-range, range)
        val hi = sensorRange.endInclusive.coerceIn(-range, range)
        if (lo > -range) c.drawLine(x, yOf(-range), x, yOf(lo), amberLine)
        c.drawLine(x, yOf(lo), x, yOf(hi), line)
        if (hi < range) c.drawLine(x, yOf(hi), x, yOf(range), amberLine)
        for (ev in -range.toInt()..range.toInt()) {
            val half = if (ev == 0) 22f else 10f
            c.drawLine(x - half, yOf(ev.toFloat()), x + half, yOf(ev.toFloat()), tick)
        }
        val y = yOf(value)
        c.drawCircle(x, y, 20f, knob)
        label.color = if (warn) AMBER else Color.WHITE
        c.drawText("%+.1f".format(value), x - 34f, y + 12f, label)
    }

    private var downY = 0f
    private var downValue = 0f
    private var moved = false
    private var lastTapMs = 0L

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y
                downValue = value
                moved = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && abs(e.y - downY) < 12f) return true
                moved = true
                val perPx = 2 * range / (height - 2 * pad)
                val v = ((downValue + (downY - e.y) * perPx) * 10).roundToInt() / 10f
                set(v.coerceIn(-range, range))
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    if (e.eventTime - lastTapMs < 350) {
                        set(0f)
                        lastTapMs = 0
                    } else {
                        lastTapMs = e.eventTime
                    }
                }
            }
        }
        return true
    }

    private fun set(v: Float) {
        if (v == value) return
        // A tick for every whole stop crossed (and landing on 0).
        if (floor(v) != floor(value) || v == 0f) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        value = v
        invalidate()
        onChange(v)
    }

    companion object {
        const val AMBER = 0xFFFFB300.toInt()
    }
}
