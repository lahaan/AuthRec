package com.authrec.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A horizontal ruler for stepped values (ISO, shutter): drag sideways and the scale slides under
 * a fixed centre mark, one value per notch, with a haptic tick for each value passed. The value
 * under the mark is applied as soon as it gets there. A tap left or right of the mark steps once.
 */
internal class ValueDial(context: Context, private val onChange: (Int) -> Unit) : View(context) {

    var labels: List<String> = emptyList()
        set(v) {
            field = v
            index = index.coerceIn(0, (v.size - 1).coerceAtLeast(0))
            pos = index.toFloat()
            invalidate()
        }

    /** Values drawn in the accent colour (e.g. the 180° shutter). */
    var marked: Set<Int> = emptySet()
        set(v) { field = v; invalidate() }

    /** Shown dimmed: the value is what auto exposure chose, not a setting (yet). */
    var auto = false
        set(v) { if (field != v) { field = v; invalidate() } }

    var index = 0
        private set

    /** Moves the scale to [i] without reporting it (not while the user is dragging). */
    fun show(i: Int) {
        if (dragging || labels.isEmpty()) return
        val c = i.coerceIn(0, labels.lastIndex)
        if (c == index && pos == c.toFloat()) return
        index = c
        pos = c.toFloat()
        invalidate()
    }

    private val spacing = Glass.dp(context, 54f)
    /** Fractional index under the centre mark. */
    private var pos = 0f
    private var dragging = false
    private var moved = false
    private var downX = 0f
    private var downPos = 0f
    private var settle: ValueAnimator? = null

    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = Glass.dp(context, 1.5f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = Glass.dp(context, 12f)
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 0f, 1f, 0x80000000.toInt())
    }
    private val centre = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.ACCENT; strokeWidth = Glass.dp(context, 3f); strokeCap = Paint.Cap.ROUND }
    private val arrow = Path()

    override fun onDraw(c: Canvas) {
        if (labels.isEmpty()) return
        val cx = width / 2f
        val tickTop = height * 0.18f
        val tickBottom = height * 0.42f
        val baseline = height * 0.80f
        val first = (pos - cx / spacing - 1).toInt().coerceAtLeast(0)
        val last = (pos + cx / spacing + 1).toInt().coerceAtMost(labels.lastIndex)
        for (i in first..last) {
            val x = cx + (i - pos) * spacing
            val fade = (1f - abs(x - cx) / (cx + spacing)).coerceIn(0f, 1f)
            val a = (255 * fade * (if (auto) 0.6f else 1f)).toInt()
            val isMarked = i in marked
            tick.color = if (isMarked) Glass.ACCENT else Color.WHITE
            tick.alpha = a
            c.drawLine(x, tickTop, x, tickBottom, tick)
            // Minor ticks between values.
            if (i < labels.lastIndex) {
                tick.color = Color.WHITE
                tick.alpha = a / 2
                for (k in 1..2) {
                    val mx = x + spacing * k / 3f
                    c.drawLine(mx, tickTop + (tickBottom - tickTop) * 0.45f, mx, tickBottom, tick)
                }
            }
            val selected = i == index
            text.color = if (isMarked) Glass.ACCENT else Color.WHITE
            text.alpha = if (selected) (if (auto) 170 else 255) else (a * 0.75f).toInt()
            text.textSize = Glass.dp(context, if (selected) 14f else 11f)
            text.isFakeBoldText = selected
            c.drawText(labels[i], x, baseline, text)
        }
        // The fixed mark the scale slides under.
        c.drawLine(cx, tickTop - Glass.dp(context, 4f), cx, tickBottom + Glass.dp(context, 4f), centre)
        val s = Glass.dp(context, 6f)
        arrow.reset()
        arrow.moveTo(cx - s, 0f)
        arrow.lineTo(cx + s, 0f)
        arrow.lineTo(cx, s * 1.2f)
        arrow.close()
        centre.style = Paint.Style.FILL
        c.drawPath(arrow, centre)
        centre.style = Paint.Style.STROKE
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (labels.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settle?.cancel()
                dragging = true
                moved = false
                downX = e.x
                downPos = pos
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && abs(e.x - downX) < Glass.dp(context, 6f)) return true
                moved = true
                pos = (downPos + (downX - e.x) / spacing).coerceIn(0f, labels.lastIndex.toFloat())
                select(pos.roundToInt())
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (!moved && e.actionMasked == MotionEvent.ACTION_UP) {
                    // A tap beside the mark nudges one value that way.
                    select((index + if (e.x > width / 2f) 1 else -1).coerceIn(0, labels.lastIndex))
                }
                settle = ValueAnimator.ofFloat(pos, index.toFloat()).apply {
                    duration = 160
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }
        return true
    }

    private fun select(i: Int) {
        if (i == index) return
        index = i
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onChange(i)
    }
}
