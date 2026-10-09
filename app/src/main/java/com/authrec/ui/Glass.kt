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
 * The "Glass" look: clear glass capsules that bend the image behind them (GL, GlassBackdrop) with
 * a hairline rim, an accent for whatever is switched on (a pill that slides between choices), red
 * for recording.
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
 * A glass capsule's view layer. The glass itself (the image behind, bent at the rim, with its
 * specular line) is drawn by GL underneath ([com.authrec.gl.GlassBackdrop]); this adds only a light
 * tint, so the shape reads on the black margins and white labels over bright scenes, and a
 * hairline rim. [accent] fills it with the accent colour (a switch that is on), fading in and
 * out; pressed brightens it. The owner found the earlier body gradient and big top gloss too
 * strong to pass for glass.
 */
internal class GlassDrawable(
    /** Corner radius in px (also what the backdrop glass behind it uses). */
    val radius: Float,
    initialAccent: Int? = null,
    /** Darker tint for panels that sit over the image and hold small text. */
    private val solid: Boolean = false,
) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private val hairline = android.content.res.Resources.getSystem().displayMetrics.density
    private var pressed = false
    private var enabled = true
    /** Tint under the label when there is no accent (null = the default for [solid]). */
    var baseColor: Int? = null

    var accent: Int? = initialAccent
        set(v) {
            if (v == field) return
            field = v
            if (v != null) shownAccent = v
            fadeAccent(if (v != null) 1f else 0f)
        }
    private var shownAccent = initialAccent ?: Glass.ACCENT_DEEP
    private var accentLevel = if (initialAccent != null) 1f else 0f
    private var accentAnim: ValueAnimator? = null

    /** Inside a [SelectionLayout] that marks this one: its sliding pill shows instead of a fill. */
    var clear = false
        set(v) {
            if (v != field) { field = v; invalidateSelf() }
        }

    private fun fadeAccent(to: Float) {
        accentAnim?.cancel()
        if (callback == null) {
            accentLevel = to
            return
        }
        accentAnim = ValueAnimator.ofFloat(accentLevel, to).apply {
            duration = 160
            addUpdateListener { accentLevel = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val stroke = hairline
        r.set(b.left + stroke / 2, b.top + stroke / 2, b.right - stroke / 2, b.bottom - stroke / 2)
        val rad = radius.coerceAtMost(minOf(r.height(), r.width()) / 2)
        val a = if (enabled) 1f else 0.45f

        if (!clear) {
            // A light dark wash with a little white in it: visible on black, gentle on the image.
            fill.shader = null
            fill.color = baseColor ?: if (solid) 0x66101418 else 0x1F101418
            fill.alpha = (fill.alpha * a).toInt()
            c.drawRoundRect(r, rad, rad, fill)
            fill.color = Color.argb((0x14 * a).toInt(), 255, 255, 255)
            c.drawRoundRect(r, rad, rad, fill)
            if (accentLevel > 0f) {
                fill.color = shownAccent
                fill.alpha = (0xE6 * accentLevel * a).toInt()
                c.drawRoundRect(r, rad, rad, fill)
            }
        }
        if (pressed) {
            fill.color = Color.argb(0x22, 255, 255, 255)
            c.drawRoundRect(r, rad, rad, fill)
        }

        rim.strokeWidth = stroke
        rim.shader = LinearGradient(0f, r.top, 0f, r.bottom,
            Color.argb((0x66 * a).toInt(), 255, 255, 255), Color.argb((0x14 * a).toInt(), 255, 255, 255), Shader.TileMode.CLAMP)
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
 * A LinearLayout whose chosen glass button (a child, or a button in a nested row) is marked by an
 * accent pill that slides behind the buttons from the previous choice to the new one, and fades
 * in or out when there's no choice (the lens chips while the front camera is on).
 */
internal class SelectionLayout(context: Context) : LinearLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var chosen: View? = null
    private val cur = RectF()
    private val from = RectF()
    private val to = RectF()
    private val tmp = android.graphics.Rect()
    private var curRadius = 0f
    private var fromRadius = 0f
    private var toRadius = 0f
    /** The pill's opacity (0..1). */
    private var shown = 0f
    private var slide: ValueAnimator? = null
    private var fade: ValueAnimator? = null
    var color = Glass.ACCENT_DEEP

    init {
        setWillNotDraw(false)
    }

    fun select(view: View?) {
        if (view === chosen) return
        val previous = chosen
        (previous?.background as? GlassDrawable)?.clear = false
        chosen = view
        (view?.background as? GlassDrawable)?.clear = true
        when {
            view == null -> fadeTo(0f)
            previous == null || cur.isEmpty || !isLaidOut || !view.isLaidOut || !isMine(view) -> {
                // Nothing to slide from: appear where it belongs.
                slide?.cancel()
                snap()
                fadeTo(1f)
            }
            else -> {
                from.set(cur)
                fromRadius = curRadius
                rectOf(view, to)
                toRadius = radiusOf(view)
                slide?.cancel()
                slide = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 280
                    interpolator = android.view.animation.DecelerateInterpolator(1.6f)
                    addUpdateListener {
                        val f = it.animatedValue as Float
                        cur.set(from.left + (to.left - from.left) * f, from.top + (to.top - from.top) * f,
                            from.right + (to.right - from.right) * f, from.bottom + (to.bottom - from.bottom) * f)
                        curRadius = fromRadius + (toRadius - fromRadius) * f
                        invalidate()
                    }
                    start()
                }
                fadeTo(1f)
            }
        }
    }

    /** Forgets the choice without animating (its views are about to be replaced). */
    fun reset() {
        slide?.cancel()
        fade?.cancel()
        (chosen?.background as? GlassDrawable)?.clear = false
        chosen = null
        shown = 0f
        cur.setEmpty()
        invalidate()
    }

    private fun fadeTo(target: Float) {
        if (shown == target && fade?.isRunning != true) return
        fade?.cancel()
        fade = ValueAnimator.ofFloat(shown, target).apply {
            duration = 160
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun isMine(v: View): Boolean {
        var p = v.parent
        while (p != null) {
            if (p === this) return true
            p = p.parent
        }
        return false
    }

    private fun rectOf(v: View, out: RectF) {
        tmp.set(0, 0, v.width, v.height)
        offsetDescendantRectToMyCoords(v, tmp)
        out.set(tmp)
        val inset = resources.displayMetrics.density / 2
        out.inset(inset, inset)
    }

    private fun radiusOf(v: View): Float {
        val r = (v.background as? GlassDrawable)?.radius ?: (minOf(v.width, v.height) / 2f)
        return r.coerceAtMost(minOf(v.width, v.height) / 2f)
    }

    private fun snap() {
        val v = chosen ?: return
        if (!isMine(v) || !v.isLaidOut) return
        rectOf(v, cur)
        curRadius = radiusOf(v)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (slide?.isRunning != true) snap()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val v = chosen
        if (shown > 0f && !cur.isEmpty) {
            paint.color = color
            paint.alpha = (0xE6 * shown * (if (v == null || v.isEnabled) 1f else 0.45f)).toInt()
            canvas.drawRoundRect(cur, curRadius, curRadius, paint)
        }
        super.dispatchDraw(canvas)
    }
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
        val row = SelectionLayout(context).apply {
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
            if (glass) row.select(buttons.getOrNull(chosen))
            else buttons.forEachIndexed { i, b -> b.setTextColor(if (i == chosen) Glass.ACCENT else Color.WHITE) }
        }
        return row to mark
    }

    /** Glass panels appear with a short fade and grow; hiding is immediate (state reads visibility). */
    fun reveal(panel: View) {
        if (!glass) return
        panel.alpha = 0f
        panel.scaleX = 0.96f
        panel.scaleY = 0.96f
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
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
