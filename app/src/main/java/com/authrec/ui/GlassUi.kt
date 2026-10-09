package com.authrec.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.authrec.CameraActivity
import com.authrec.camera.AeMode
import com.authrec.camera.AfMode
import com.authrec.color.LogProfile
import com.authrec.gl.GlassRect
import com.authrec.record.VideoCodec
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Glass layout. Controls live in the black margins beside the 4:3 image where they can:
 * on the right the lenses, the big record button and the EV slider (where the right thumb rests
 * while filming); on the left mode, look, eDR, adjustments and settings. Pro adds a bar along
 * the bottom of the image with the exposure, ISO, shutter, white balance, focus and frame rate;
 * ISO and shutter open a dial to drag through the stops. Rarely changed options (codec, bitrate,
 * profile, clean-up, layout, lens tools) sit in the settings sheet.
 */
internal class GlassUi(private val a: CameraActivity) : CameraUi {

    private val kit = a.kit
    private val dm = a.resources.displayMetrics
    private val screenW = maxOf(dm.widthPixels, dm.heightPixels)
    private val screenH = minOf(dm.widthPixels, dm.heightPixels)
    /** Width of each black margin beside the 4:3 image (the preview letterboxes to it). */
    private val side = ((screenW - screenH * 4f / 3f) / 2f).roundToInt().coerceAtLeast(0)
    private val imageW = screenW - 2 * side
    /** Right margin: the EV slider against the image, then a column with lenses / record / flip. */
    private val sliderW = a.dp(64)
    private val rightColumnW = (side - sliderW).coerceAtLeast(a.dp(96))
    private val recSize = a.dp(78)
    /** The record button sits a little below the middle, where the right thumb rests. */
    private val recTop = screenH / 2 + a.dp(24) - recSize / 2
    private val lensTop = a.dp(14)
    /** Width of the ISO / shutter dials (declared before the panels that use it). */
    private val dialWidth = minOf(a.dp(520), imageW - a.dp(60))

    // ---- Right: lenses, record, flip ----
    private val recordButton = RecordButton(a) { a.toggleRecording() }
    private val lensColumn = SelectionLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val flipButton = roundChip("") { flipCamera() }.apply {
        foreground = FlipIcon(this, a.dp(2).toFloat())
        contentDescription = "Front / back camera"
    }
    /** Selfie-style mirror, next to the flip button while the front camera is on. */
    private val mirrorButton = roundChip("") { a.setMirrorFront(!a.mirrorFront) }.apply {
        foreground = MirrorIcon(this, a.dp(2).toFloat())
        contentDescription = "Mirror front camera"
    }
    private var lensKeys = emptyList<String>()
    private val lensChips = mutableMapOf<Int, Button>()
    /** Back lens to return to from the front camera. */
    private var lastBack: String? = null

    // ---- Left: mode, look, eDR, adjust, settings ----
    private val modeRow = kit.segmented(listOf("Simple", "Pro")) { a.setSimple(it == 0) }
    private val lookChip = captionChip("LOOK") { a.showViewMenu(it) }
    private val edrChip = kit.button("eDR") { a.setEdr(!a.edrOn) }.apply {
        setOnLongClickListener { if (!a.simple) toggle(adjustPanel); true }
    }
    private val adjustChip = kit.button("Adjust") { toggle(adjustPanel) }
    private val resChip = kit.button("") { a.setSuperpixel(!a.superpixel) }
    private val settingsChip = kit.button("⚙  Settings") { toggle(settingsSheet) }

