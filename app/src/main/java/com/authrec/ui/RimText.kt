package com.authrec.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.TextView

/**
 * Text that reads over any image: a dark rim under the letters. The statistics had a soft shadow
 * only, and over a bright scene (a window, the sky) the white letters were hard to make out
 * (QA, 2026-11-13).
 */
internal class RimText(context: Context) : TextView(context) {
    private val rim = context.resources.displayMetrics.density * 1.6f

    override fun onDraw(canvas: Canvas) {
        val lines = layout
        if (lines != null) {
            // The text's own paint, as a stroke: the letters' outlines, drawn before the letters.
            val p = paint
            val style = p.style
            val color = p.color
            p.style = Paint.Style.STROKE
            p.strokeWidth = rim
            p.strokeJoin = Paint.Join.ROUND
            p.color = 0x99000000.toInt()
            canvas.save()
            canvas.translate(compoundPaddingLeft.toFloat(), extendedPaddingTop.toFloat())
            lines.draw(canvas)
            canvas.restore()
            p.style = style
            p.color = color
        }
        super.onDraw(canvas)
    }
}
