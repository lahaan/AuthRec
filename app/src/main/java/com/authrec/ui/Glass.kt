package com.authrec.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * The "Glass" look: translucent, glossy capsules with a bright rim (a nod to liquid glass and
 * Frutiger Aero), an aqua accent for whatever is switched on, red for recording. The camera image
 * is a SurfaceView, so real backdrop blur isn't available; a dark translucent base under the gloss
 * keeps text readable over bright scenes instead.
 */
internal object Glass {
    const val ACCENT = 0xFF4FD8FF.toInt()
    const val ACCENT_DEEP = 0xFF1C9BD8.toInt()
    const val REC = 0xFFFF3B30.toInt()
    const val AMBER = 0xFFFFB300.toInt()
    const val TEXT = Color.WHITE
    const val TEXT_DIM = 0xB3FFFFFF.toInt()

    fun dp(context: Context, v: Float) = v * context.resources.displayMetrics.density
}

internal fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
internal fun View.dp(v: Number): Int = context.dp(v)

/**
 * A glass capsule: dark translucent base, a soft white body gradient, a glossy highlight over the
 * upper half and a rim that is bright on top and fades towards the bottom. [accent] fills it with
 * the accent colour (a switch that is on, a selected option); pressed brightens it.
 */
internal class GlassDrawable(
    private val radius: Float,
    var accent: Int? = null,
    /** Darker base for panels that sit over the image and hold small text. */
    private val solid: Boolean = false,
) : Drawable() {
    private val base = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private var pressed = false
    private var enabled = true
    /** Base colour under the gloss when there is no accent (null = the default for [solid]). */
    var baseColor: Int? = null

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val stroke = radius.coerceAtMost(3f).coerceAtLeast(2f)
        r.set(b.left + stroke / 2, b.top + stroke / 2, b.right - stroke / 2, b.bottom - stroke / 2)
        val rad = radius.coerceAtMost(r.height() / 2)
        val top = r.top
        val bottom = r.bottom
        val a = if (enabled) 1f else 0.45f

        val tint = accent
        base.color = tint ?: baseColor ?: if (solid) 0xA0101418.toInt() else 0x66101418
        base.alpha = (base.alpha * a).toInt()
        c.drawRoundRect(r, rad, rad, base)

        val lift = if (pressed) 0x40 else 0
        body.shader = LinearGradient(0f, top, 0f, bottom,
            Color.argb((((if (tint != null) 0x50 else 0x38) + lift) * a).toInt(), 255, 255, 255),
            Color.argb((((if (tint != null) 0x08 else 0x10) + lift / 2) * a).toInt(), 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, body)

        // Specular gloss on the upper half, inset from the rim.
        val inset = stroke * 1.5f
        val glossRect = RectF(r.left + inset, r.top + inset, r.right - inset, r.top + r.height() * 0.52f)
        gloss.shader = LinearGradient(0f, glossRect.top, 0f, glossRect.bottom,
            Color.argb((0x70 * a).toInt(), 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        val gr = (rad - inset).coerceAtLeast(0f)
        c.drawRoundRect(glossRect, gr, gr.coerceAtMost(glossRect.height()), gloss)

        rim.strokeWidth = stroke
        rim.shader = LinearGradient(0f, top, 0f, bottom,
            Color.argb((0xC0 * a).toInt(), 255, 255, 255), Color.argb((0x30 * a).toInt(), 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, rim)
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val p = android.R.attr.state_pressed in state
        val e = android.R.attr.state_enabled in state
        if (p == pressed && e == enabled) return false
        pressed = p
        enabled = e
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Builds controls in one of the two styles, so panels shared by both layouts (priority limits,
 * focus bar, sliders) look right in either.
 */
internal class UiKit(val context: Context, val glass: Boolean) {

    /** A tappable control: a glass capsule, or the classic compact system button. */
    fun button(text: String, onClick: () -> Unit): Button = Button(context).apply {
        this.text = text
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        // Compact: the default 48 dp minimum height makes the side columns collide on landscape phones.
        minHeight = 0
        minimumHeight = 0
        setOnClickListener { onClick() }
        if (glass) {
            textSize = 13f
            setTextColor(Glass.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(3f, 0f, 1f, 0x80000000.toInt())
            background = GlassDrawable(context.dp(19).toFloat())
            stateListAnimator = null
            setPadding(context.dp(14), context.dp(9), context.dp(14), context.dp(9))
            pressAnimation(this)
        } else {
            textSize = 12f
            setPadding(28, 28, 28, 28)
        }
    }

    /** Marks a glass button as on (accent fill) or off. No-op in the classic style. */
    fun setOn(view: View, on: Boolean, color: Int = Glass.ACCENT_DEEP) {
        val bg = view.background as? GlassDrawable ?: return
        val want = if (on) color else null
        if (bg.accent != want) {
            bg.accent = want
            bg.invalidateSelf()
        }
    }

    fun label(text: String = "", size: Float = 12f, dim: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(if (dim) Glass.TEXT_DIM else Glass.TEXT)
        if (glass) setShadowLayer(3f, 0f, 1f, 0x80000000.toInt())
    }

    /** A panel holding rows of controls over the image. */
    fun panel(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (glass) {
            background = GlassDrawable(context.dp(22).toFloat(), solid = true)
            setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
        } else {
            setBackgroundColor(0xB0000000.toInt())
            setPadding(32, 16, 32, 16)
        }
    }

    fun seekBar(max: Int, initial: Int): SeekBar = SeekBar(context).apply {
        this.max = max
        progress = initial
        if (glass) {
            progressTintList = ColorStateList.valueOf(Glass.ACCENT)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0x60FFFFFF)
        }
    }

    /**
     * A row of mutually exclusive options (glass: one capsule per option, the chosen one lit).
     * Returns the row and a function that marks option [i] as chosen.
     */
    fun segmented(options: List<String>, onPick: (Int) -> Unit): Pair<LinearLayout, (Int) -> Unit> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val buttons = options.mapIndexed { i, o ->
            button(o) { onPick(i) }.apply {
                if (glass) setPadding(context.dp(12), context.dp(7), context.dp(12), context.dp(7))
                textSize = if (glass) 12f else 11f
            }.also { row.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(6) }) }
        }
        val mark: (Int) -> Unit = { chosen ->
            buttons.forEachIndexed { i, b ->
                if (glass) setOn(b, i == chosen) else b.setTextColor(if (i == chosen) Glass.ACCENT else Color.WHITE)
            }
        }
        return row to mark
    }

    companion object {
        /** Glass controls give a little when pressed. */
        fun pressAnimation(v: View) {
            v.setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }
                false
            }
        }

        /** A plain rounded outline (focus square and the like). */
        fun outline(color: Int, width: Int, radius: Float = 0f) = GradientDrawable().apply {
            setStroke(width, color)
            cornerRadius = radius
        }

        fun pulse(view: View): ValueAnimator = ValueAnimator.ofFloat(1f, 0.35f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { view.alpha = it.animatedValue as Float }
            start()
        }
    }
}