    // ---- Top: REC timer, hint ----
    private val recDot = View(a).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Glass.REC) } }
    private val recTime = kit.label("00:00", 15f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
    private val recPill = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GlassDrawable(a.dp(16).toFloat(), solid = true)
        setPadding(a.dp(12), a.dp(6), a.dp(14), a.dp(6))
        addView(recDot, LinearLayout.LayoutParams(a.dp(10), a.dp(10)).apply { marginEnd = a.dp(8) })
        addView(recTime)
        visibility = View.GONE
    }
    private var recPulse: ValueAnimator? = null
    private val hintText = kit.label(size = 13f).apply { maxWidth = a.dp(420) }
    private val hintAction = kit.button("") { }
    private val hint = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GlassDrawable(a.dp(20).toFloat(), solid = true)
        setPadding(a.dp(16), a.dp(10), a.dp(10), a.dp(10))
        addView(hintText)
        addView(hintAction, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = a.dp(12) })
        addView(kit.button("✕") { closeHint() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = a.dp(6) })
        visibility = View.GONE
    }
    private var hintClose: (() -> Unit)? = null

    // ---- Bottom (Pro): exposure bar and its panels ----
    private val aeChip = captionChip("EXPOSURE") { toggle(aePanel) }
    private val isoChip = captionChip("ISO") { toggle(isoPanel) }
    private val shutterChip = captionChip("SHUTTER") { toggle(shutterPanel) }
    private val wbChip = captionChip("WB") { toggle(wbPanel) }
    private val focusChip = captionChip("FOCUS") { a.cycleAf() }
    private val fpsChip = captionChip("FPS") { toggle(fpsPanel) }
    private val proBar = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        listOf(aeChip, isoChip, shutterChip, wbChip, focusChip, fpsChip).forEach {
            addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = a.dp(4); marginEnd = a.dp(4) })
        }
    }

    private val aeModes = listOf(AeMode.AUTO, AeMode.PRIORITY, AeMode.LOCKED, AeMode.MANUAL)
    private val aeRow = kit.segmented(listOf("Auto", "Priority", "Locked", "Manual")) { a.setAe(aeModes[it]) }
    private val limitsButton = kit.button("Limits…") { a.togglePriorityPanel() }
    private val aePanel = panel(
        title("Exposure"),
        LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(aeRow.first)
            addView(limitsButton)
        },
        kit.label("Auto and Locked follow the phone's metering; Priority keeps shutter and ISO inside your limits; " +
            "Manual is yours (drag ISO or shutter).", 11f, dim = true).apply { maxWidth = a.dp(460) },
    )

    private var isoValues = emptyList<Int>()
    private val isoDial = ValueDial(a) { i -> isoValues.getOrNull(i)?.let { a.setManualIso(it) } }
    private val isoAuto = kit.button("Auto") { a.setAe(AeMode.AUTO) }
    private val isoTitle = title("ISO")
    private val isoPanel = panel(dialHeader("ISO", isoAuto, isoTitle).fullWidth(), isoDial.sized())

    private var shutterValues = emptyList<Long>()
    private val shutterDial = ValueDial(a) { i -> shutterValues.getOrNull(i)?.let { a.setManualShutter(it) } }
    private val shutterAuto = kit.button("Auto") { a.setAe(AeMode.AUTO) }
    private val shutterPanel = panel(dialHeader("Shutter", shutterAuto).fullWidth(), shutterDial.sized())

    private val wbRow = kit.segmented(listOf("Auto", "Locked")) { if ((it == 1) != a.capture.awbLock) a.toggleAwbLock() }
    private val warmthBar = kit.seekBar(200, a.wbWarmth + 100)
    private val tintBar = kit.seekBar(200, a.wbTint + 100)
    private val warmthLabel = kit.label(size = 12f).apply { minWidth = a.dp(96) }
    private val tintLabel = kit.label(size = 12f).apply { minWidth = a.dp(96) }
    private val wbPanel = panel(
        title("White balance"),
        wbRow.first,
        sliderRow(warmthLabel, warmthBar) { a.setWbShift(it - 100, a.wbTint) },
        sliderRow(tintLabel, tintBar) { a.setWbShift(a.wbWarmth, it - 100) },
        kit.button("Reset trim") { a.setWbShift(0, 0) },
    )

    private val fpsOptions = listOf(24, 25, 30)
    private val fpsRow = kit.segmented(fpsOptions.map { "$it" }) { a.setFps(fpsOptions[it]) }
    private val fpsPanel = panel(title("Frame rate"), fpsRow.first)

    // ---- Adjust: eDR curve (Pro) and view sliders ----
    private val curve = ToneCurveView(a, onChange = { h, s -> a.setEdrCurve(h, s) }, onReset = { a.resetEdrCurve() })
    private val edrSwitch = kit.segmented(listOf("eDR off", "eDR on")) { a.setEdr(it == 1) }
    private val curveBox = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        addView(edrSwitch.first)
        addView(curve, LinearLayout.LayoutParams(a.dp(210), a.dp(150)).apply { topMargin = a.dp(6) })
        addView(kit.label("Drag a dot up or down; double-tap resets", 10.5f, dim = true))
    }
    private val strengthBar = kit.seekBar(100, a.lutStrength)
    private val saturationBar = kit.seekBar(200, a.saturationPct)
    private val vibranceBar = kit.seekBar(200, a.vibrancePct + 100)
    private val adjWarmthBar = kit.seekBar(200, a.wbWarmth + 100)
    private val adjTintBar = kit.seekBar(200, a.wbTint + 100)
    private val strengthLabel = kit.label(size = 12f).apply { minWidth = a.dp(96) }
    private val saturationLabel = kit.label(size = 12f).apply { minWidth = a.dp(96) }
    private val vibranceLabel = kit.label(size = 12f).apply { minWidth = a.dp(96) }
    private val adjWarmthLabel = kit.label(size = 12f).apply { minWidth = a.dp(96) }
    private val adjTintLabel = kit.label(size = 12f).apply { minWidth = a.dp(96) }
    private val adjustPanel = kit.panel().apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = View.GONE
        addView(curveBox, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = a.dp(14) })
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(sliderRow(strengthLabel, strengthBar) { a.setLookAdjust(strength = it) })
            addView(sliderRow(saturationLabel, saturationBar) { a.setLookAdjust(saturation = it) })
            addView(sliderRow(vibranceLabel, vibranceBar) { a.setLookAdjust(vibrance = it - 100) })
            addView(sliderRow(adjWarmthLabel, adjWarmthBar) { a.setWbShift(it - 100, a.wbTint) })
            addView(sliderRow(adjTintLabel, adjTintBar) { a.setWbShift(a.wbWarmth, it - 100) })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(kit.button("Reset") {
                    a.setLookAdjust(100, 100, 0)
                    a.setWbShift(0, 0)
                    a.resetEdrCurve()
                })
                addView(kit.button("Edit look…") { a.openLookEditor() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    marginStart = a.dp(8)
                })
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = a.dp(6) })
        }, LinearLayout.LayoutParams(a.dp(272), WRAP_CONTENT))
    }

    // ---- Settings sheet ----
    private val settingsRows = mutableListOf<() -> Unit>()
    private val proSettings = mutableListOf<View>()
    private val settingsSheet: View = buildSettings()

    private val panels: List<View> = listOf(aePanel, isoPanel, shutterPanel, wbPanel, fpsPanel, adjustPanel, settingsSheet)

    override val lensAnchor: View get() = lensColumn
    override val lookAnchor: View get() = lookChip

    override val root: View = FrameLayout(a).apply {
        setBackgroundColor(Color.BLACK)
        addView(a.surfaceView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(a.focusSquare, FrameLayout.LayoutParams(a.dp(52), a.dp(52)))
        (a.focusSquare.background as? android.graphics.drawable.GradientDrawable)?.cornerRadius = a.dp(10).toFloat()

        addView(a.info, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(side + a.dp(14), a.dp(10), 0, 0)
        })
        addView(recPill, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = a.dp(10)
        })
        addView(hint, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = a.dp(52)
        })

        // Left margin.
        val leftW = side.coerceAtLeast(a.dp(150))
        addView(modeRow.first, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(a.dp(20), a.dp(16), 0, 0)
        })
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            listOf(lookChip, edrChip, adjustChip, resChip).forEach {
                addView(it, LinearLayout.LayoutParams(leftW - a.dp(44), WRAP_CONTENT).apply { bottomMargin = a.dp(10) })
            }
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START).apply {
            marginStart = a.dp(20)
        })
        addView(settingsChip, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(a.dp(20), 0, 0, a.dp(16))
        })

        // Right margin: the EV slider against the image, then the lens column / record / flip.
        addView(a.exposureSlider, FrameLayout.LayoutParams(sliderW, (screenH * 0.66f).toInt(), Gravity.CENTER_VERTICAL or Gravity.END).apply {
            marginEnd = rightColumnW
        })
        addView(recordButton, FrameLayout.LayoutParams(recSize, recSize, Gravity.TOP or Gravity.END).apply {
            marginEnd = (rightColumnW - recSize) / 2
            topMargin = recTop
        })
        addView(lensColumn, FrameLayout.LayoutParams(rightColumnW, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = lensTop
        })
        addView(flipButton, FrameLayout.LayoutParams(a.dp(46), a.dp(46), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, (rightColumnW - a.dp(46)) / 2, a.dp(18))
        })
        // Under the EV slider (which ends 17 % of the height above the bottom), level with flip.
        addView(mirrorButton, FrameLayout.LayoutParams(a.dp(40), a.dp(40), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, rightColumnW + (sliderW - a.dp(40)) / 2, a.dp(21))
        })

        // Bottom of the image (Pro), with its panels above it.
        addView(proBar, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = a.dp(10)
        })
        val aboveBar = a.dp(76)
        for (p in listOf(aePanel, isoPanel, shutterPanel, wbPanel, fpsPanel)) {
            p.visibility = View.GONE
            addView(p, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = aboveBar })
        }
        addView(a.focusBar, FrameLayout.LayoutParams((imageW * 0.7f).toInt(), WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = aboveBar
        })
        addView(a.priorityPanel, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = aboveBar + a.dp(130)
        })
        addView(adjustPanel, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = aboveBar
        })
        addView(settingsSheet, FrameLayout.LayoutParams(minOf(a.dp(560), imageW - a.dp(24)), MATCH_PARENT, Gravity.CENTER_HORIZONTAL).apply {
            setMargins(0, a.dp(12), 0, a.dp(12))
        })
        // Before every UI frame: tell the renderer where the glass is, for the blur behind it.
        viewTreeObserver.addOnPreDrawListener {
            publishGlass()
            true
        }
    }

    // ---- Backdrop blur ----

    private var publishedGlass: List<GlassRect> = emptyList()
    private val loc = IntArray(2)
    private val origin = IntArray(2)

    /** Every visible glass shape (capsules, the record button, the EV slider) in surface pixels. */
    private fun publishGlass() {
        val out = ArrayList<GlassRect>(48)
        a.surfaceView.getLocationInWindow(origin)
        collectGlass(root, 1f, RectF(-1e6f, -1e6f, 1e6f, 1e6f), false, out)
        val same = out.size == publishedGlass.size && out.indices.all { i ->
            val p = out[i]
            val q = publishedGlass[i]
            p.left == q.left && p.top == q.top && p.right == q.right && p.bottom == q.bottom && p.alpha == q.alpha &&
                p.clip == q.clip
        }
        if (same) return
        publishedGlass = out
        a.setGlassRects(out)
    }

    /**
     * [clip]: what the view's ancestors let it show, in surface pixels. The views clip themselves;
     * the GL backdrop has to be told, or a settings row scrolled past the sheet's top still drew
     * its glass over the image. [onGlass]: an ancestor is glass already; its buttons sit on that
     * pane as tinted shapes instead of each opening its own clear window through the frost (owner:
     * the settings pills showed the scene sharper than the sheet around them).
     */
    private fun collectGlass(v: View, parentAlpha: Float, clip: RectF, onGlass: Boolean, out: MutableList<GlassRect>) {
        if (v.visibility != View.VISIBLE) return
        val alpha = parentAlpha * v.alpha
        if (alpha < 0.02f) return
        val box: android.graphics.RectF?
        val radius: Float
        when {
            v === recordButton -> { box = recordButton.glassBox(); radius = box.width() / 2f }
            v === a.exposureSlider -> { box = a.exposureSlider.glassBox(); radius = 30f }
            v.background is GlassDrawable -> { box = android.graphics.RectF(0f, 0f, v.width.toFloat(), v.height.toFloat()); radius = (v.background as GlassDrawable).radius }
            else -> { box = null; radius = 0f }
        }
        if (box != null && v.width > 0 && !onGlass) {
            v.getLocationInWindow(loc)
            val x = (loc[0] - origin[0]).toFloat()
            val y = (loc[1] - origin[1]).toFloat()
            val r = GlassRect(x + box.left * v.scaleX, y + box.top * v.scaleY, x + box.right * v.scaleX, y + box.bottom * v.scaleY,
                radius * v.scaleX, alpha, clip)
            if (r.right > clip.left && r.left < clip.right && r.bottom > clip.top && r.top < clip.bottom) out += r
        }
        if (v !is android.view.ViewGroup) return
        var inner = clip
        if (v.clipChildren) {
            v.getLocationInWindow(loc)
            val x = (loc[0] - origin[0]).toFloat()
            val y = (loc[1] - origin[1]).toFloat()
            val pad = v.clipToPadding
            inner = RectF(x + if (pad) v.paddingLeft else 0, y + if (pad) v.paddingTop else 0,
                x + v.width - if (pad) v.paddingRight else 0, y + v.height - if (pad) v.paddingBottom else 0)
            if (!inner.intersect(clip)) return
        }
        for (i in 0 until v.childCount) collectGlass(v.getChildAt(i), alpha, inner, onGlass || box != null, out)
    }

    // ---- Building blocks ----

    /** A glass chip with a small caption over its value (pro bar, look). */
    private fun captionChip(caption: String, onClick: (View) -> Unit): Button {
        val b = kit.button("") { }
        b.setOnClickListener { onClick(it) }
        b.tag = caption
        b.gravity = Gravity.CENTER
        b.setLines(2)
        b.setPadding(a.dp(14), a.dp(6), a.dp(14), a.dp(6))
        b.ellipsize = TextUtils.TruncateAt.END
        return b
    }

    private fun setCaption(b: Button, value: String, auto: Boolean = false) {
        val caption = b.tag as String
        val text = android.text.SpannableStringBuilder()
        text.append(caption + if (auto) " · A" else "")
        text.setSpan(android.text.style.RelativeSizeSpan(0.72f), 0, text.length, 0)
        text.setSpan(android.text.style.ForegroundColorSpan(if (auto) Glass.ACCENT else Glass.TEXT_DIM), 0, text.length, 0)
        text.append("\n")
        val start = text.length
        text.append(value)
        text.setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, text.length, 0)
        if (b.text.toString() != text.toString()) b.text = text
    }

    private fun roundChip(text: String, onClick: () -> Unit): Button = kit.button(text, onClick).apply {
        background = GlassDrawable(a.dp(23).toFloat())
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
        textSize = 13f
    }

    private fun title(text: String) = kit.label(text, 14f).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(0, 0, 0, a.dp(6))
    }

    private fun panel(vararg children: View): LinearLayout = kit.panel().apply {
        children.forEach {
            // Keep a size the child already asked for (the dials: a custom view would fill the screen).
            val lp = (it.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            addView(it, lp.apply { bottomMargin = a.dp(6) })
        }
    }

    private fun dialHeader(name: String, auto: Button, titleView: TextView = title(name)) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(titleView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(kit.label("drag", 11f, dim = true).apply { setPadding(0, 0, a.dp(10), 0) })
        addView(auto)
    }

    private fun ValueDial.sized(): View = this.apply { layoutParams = LinearLayout.LayoutParams(dialWidth, a.dp(64)) }

    private fun View.fullWidth(): View = this.apply { layoutParams = LinearLayout.LayoutParams(dialWidth, WRAP_CONTENT) }

    private fun sliderRow(label: TextView, bar: SeekBar, width: Int = a.dp(176), onChange: (Int) -> Unit) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })
        addView(label)
        addView(bar, LinearLayout.LayoutParams(width, WRAP_CONTENT))
    }

    private fun toggle(panel: View) {
        val show = panel.visibility != View.VISIBLE
        panels.forEach { if (it !== panel) it.visibility = View.GONE }
        if (show && panel === adjustPanel) a.priorityPanelOpen = false
        panel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) kit.reveal(panel)
        update()
    }

    private fun buildSettings(): View {
        val content = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun row(label: String, pro: Boolean, control: View): View {
            val r = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, a.dp(5), 0, a.dp(5))
                addView(kit.label(label, 13f).apply { minWidth = a.dp(110) })
                addView(control)
            }
            content.addView(r)
            if (pro) proSettings += r
            return r
        }
        fun <T> choice(label: String, pro: Boolean, options: List<Pair<String, T>>, current: () -> T, pick: (T) -> Unit) {
            val (seg, mark) = kit.segmented(options.map { it.first }) { i -> pick(options[i].second) }
            row(label, pro, seg)
            settingsRows += { mark(options.indexOfFirst { it.second == current() }) }
        }
        // Fixed above the rows, which scroll beneath it.
        val header = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title("Settings"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(kit.button("✕") { toggle(settingsSheet) })
        }
        choice("Mode", false, listOf("Simple" to true, "Pro" to false), { a.simple }) { a.setSimple(it) }
        choice("Resolution", false, listOf("4K open gate" to false, "2K superpixel" to true), { a.superpixel }) { a.setSuperpixel(it) }
        choice("Frame", false, CameraActivity.FrameAspect.entries.map { it.label to it }, { a.aspect }) { a.setAspect(it) }
        choice("Frame rate", false, listOf("24" to 24, "25" to 25, "30" to 30), { a.capture.fps }) { a.setFps(it) }
        choice("Codec", true, VideoCodec.entries.map { it.label to it }, { a.codec }) { a.setCodec(it) }
        choice("Bitrate", true, listOf("50" to 50, "100" to 100, "150 Mbps" to 150), { a.bitrateMbps }) { a.setBitrate(it) }
        choice("Record", true, listOf("Look" to true, "Log" to false), { a.bakeLut }) { a.setBake(it) }
        choice("Log profile", true, LogProfile.entries.map { it.label to it }, { a.profile }) { a.setProfile(it) }
        choice("Clean-up", true, listOf("Off" to 0, "Pixels" to 1, "+ Colour" to 2, "+ Colour+" to 3), { a.cleanup }) { a.setCleanup(it) }
        choice("Audio", false, listOf("On" to true, "Off" to false), { a.audioOn }) { if (it != a.audioOn) a.toggleAudio() }
        choice("Layout", false, listOf("Glass" to false, "Classic" to true), { a.classicUi }) { a.setClassicUi(it) }
        fun buttons(pro: Boolean, vararg items: Pair<String, () -> Unit>) {
            val r = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, a.dp(8), 0, 0)
                items.forEach { (t, f) ->
                    addView(kit.button(t) { f() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = a.dp(8) })
                }
            }
            content.addView(r)
            if (pro) proSettings += r
        }
        buttons(true, "Import .cube LUT…" to { a.pickLut() }, "Edit look / make LUT…" to { a.openLookEditor() })
        buttons(false, "Lenses…" to { a.showLensMenu(settingsChip) }, "Rescan lenses" to { toggle(settingsSheet); a.rescanLenses() },
            "Send diagnostics…" to { a.sendDiagnostics() })
        content.addView(kit.label("AuthRec ${a.packageManager.getPackageInfo(a.packageName, 0).versionName}", 11f, dim = true).apply {
            setPadding(0, a.dp(12), 0, a.dp(10))
        })
        // Rows fade out towards the sheet's edges instead of being cut by a straight line just
        // under its rounded rim (owner: scrolled rows looked clipped, poking past the corners).
        val rows = ScrollView(a).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(a.dp(28))
            addView(content)
        }
        return LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassDrawable(a.dp(24).toFloat(), solid = true).apply { baseColor = 0x99101418.toInt() }
            setPadding(a.dp(20), a.dp(14), a.dp(20), a.dp(8))
            visibility = View.GONE
            addView(header)
            addView(rows, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
    }

    // ---- Lenses ----

    private fun lensChipText(label: String) = label.substringBefore(" ").replace("x", "×")

    private fun rebuildLenses() {
        val order = a.lensOrder().filter { !a.lenses[it].front }
        val keys = order.map { a.lenses[it].key }
        if (keys == lensKeys && lensChips.size == order.size) return
        lensKeys = keys
        lensColumn.reset()
        lensColumn.removeAllViews()
        lensChips.clear()
        // The chips fit between the top edge and the record button, keeping a clear gap above it so
        // a lens tap can't land on record. A short screen (15 Ultra: ~384 dp tall) or a fourth back
        // lens would squeeze one column below a comfortable size, so then they go two abreast.
        val gap = a.dp(7)
        val room = recTop - a.dp(16) - lensTop
        val n = order.size.coerceAtLeast(1)
        val cols = if (n == 1 || room / n - gap >= a.dp(40)) 1 else 2
        val rows = (n + cols - 1) / cols
        val size = minOf(a.dp(44), room / rows - gap, (rightColumnW - gap) / cols - gap)
        order.chunked(cols).forEach { rowLenses ->
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            rowLenses.forEachIndexed { k, i ->
                val chip = roundChip(lensChipText(a.lenses[i].label)) { if (i != a.lensIndex) a.switchLens(i) }
                chip.setOnLongClickListener { a.showLensMenu(it); true }
                lensChips[i] = chip
                row.addView(chip, LinearLayout.LayoutParams(size, size).apply { if (k > 0) marginStart = gap })
            }
            lensColumn.addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = gap })
        }
        if (order.isEmpty()) {
            lensColumn.addView(kit.button("Lens") { a.showLensMenu(lensColumn) })
        }
    }

    private fun flipCamera() {
        val current = a.lenses.getOrNull(a.lensIndex) ?: return
        val target = if (current.front) {
            a.lenses.indexOfFirst { it.key == lastBack }.takeIf { it >= 0 }
                ?: a.lenses.indexOfFirst { it.label == "1x" }.takeIf { it >= 0 }
                ?: a.lenses.indexOfFirst { !it.front }
        } else {
            a.lenses.indexOfFirst { it.front }
        }
        if (target >= 0 && target != a.lensIndex) a.switchLens(target)
    }

    // ---- State → controls ----

    override fun update() {
        val recording = a.recording
        val busy = recording || a.stopping || a.warmingUp
        recordButton.state = when {
            a.stopping -> RecordButton.State.SAVING
            a.warmingUp -> RecordButton.State.STARTING
            recording -> RecordButton.State.RECORDING
            else -> RecordButton.State.IDLE
        }
        updateRecPill()

        modeRow.second(if (a.simple) 0 else 1)
        modeRow.first.isEnabled = !busy
        for (i in 0 until modeRow.first.childCount) modeRow.first.getChildAt(i).isEnabled = !busy
        setCaption(lookChip, a.currentView()?.label ?: "")
        kit.setOn(edrChip, a.edrOn && a.edrAvailable())
        edrChip.isEnabled = a.edrAvailable()
        edrChip.text = if (a.edrOn) "eDR  on" else "eDR  off"
        kit.setOn(adjustChip, adjustPanel.visibility == View.VISIBLE)
        resChip.text = if (a.superpixel) "2K" else "4K"
        resChip.visibility = if (a.simple) View.VISIBLE else View.GONE
        resChip.isEnabled = !busy
        settingsChip.isEnabled = !busy
        kit.setOn(settingsChip, settingsSheet.visibility == View.VISIBLE)
        if (busy && settingsSheet.visibility == View.VISIBLE) settingsSheet.visibility = View.GONE

        rebuildLenses()
        val current = a.lenses.getOrNull(a.lensIndex)
        if (current != null && !current.front) lastBack = current.key
        lensChips.values.forEach { it.isEnabled = !busy && !a.scanning }
        // The accent pill rides from the old lens to the new one (none on the front camera).
        lensColumn.select(lensChips[a.lensIndex])
        flipButton.visibility = if (a.lenses.any { it.front }) View.VISIBLE else View.GONE
        kit.setOn(flipButton, current?.front == true)
        flipButton.isEnabled = !busy && !a.scanning
        mirrorButton.visibility = if (current?.front == true) View.VISIBLE else View.GONE
        kit.setOn(mirrorButton, a.mirrorFront)
        mirrorButton.isEnabled = !busy

        // Pro bar.
        val pro = !a.simple
        proBar.visibility = if (pro && settingsSheet.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        // The info line sits behind the sheet's rows and showed through its glass.
        a.info.visibility = if (settingsSheet.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
        if (!pro) listOf(aePanel, isoPanel, shutterPanel, wbPanel, fpsPanel).forEach { it.visibility = View.GONE }
        val ae = a.capture.ae
        setCaption(aeChip, when (ae) {
            AeMode.AUTO -> "Auto"
            AeMode.PRIORITY -> "Priority"
            AeMode.LOCKED -> "Locked"
            AeMode.MANUAL -> "Manual"
        })
        aeRow.second(aeModes.indexOf(ae))
        limitsButton.visibility = if (ae == AeMode.PRIORITY) View.VISIBLE else View.GONE
        kit.setOn(limitsButton, a.priorityPanelOpen)
        updateExposureValues()
        setCaption(wbChip, (if (a.capture.awbLock) "Locked" else "Auto") + if (a.wbWarmth != 0 || a.wbTint != 0) " ±" else "")
        wbRow.second(if (a.capture.awbLock) 1 else 0)
        warmthLabel.text = "Warmth %+d".format(a.wbWarmth)
        tintLabel.text = "Tint %+d".format(a.wbTint)
        if (warmthBar.progress != a.wbWarmth + 100) warmthBar.progress = a.wbWarmth + 100
        if (tintBar.progress != a.wbTint + 100) tintBar.progress = a.wbTint + 100
        focusChip.visibility = if (a.hasAfLens()) View.VISIBLE else View.GONE
        setCaption(focusChip, a.afLabel().removePrefix("AF: ").let { if (it == "MF") "Manual" else it })
        kit.setOn(focusChip, a.capture.af == AfMode.MANUAL)
        setCaption(fpsChip, "${a.capture.fps}")
        fpsRow.second(fpsOptions.indexOf(a.capture.fps))
        fpsChip.isEnabled = !busy
        listOf(aeChip to aePanel, isoChip to isoPanel, shutterChip to shutterPanel, wbChip to wbPanel, fpsChip to fpsPanel)
            .forEach { (chip, panel) -> if (chip !== focusChip) kit.setOn(chip, panel.visibility == View.VISIBLE) }

        // Adjust.
        curveBox.visibility = if (pro) View.VISIBLE else View.GONE
        curve.set(a.edrHighlights, a.edrShadows)
        curve.active = a.edrOn
        edrSwitch.second(if (a.edrOn) 1 else 0)
        strengthLabel.text = "LUT strength ${a.lutStrength}%"
        saturationLabel.text = "Saturation ${a.saturationPct}%"
        vibranceLabel.text = "Vibrance %+d".format(a.vibrancePct)
        adjWarmthLabel.text = "Warmth %+d".format(a.wbWarmth)
        adjTintLabel.text = "Tint %+d".format(a.wbTint)
        fun sync(bar: SeekBar, p: Int) { if (bar.progress != p) bar.progress = p }
        sync(strengthBar, a.lutStrength)
        sync(saturationBar, a.saturationPct)
        sync(vibranceBar, a.vibrancePct + 100)
        sync(adjWarmthBar, a.wbWarmth + 100)
        sync(adjTintBar, a.wbTint + 100)

        // Settings.
        settingsRows.forEach { it() }
        proSettings.forEach { it.visibility = if (pro) View.VISIBLE else View.GONE }
    }

    /** ISO / shutter chips and dials: the camera's own values while auto exposure runs. */
    private fun updateExposureValues() {
        val manual = a.capture.ae == AeMode.MANUAL
        val meta = a.camera?.latestMeta
        val iso = if (manual) a.capture.iso else meta?.iso ?: a.capture.iso
        val ns = if (manual) a.capture.exposureNs else meta?.exposureNs?.takeIf { it > 0 } ?: a.capture.exposureNs
        val effective = a.lastEffectiveIso
        setCaption(isoChip, "$iso", auto = !manual)
        isoTitle.text = if (effective != null && effective > iso * 1.4) "ISO  ·  ≈$effective with gain" else "ISO"
        setCaption(shutterChip, shutterText(ns), auto = !manual)

        val isoStops = a.isoStops()
        if (isoStops != isoValues) {
            isoValues = isoStops
            isoDial.labels = isoStops.map { "$it" }
        }
        isoDial.show(isoValues.indices.minByOrNull { abs(isoValues[it] - iso) } ?: 0)
        isoDial.auto = !manual
        kit.setOn(isoAuto, !manual)

        val shutterStops = a.shutterStops()
        if (shutterStops != shutterValues) {
            shutterValues = shutterStops
            shutterDial.labels = shutterStops.map { shutterText(it) }
            val half = 1_000_000_000L / (2 * a.capture.fps)
            shutterDial.marked = shutterStops.indices.filter { abs(shutterStops[it] - half) < half / 50 }.toSet()
        }
        shutterDial.show(shutterValues.indices.minByOrNull { abs(shutterValues[it] - ns) } ?: 0)
        shutterDial.auto = !manual
        kit.setOn(shutterAuto, !manual)
    }

    private fun shutterText(ns: Long): String {
        if (ns <= 0) return "–"
        val denom = 1e9 / ns
        return if (denom >= 1.5) "1/${denom.roundToInt()}" else "%.1f s".format(ns / 1e9)
    }

    private val recTick = object : Runnable {
        override fun run() {
            updateRecPill()
            if (a.recording) recPill.postDelayed(this, 500)
        }
    }

    private fun updateRecPill() {
        val on = a.recording
        if (on && recPill.visibility != View.VISIBLE) {
            recPill.visibility = View.VISIBLE
            recPulse?.cancel()
            recPulse = UiKit.pulse(recDot)
            recPill.removeCallbacks(recTick)
            recPill.postDelayed(recTick, 500)
        } else if (!on && recPill.visibility == View.VISIBLE) {
            recPill.visibility = View.GONE
            recPulse?.cancel()
            recPulse = null
        }
        if (on) {
            val secs = (SystemClock.elapsedRealtime() - a.recordStartMs) / 1000
            val dropped = a.droppedFrames
            recTime.text = "%02d:%02d".format(secs / 60, secs % 60) + if (!a.simple && dropped > 0) "  ·  $dropped dropped" else ""
        }
    }

    override fun onStats() {
        if (!a.simple) updateExposureValues()
    }

    override fun dismissPanels(): Boolean {
        var closed = false
        panels.forEach {
            if (it.visibility == View.VISIBLE) {
                it.visibility = View.GONE
                closed = true
            }
        }
        if (closed) update()
        return closed
    }

    override fun showHint(text: String, action: String, onAction: () -> Unit, onClose: () -> Unit) {
        hintText.text = text
        hintAction.text = action
        kit.setOn(hintAction, true)
        hintAction.setOnClickListener {
            onAction()
            closeHint()
        }
        hintClose = onClose
        hint.alpha = 0f
        hint.visibility = View.VISIBLE
        hint.animate().alpha(1f).setDuration(250).start()
        hint.removeCallbacks(autoClose)
        hint.postDelayed(autoClose, 15_000)
    }

    private val autoClose = Runnable { closeHint() }

    private fun closeHint() {
        hint.removeCallbacks(autoClose)
        if (hint.visibility != View.VISIBLE) return
        hint.animate().alpha(0f).setDuration(200).withEndAction { hint.visibility = View.GONE }.start()
        hintClose?.invoke()
        hintClose = null
    }
}

/**
 * The front / back flip symbol: an open circle with an arrowhead, drawn in the middle of [view]
 * in its text colour (the ⟲ glyph sat low and to the right: its font's metrics, not its shape).
 */
private class FlipIcon(private val view: TextView, private val stroke: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val arc = RectF()
    private val head = Path()

    override fun draw(canvas: Canvas) {
        val r = minOf(bounds.width(), bounds.height()) * 0.22f
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        paint.color = view.currentTextColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        arc.set(cx - r, cy - r, cx + r, cy + r)
        // Open at the upper left; the arrowhead on the end that comes round anticlockwise.
        val start = 215f
        val sweep = -290f
        canvas.drawArc(arc, start, sweep, false, paint)
        val end = Math.toRadians((start + sweep).toDouble())
        val px = cx + r * kotlin.math.cos(end).toFloat()
        val py = cy + r * kotlin.math.sin(end).toFloat()
        // Unit tangent in the direction of travel (decreasing angle) and the outward normal.
        val tx = kotlin.math.sin(end).toFloat()
        val ty = -kotlin.math.cos(end).toFloat()
        val nx = kotlin.math.cos(end).toFloat()
        val ny = kotlin.math.sin(end).toFloat()
        val h = stroke * 2.6f
        head.reset()
        head.moveTo(px + tx * h * 0.7f, py + ty * h * 0.7f)
        head.lineTo(px - tx * h * 0.3f + nx * h * 0.6f, py - ty * h * 0.3f + ny * h * 0.6f)
        head.lineTo(px - tx * h * 0.3f - nx * h * 0.6f, py - ty * h * 0.3f - ny * h * 0.6f)
        head.close()
        paint.style = Paint.Style.FILL
        canvas.drawPath(head, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Mirror symbol: two triangles pointing apart across a dashed line, the left one filled. */
private class MirrorIcon(private val view: TextView, private val stroke: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val s = minOf(bounds.width(), bounds.height()) * 0.5f
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val gap = s * 0.14f
        val half = s * 0.36f
        val depth = s * 0.34f
        paint.color = view.currentTextColor
        paint.strokeWidth = stroke
        fun triangle(dir: Float) {
            path.reset()
            path.moveTo(cx + dir * gap, cy - half)
            path.lineTo(cx + dir * gap, cy + half)
            path.lineTo(cx + dir * (gap + depth), cy)
            path.close()
        }
        paint.style = Paint.Style.FILL
        triangle(-1f)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        triangle(1f)
        canvas.drawPath(path, paint)
        // The mirror line, dashed.
        val dash = s * 0.13f
        var y = cy - s * 0.5f
        while (y < cy + s * 0.5f) {
            canvas.drawLine(cx, y, cx, minOf(y + dash, cy + s * 0.5f), paint)
            y += dash * 1.8f
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

