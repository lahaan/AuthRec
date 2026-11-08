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
 * ISO and shutter open a dial to drag through the stops. Photo (under Simple / Pro) has Pro's
 * controls without the video-only ones and makes the record button a shutter. Rarely changed
 * options (codec, bitrate,
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
    /** Views sized to [dialWidth]: narrower when the phone is held upright. */
    private val dialViews = mutableListOf<View>()

    // ---- Right: lenses, record, flip ----
    private val recordButton = RecordButton(a) { a.toggleRecording() }
    private val lensColumn = SelectionLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val flipButton = roundChip("") { flipCamera() }.apply {
        foreground = FlipIcon(this, a.dp(2).toFloat())
        contentDescription = "Front / back camera"
    }
    /** Pause / resume, in the flip button's place while a clip records (flip can't be used then). */
    private val pauseButton = roundChip("") { a.togglePause() }.apply {
        foreground = PauseIcon(this, a.dp(2).toFloat()) { a.paused }
        contentDescription = "Pause recording"
    }
    /** A still from the recording, above Pause while a clip records. */
    private val snapButton = roundChip("") { a.takeSnap() }.apply {
        foreground = SnapIcon(this, a.dp(2).toFloat())
        contentDescription = "Photo from the recording"
    }
    /** Selfie-style mirror, next to the flip button while the front camera is on. */
    private val mirrorButton = roundChip("") { a.setMirrorFront(!a.mirrorFront) }.apply {
        foreground = MirrorIcon(this, a.dp(2).toFloat())
        contentDescription = "Mirror front camera"
    }
    /** Torch, in the mirror button's place on back lenses with a flash unit. */
    private val torchButton = roundChip("") { a.setTorch(!a.capture.torch) }.apply {
        foreground = TorchIcon(this, a.dp(2).toFloat()) { a.capture.torch }
        contentDescription = "Torch"
    }
    private var lensKeys = emptyList<String>()
    private val lensChips = mutableMapOf<Int, Button>()
    /** Back lens to return to from the front camera. */
    private var lastBack: String? = null

    // ---- Left: mode, look, eDR, adjust, settings ----
    // Simple | Pro, and Photo under them (owner: "under Pro/Simple … just 'Photo'"): one pill
    // slides between all three; a third button in the row would reach into the image.
    private val modeButtons = listOf("Simple", "Pro", "Photo").mapIndexed { i, label ->
        kit.button(label) { a.setModeIndex(i) }.apply {
            setPadding(a.dp(12), a.dp(7), a.dp(12), a.dp(7))
            textSize = 12f
        }
    }
    private val modeBox = SelectionLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        // Wrapped explicitly: a vertical LinearLayout's default (match_parent) squeezed the row to
        // Photo's width ("Sim", no Pro).
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(modeButtons[0], LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = a.dp(6) })
            addView(modeButtons[1])
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(modeButtons[2], LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = a.dp(6) })
    }
    private val lookChip = captionChip("LOOK") { toggleLooks() }
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
    /** Exposure, white balance and focus back to automatic (dimmed while they are). */
    private val resetChip = captionChip("RESET") {
        listOf(aePanel, isoPanel, shutterPanel, wbPanel).forEach { it.visibility = View.GONE }
        a.resetCapture()
    }
    private val proBar = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        listOf(aeChip, isoChip, shutterChip, wbChip, focusChip, fpsChip, resetChip).forEach {
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
    private val warmthBar = kit.seekBar(200, a.wbWarmth + 100, neutral = 100)
    private val tintBar = kit.seekBar(200, a.wbTint + 100, neutral = 100)
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
    private val strengthBar = kit.seekBar(100, a.lutStrength, neutral = 100)
    private val saturationBar = kit.seekBar(200, a.saturationPct, neutral = 100)
    private val vibranceBar = kit.seekBar(200, a.vibrancePct + 100, neutral = 100)
    private val adjWarmthBar = kit.seekBar(200, a.wbWarmth + 100, neutral = 100)
    private val adjTintBar = kit.seekBar(200, a.wbTint + 100, neutral = 100)
    private val strengthLabel = kit.label(size = 12f).apply { minWidth = a.dp(96) }
    private val saturationLabel = kit.label(size = 12f).apply { minWidth = a.dp(96) }
    private val vibranceLabel = kit.label(size = 12f).apply { minWidth = a.dp(96) }
    private val adjWarmthLabel = kit.label(size = 12f).apply { minWidth = a.dp(96) }
    private val adjTintLabel = kit.label(size = 12f).apply { minWidth = a.dp(96) }
    // Texture / Clarity / Dehaze (Lightroom's Presence dials): Pro and Photo.
    private val textureBar = kit.seekBar(200, a.photoTexture + 100, neutral = 100)
    private val clarityBar = kit.seekBar(200, a.photoClarity + 100, neutral = 100)
    private val dehazeBar = kit.seekBar(200, a.photoDehaze + 100, neutral = 100)
    private val textureLabel = kit.label(size = 12f).apply { minWidth = a.dp(96) }
    private val clarityLabel = kit.label(size = 12f).apply { minWidth = a.dp(96) }
    private val dehazeLabel = kit.label(size = 12f).apply { minWidth = a.dp(96) }
    private val photoDials = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        addView(sliderRow(textureLabel, textureBar) { a.setPhotoFinish(texture = it - 100) })
        addView(sliderRow(clarityLabel, clarityBar) { a.setPhotoFinish(clarity = it - 100) })
        addView(sliderRow(dehazeLabel, dehazeBar) { a.setPhotoFinish(dehaze = it - 100) })
    }
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
            addView(photoDials)
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(kit.button("Reset") {
                    a.setLookAdjust(100, 100, 0)
                    a.setWbShift(0, 0)
                    a.resetEdrCurve()
                    if (!a.simple) a.setPhotoFinish(0, 0, 0)
                })
                addView(kit.button("Edit look…") { a.openLookEditor() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    marginStart = a.dp(8)
                })
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = a.dp(6) })
        }, LinearLayout.LayoutParams(a.dp(336), WRAP_CONTENT))
    }

    // ---- Look sheet: profiles, looks, LUTs ----
    // In place of the system's grey pop-up list (owner: "the scroll for LUTs/looks looks a bit
    // ehhh", 2026-11-08). Stays open while looks are tried, so they can be compared on the image.
    private val lookRows = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val lookScroll = ScrollView(a).apply {
        isVerticalScrollBarEnabled = false
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(a.dp(28))
        addView(lookRows)
    }
    /** Each pill and whether it's the current choice (re-marked on [update]). */
    private val lookMarks = mutableListOf<Pair<Button, () -> Boolean>>()
    /** What the list was built from: rebuilt when it changes (a LUT imported, a profile saved). */
    private var lookKey: List<Any?> = emptyList()
    private val lookSheet: View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = GlassDrawable(a.dp(24).toFloat(), solid = true).apply { baseColor = 0x99101418.toInt() }
        setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(8))
        visibility = View.GONE
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title("Looks"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(kit.button("✕") { toggle(lookSheet) })
        })
        addView(lookScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    private fun toggleLooks() {
        if (lookSheet.visibility != View.VISIBLE) rebuildLooks(showCurrent = true)
        toggle(lookSheet)
    }

    private fun currentLookKey(): List<Any?> = listOf(System.identityHashCode(a.views), System.identityHashCode(a.profiles), a.simple,
        a.profilesUsable(), (a.currentView() as? CameraActivity.ViewEntry.CubeFile)?.let { it.file.path + a.lutInputOf(it.file) })

    /** [showCurrent]: scroll to the look in use (on opening); otherwise keep the scroll where it was. */
    private fun rebuildLooks(showCurrent: Boolean = false) {
        lookKey = currentLookKey()
        val scrollY = lookScroll.scrollY
        lookRows.removeAllViews()
        lookMarks.clear()
        fun section(name: String) = lookRows.addView(kit.label(name.uppercase(), 11f, dim = true).apply {
            letterSpacing = 0.08f
            setPadding(a.dp(4), a.dp(12), 0, a.dp(6))
        })
        fun note(text: String) = lookRows.addView(kit.label(text, 11f, dim = true).apply { setPadding(a.dp(4), 0, a.dp(4), a.dp(6)) })
        fun pill(text: String, enabled: Boolean = true, current: () -> Boolean, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
            val b = kit.button(text) { onClick() }.apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.45f
                onLong?.let { f -> setOnLongClickListener { f(); true } }
            }
            kit.setOn(b, current())
            lookRows.addView(b, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = a.dp(6) })
            lookMarks += b to current
        }

        section("Profiles")
        val usable = a.profilesUsable()
        a.profiles.forEach { p -> pill(p.name, usable, { a.profileActive(p) }, onLong = { a.promptEditProfile(p) }) { a.applyProfile(p) } }
        note(when {
            a.profiles.isEmpty() -> "The look with its adjustments and dials, the video format and colour NR, under a name."
            !usable -> "Profiles change the format: after the clip."
            else -> "Hold one to rename or delete it."
        })
        lookRows.addView(kit.button("+  Save current as profile") { a.promptSaveProfile() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        val groups = listOf<Pair<String, (CameraActivity.ViewEntry) -> Boolean>>(
            "Looks" to { it is CameraActivity.ViewEntry.LogView || it is CameraActivity.ViewEntry.BuiltIn },
            "Your looks" to { it is CameraActivity.ViewEntry.UserLook },
            "LUTs" to { it is CameraActivity.ViewEntry.CubeFile },
        )
        for ((name, inGroup) in groups) {
            val items = a.views.withIndex().filter { (_, v) -> inGroup(v) && !(a.simple && v is CameraActivity.ViewEntry.LogView) }
            if (items.isEmpty()) continue
            section(name)
            // The section says what they are: no "★ " / "LUT " in front, nor ".cube.txt" behind.
            items.forEach { (i, v) ->
                val name = v.label.removePrefix("★ ").removePrefix("LUT ").replace(Regex("""(\.cube)?(\.txt)?$""", RegexOption.IGNORE_CASE), "")
                pill(name, current = { a.viewIndex == i }) { a.selectView(i) }
            }
        }

        // An imported LUT is built for one log format; ours is converted to it before the lookup.
        (a.currentView() as? CameraActivity.ViewEntry.CubeFile)?.takeIf { !a.simple }?.let { cube ->
            section("This LUT expects")
            val options = listOf<Pair<String, LogProfile?>>("Our log" to null) + LogProfile.entries.map { it.label to it }
            options.chunked(2).forEach { pair ->
                val (seg, mark) = kit.segmented(pair.map { it.first }) { k -> a.setLutInput(cube.file, pair[k].second) }
                mark(pair.indexOfFirst { it.second == a.lutInputOf(cube.file) })
                lookRows.addView(seg, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = a.dp(6) })
            }
        }

        lookRows.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, a.dp(12), 0, a.dp(8))
            if (!a.simple) addView(kit.button("Import .cube…") { a.pickLut() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = a.dp(8) })
            addView(kit.button("Edit look…") { a.openLookEditor() })
        })
        // Once laid out (on opening the sheet is still hidden here).
        lookScroll.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (lookScroll.height == 0) return
                lookScroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val current = lookMarks.lastOrNull { (_, isCurrent) -> isCurrent() }?.first
                if (showCurrent && current != null) lookScroll.scrollTo(0, (current.top - lookScroll.height / 3).coerceAtLeast(0))
                else lookScroll.scrollTo(0, scrollY)
            }
        })
    }

    // ---- Settings sheet ----
    private val settingsRows = mutableListOf<() -> Unit>()
    /** The settings' label + options rows: options beside the label, or under it when upright. */
    private val settingsLines = mutableListOf<LinearLayout>()
    private val proSettings = mutableListOf<View>()
    /** Rows only video uses (hidden in Photo mode), and Photo's own. */
    private val videoRows = mutableListOf<View>()
    private val photoRows = mutableListOf<View>()
    private val settingsSheet: View = buildSettings()

    private val panels: List<View> = listOf(aePanel, isoPanel, shutterPanel, wbPanel, fpsPanel, adjustPanel, settingsSheet, lookSheet)

    override val lensAnchor: View get() = lensColumn
    override val lookAnchor: View get() = lookChip

    /** The left column's chips: stacked beside the image, a row above it when the phone is upright. */
    private val leftColumn = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        listOf(lookChip, edrChip, adjustChip, resChip).forEach { addView(it) }
    }
    /** The Pro bar scrolls sideways where it's wider than the screen (upright). */
    private val proBarScroll = android.widget.HorizontalScrollView(a).apply {
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(a.dp(20))
        addView(proBar)
    }
    /**
     * Every control, in one layer the phone's pose turns as a whole (the image and the focus
     * square stay as they are): laid out for landscape, or for the phone held upright, so text
     * reads the right way up (owner, 2026-11-08: "when phone gets tilted vertical, elements could
     * go vertical (and text readable properly) and be rearranged if needed").
     */
    private val controls = object : FrameLayout(a) {
        /**
         * With a SurfaceView in the window, every view takes the rectangle it draws out of the
         * window's "transparent region", and the system skips the window where none did. Views
         * work that rectangle out from their position and size, not their rotation, so turned
         * upright the controls claimed the wrong places and a strip of them vanished whenever the
         * stats line and Pro bar (which happened to cover it) were hidden (X14, 2026-11-08). The
         * turned layer claims its whole turned bounds instead (it covers the screen).
         */
        override fun gatherTransparentRegion(region: android.graphics.Region?): Boolean {
            if (region == null || rotation == 0f) return super.gatherTransparentRegion(region)
            val box = RectF(0f, 0f, width.toFloat(), height.toFloat())
            matrix.mapRect(box)
            val parentLoc = IntArray(2)
            (parent as? View)?.getLocationInWindow(parentLoc)
            box.offset(left + parentLoc[0].toFloat(), top + parentLoc[1].toFloat())
            region.op(box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt(), android.graphics.Region.Op.DIFFERENCE)
            return true
        }
    }
    /** The pose the controls are laid out for: quarter turns clockwise from the screen's landscape. */
    private var turn = 0
    private val upright get() = turn == 90 || turn == 270

    override val root: View = FrameLayout(a).apply {
        setBackgroundColor(Color.BLACK)
        addView(a.surfaceView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(a.focusSquare, FrameLayout.LayoutParams(a.dp(52), a.dp(52)))
        (a.focusSquare.background as? android.graphics.drawable.GradientDrawable)?.cornerRadius = a.dp(10).toFloat()
        addView(controls, FrameLayout.LayoutParams(screenW, screenH, Gravity.CENTER))
        // Back to front. The REC pill sits in the mode switch's place (locked while recording):
        // over the image it covered Pro's statistics (owner, 2026-10-21).
        listOf(a.info, recPill, hint, modeBox, leftColumn, settingsChip, a.exposureSlider, recordButton, lensColumn,
            flipButton, pauseButton, snapButton, mirrorButton, torchButton, proBarScroll,
            aePanel, isoPanel, shutterPanel, wbPanel, fpsPanel, a.focusBar, a.priorityPanel, adjustPanel, settingsSheet, lookSheet,
        ).forEach { controls.addView(it) }
        listOf(aePanel, isoPanel, shutterPanel, wbPanel, fpsPanel).forEach { it.visibility = View.GONE }
        arrange()
        // Before every UI frame: tell the renderer where the glass is, for the blur behind it.
        viewTreeObserver.addOnPreDrawListener {
            publishGlass()
            true
        }
    }

    /** Where [setTurn] is heading (the layout changes once the controls have faded out). */
    private var targetTurn = 0

    override fun setTurn(turn: Int) {
        if (turn == targetTurn) return
        targetTurn = turn
        // A quick fade rather than a spin: mid-turn the frosted backdrop (drawn square to the
        // screen) wouldn't match the controls.
        controls.animate().cancel()
        controls.animate().alpha(0f).setDuration(90).withEndAction {
            this.turn = targetTurn
            arrange()
            update()
            controls.animate().alpha(1f).setDuration(140).start()
        }.start()
    }

    private fun place(v: View, w: Int, h: Int, gravity: Int, left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) {
        v.layoutParams = FrameLayout.LayoutParams(w, h, gravity).apply { setMargins(left, top, right, bottom) }
    }

    /** Lays the controls out for [turn]: the layer is the screen's landscape, or upright and turned a quarter. */
    private fun arrange() {
        val w = if (upright) screenH else screenW
        val h = if (upright) screenW else screenH
        controls.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        controls.rotation = -turn.toFloat()
        if (upright) arrangeUpright(w, h) else arrangeLandscape()
        lensKeys = emptyList()
        rebuildLenses()
        controls.requestLayout()
    }

    private fun arrangeLandscape() {
        val top = Gravity.TOP
        val bottom = Gravity.BOTTOM
        val start = Gravity.START
        val end = Gravity.END
        val centreH = Gravity.CENTER_HORIZONTAL
        a.info.maxWidth = Int.MAX_VALUE
        place(a.info, WRAP_CONTENT, WRAP_CONTENT, top or start, left = side + a.dp(14), top = a.dp(10))
        place(recPill, WRAP_CONTENT, WRAP_CONTENT, top or start, left = a.dp(20), top = a.dp(16))
        hintText.maxWidth = a.dp(420)
        place(hint, WRAP_CONTENT, WRAP_CONTENT, top or centreH, top = a.dp(52))

        // Left margin.
        val leftW = side.coerceAtLeast(a.dp(150))
        place(modeBox, WRAP_CONTENT, WRAP_CONTENT, top or start, left = a.dp(20), top = a.dp(16))
        leftColumn.orientation = LinearLayout.VERTICAL
        for (i in 0 until leftColumn.childCount) {
            leftColumn.getChildAt(i).layoutParams = LinearLayout.LayoutParams(leftW - a.dp(44), WRAP_CONTENT).apply { bottomMargin = a.dp(10) }
        }
        place(leftColumn, WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL or start, left = a.dp(20))
        place(settingsChip, WRAP_CONTENT, WRAP_CONTENT, bottom or start, left = a.dp(20), bottom = a.dp(16))

        // Right margin: the EV slider against the image, then the lens column / record / flip.
        place(a.exposureSlider, sliderW, (screenH * 0.66f).toInt(), Gravity.CENTER_VERTICAL or end, right = rightColumnW)
        place(recordButton, recSize, recSize, top or end, right = (rightColumnW - recSize) / 2, top = recTop)
        place(lensColumn, rightColumnW, WRAP_CONTENT, top or end, top = lensTop)
        val roundRight = (rightColumnW - a.dp(46)) / 2
        place(flipButton, a.dp(46), a.dp(46), bottom or end, right = roundRight, bottom = a.dp(18))
        place(pauseButton, a.dp(46), a.dp(46), bottom or end, right = roundRight, bottom = a.dp(18))
        place(snapButton, a.dp(46), a.dp(46), bottom or end, right = roundRight, bottom = a.dp(18 + 46 + 14))
        // Under the EV slider (which ends 17 % of the height above the bottom), level with flip.
        place(mirrorButton, a.dp(40), a.dp(40), bottom or end, right = rightColumnW + (sliderW - a.dp(40)) / 2, bottom = a.dp(21))
        place(torchButton, a.dp(40), a.dp(40), bottom or end, right = rightColumnW + (sliderW - a.dp(40)) / 2, bottom = a.dp(21))

        // Bottom of the image (Pro), with its panels above it.
        place(proBarScroll, WRAP_CONTENT, WRAP_CONTENT, bottom or centreH, bottom = a.dp(10))
        val aboveBar = a.dp(76)
        for (p in listOf(aePanel, isoPanel, shutterPanel, wbPanel, fpsPanel)) place(p, WRAP_CONTENT, WRAP_CONTENT, bottom or centreH, bottom = aboveBar)
        dialViews.forEach { it.layoutParams = (it.layoutParams as LinearLayout.LayoutParams).apply { width = dialWidth } }
        place(a.focusBar, (imageW * 0.7f).toInt(), WRAP_CONTENT, bottom or centreH, bottom = aboveBar)
        place(a.priorityPanel, WRAP_CONTENT, WRAP_CONTENT, bottom or centreH, bottom = aboveBar + a.dp(130))
        adjustPanel.orientation = LinearLayout.HORIZONTAL
        curveBox.layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = a.dp(14) }
        place(adjustPanel, WRAP_CONTENT, WRAP_CONTENT, bottom or centreH, bottom = aboveBar)
        place(settingsSheet, minOf(a.dp(560), imageW - a.dp(24)), MATCH_PARENT, centreH, top = a.dp(12), bottom = a.dp(12))
        settingsLines.forEach { line ->
            line.orientation = LinearLayout.HORIZONTAL
            line.gravity = Gravity.CENTER_VERTICAL
            (line.getChildAt(1).layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0
        }
        // Over the image's left side, beside the column with the Look chip (which stays lit).
        place(lookSheet, minOf(a.dp(320), imageW - a.dp(24)), MATCH_PARENT, start, left = side + a.dp(8), top = a.dp(12), bottom = a.dp(12))
    }

    /**
     * The phone upright: the image fills the width between two bands. Above it the mode switch
     * and settings, with look / eDR / adjust in a row; below it the lens chips over the record
     * button, flip on its right, torch / mirror on its left. The EV slider runs down the image's
     * right edge, the Pro bar along its bottom (scrolling where it's too wide), panels above that.
     */
    private fun arrangeUpright(w: Int, h: Int) {
        val top = Gravity.TOP
        val bottom = Gravity.BOTTOM
        val start = Gravity.START
        val end = Gravity.END
        val centreH = Gravity.CENTER_HORIZONTAL
        val imgTop = side
        val imgBottom = side + imageW
        val below = h - imgBottom  // the band under the image
        a.info.maxWidth = w - a.dp(28)
        place(a.info, WRAP_CONTENT, WRAP_CONTENT, top or start, left = a.dp(14), top = imgTop + a.dp(10))
        place(recPill, WRAP_CONTENT, WRAP_CONTENT, top or start, left = a.dp(16), top = a.dp(14))
        hintText.maxWidth = w - a.dp(140)
        place(hint, WRAP_CONTENT, WRAP_CONTENT, top or centreH, top = imgTop + a.dp(52))

        place(modeBox, WRAP_CONTENT, WRAP_CONTENT, top or start, left = a.dp(16), top = a.dp(14))
        place(settingsChip, WRAP_CONTENT, WRAP_CONTENT, top or end, right = a.dp(16), top = a.dp(14))
        leftColumn.orientation = LinearLayout.HORIZONTAL
        for (i in 0 until leftColumn.childCount) {
            val chip = leftColumn.getChildAt(i)
            leftColumn.getChildAt(i).layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, if (chip === lookChip) 1.6f else 1f).apply {
                if (i < leftColumn.childCount - 1) marginEnd = a.dp(8)
            }
        }
        place(leftColumn, w - a.dp(32), WRAP_CONTENT, top or start, left = a.dp(16), top = (imgTop - a.dp(62)).coerceAtLeast(a.dp(100)))

        val recCentre = imgBottom + below / 2 + a.dp(10)
        place(recordButton, recSize, recSize, top or centreH, top = recCentre - recSize / 2)
        place(lensColumn, WRAP_CONTENT, WRAP_CONTENT, top or centreH, top = imgBottom + a.dp(10))
        val beside = w / 2 + recSize / 2  // right of the record button
        val roundLeft = beside + (w - beside - a.dp(46)) / 2
        place(flipButton, a.dp(46), a.dp(46), top or start, left = roundLeft, top = recCentre - a.dp(23))
        place(pauseButton, a.dp(46), a.dp(46), top or start, left = roundLeft, top = recCentre - a.dp(23))
        place(snapButton, a.dp(46), a.dp(46), top or start, left = roundLeft, top = recCentre - a.dp(23 + 46 + 14))
        val smallLeft = (w / 2 - recSize / 2 - a.dp(40)) / 2
        place(mirrorButton, a.dp(40), a.dp(40), top or start, left = smallLeft, top = recCentre - a.dp(20))
        place(torchButton, a.dp(40), a.dp(40), top or start, left = smallLeft, top = recCentre - a.dp(20))

        val sliderH = (imageW * 0.6f).toInt()
        place(a.exposureSlider, sliderW, sliderH, top or end, top = imgTop + (imageW - sliderH) / 2 - a.dp(30))
        place(proBarScroll, WRAP_CONTENT, WRAP_CONTENT, bottom or centreH, left = a.dp(8), right = a.dp(8), bottom = below + a.dp(10))
        val aboveBar = below + a.dp(76)
        for (p in listOf(aePanel, isoPanel, shutterPanel, wbPanel, fpsPanel)) place(p, WRAP_CONTENT, WRAP_CONTENT, bottom or centreH, bottom = aboveBar)
        dialViews.forEach { it.layoutParams = (it.layoutParams as LinearLayout.LayoutParams).apply { width = w - a.dp(64) } }
        place(a.focusBar, (w * 0.88f).toInt(), WRAP_CONTENT, bottom or centreH, bottom = aboveBar)
        place(a.priorityPanel, WRAP_CONTENT, WRAP_CONTENT, bottom or centreH, bottom = aboveBar + a.dp(130))
        // The curve over the sliders: side by side it's wider than the phone.
        adjustPanel.orientation = LinearLayout.VERTICAL
        curveBox.layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = a.dp(10) }
        place(adjustPanel, WRAP_CONTENT, WRAP_CONTENT, bottom or centreH, bottom = aboveBar)
        // Both sheets over the image only: across the bands they covered the controls there.
        place(settingsSheet, w - a.dp(24), imageW, top or centreH, top = imgTop)
        settingsLines.forEach { line ->
            line.orientation = LinearLayout.VERTICAL
            line.gravity = Gravity.START
            (line.getChildAt(1).layoutParams as? LinearLayout.LayoutParams)?.topMargin = a.dp(4)
        }
        place(lookSheet, w - a.dp(24), imageW, top or centreH, top = imgTop)
    }

    // ---- Backdrop blur ----

    private var publishedGlass: List<GlassRect> = emptyList()
    private val toScreen = android.graphics.Matrix()
    private val screenToSurface = android.graphics.Matrix()

    /**
     * [r], in [v]'s own pixels, as a rectangle in the surface's: through every transform on the
     * way (the controls' layer turned a quarter when the phone is upright, a panel's grow-in).
     * Leaves [toScreen] holding the transform (for radii).
     */
    private fun toSurface(v: View, r: RectF): RectF {
        toScreen.reset()
        v.transformMatrixToGlobal(toScreen)
        toScreen.postConcat(screenToSurface)
        return RectF(r).also { toScreen.mapRect(it) }
    }

    /** Every visible glass shape (capsules, the record button, the EV slider) in surface pixels. */
    private fun publishGlass() {
        val out = ArrayList<GlassRect>(48)
        screenToSurface.reset()
        a.surfaceView.transformMatrixToGlobal(screenToSurface)
        screenToSurface.invert(screenToSurface)
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
            val b = toSurface(v, box)
            val r = GlassRect(b.left, b.top, b.right, b.bottom, toScreen.mapRadius(radius), alpha, clip)
            if (r.right > clip.left && r.left < clip.right && r.bottom > clip.top && r.top < clip.bottom) out += r
        }
        if (v !is android.view.ViewGroup) return
        var inner = clip
        if (v.clipChildren) {
            val pad = v.clipToPadding
            inner = toSurface(v, RectF((if (pad) v.paddingLeft else 0).toFloat(), (if (pad) v.paddingTop else 0).toFloat(),
                (v.width - if (pad) v.paddingRight else 0).toFloat(), (v.height - if (pad) v.paddingBottom else 0).toFloat()))
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

    private fun ValueDial.sized(): View = this.apply { layoutParams = LinearLayout.LayoutParams(dialWidth, a.dp(64)); dialViews += this }

    private fun View.fullWidth(): View = this.apply { layoutParams = LinearLayout.LayoutParams(dialWidth, WRAP_CONTENT); dialViews += this }

    private fun sliderRow(label: TextView, bar: SeekBar, width: Int = a.dp(232), onChange: (Int) -> Unit) = LinearLayout(a).apply {
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
            settingsLines += r
            content.addView(r)
            if (pro) proSettings += r
            return r
        }
        /** [offered]: which options show right now (the rest hide), e.g. bitrates per resolution. */
        /** [offered]: options shown; [usable]: options shown but greyed out where this phone can't. */
        fun <T> choice(label: String, pro: Boolean, options: List<Pair<String, T>>, current: () -> T,
                       offered: ((T) -> Boolean)? = null, usable: ((T) -> Boolean)? = null, note: TextView? = null,
                       pick: (T) -> Unit): View {
            val (seg, mark) = kit.segmented(options.map { it.first }) { i -> pick(options[i].second) }
            val r = row(label, pro, seg)
            // Its own visibility (shown only when it has something to say), so not in proSettings.
            note?.let { content.addView(it) }
            settingsRows += {
                offered?.let { show -> options.forEachIndexed { i, o -> seg.getChildAt(i).visibility = if (show(o.second)) View.VISIBLE else View.GONE } }
                usable?.let { can ->
                    options.forEachIndexed { i, o ->
                        seg.getChildAt(i).isEnabled = can(o.second)
                        seg.getChildAt(i).alpha = if (can(o.second)) 1f else 0.4f
                    }
                }
                mark(options.indexOfFirst { it.second == current() })
            }
            return r
        }
        // Fixed above the rows, which scroll beneath it.
        val header = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title("Settings"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(kit.button("✕") { toggle(settingsSheet) })
        }
        choice("Mode", false, listOf("Simple" to 0, "Pro" to 1, "Photo" to 2), { a.modeIndex }) { a.setModeIndex(it) }
        photoRows += choice("Photo", false, listOf("JPEG" to false, "JPEG + RAW" to true), { a.photoRaw }) { a.setPhotoRaw(it) }
        val resNote = kit.label("", 11f, dim = true)
        videoRows += resNote
        videoRows += choice("Resolution", false, listOf("4K open gate" to false, "2K superpixel" to true), {
            val limit = a.fourKLimit()
            resNote.text = limit?.let { "4K records at ${it.first}×${it.second} on this phone (its video encoder's limit)." } ?: ""
            resNote.visibility = if (resNote.text.isEmpty()) View.GONE else View.VISIBLE
            a.superpixel
        }, note = resNote) { a.setSuperpixel(it) }
        choice("Frame", false, CameraActivity.FrameAspect.entries.map { it.label to it }, { a.aspect }) { a.setAspect(it) }
        videoRows += choice("Frame rate", false, listOf("24" to 24, "25" to 25, "30" to 30), { a.capture.fps }) { a.setFps(it) }
        val codecNote = kit.label("", 11f, dim = true)
        videoRows += codecNote
        videoRows += choice("Codec", true, VideoCodec.entries.map { it.label to it }, {
            codecNote.text = if (a.codec != a.effectiveCodec()) "This phone can't record 10-bit: it records ${a.effectiveCodec().label}." else ""
            codecNote.visibility = if (codecNote.text.isEmpty() || a.simple) View.GONE else View.VISIBLE
            a.effectiveCodec()
        }, usable = { a.codecOffered(it) }, note = codecNote) { a.setCodec(it) }
        videoRows += choice("Bitrate (Mbps)", true, (CameraActivity.BITRATES_2K + CameraActivity.BITRATES_4K).distinct().sorted().map { "$it" to it },
            { a.bitrateMbps }, offered = { it in a.bitrateOptions() }) { a.setBitrate(it) }
        videoRows += choice("Record", true, listOf("Look" to true, "Log" to false), { a.bakeLut }) { a.setBake(it) }
        choice("Log profile", true, LogProfile.entries.map { it.label to it }, { a.profile }) { a.setProfile(it) }
        // Photos always get the full clean-up (Renderer.takePhoto).
        videoRows += choice("Clean-up", true, listOf("Off" to 0, "Pixels" to 1, "+ Colour" to 2, "+ Colour+" to 3), { a.cleanup }) { a.setCleanup(it) }
        // Quality: the history checked against each pixel's neighbours (best against trails, the
        // GPU's heaviest pass gets ~7 ms more at 4K); Fast: against the pixel's own noise.
        videoRows += choice("Colour NR", true, listOf("Quality" to false, "Fast" to true), { a.nrFast }) { a.setNrFast(it) }
        videoRows += choice("Audio", false, listOf("On" to true, "Off" to false), { a.audioOn }) { if (it != a.audioOn) a.toggleAudio() }
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
        buttons(false, "Recordings…" to { a.openRecordings() }, "About & licences" to { com.authrec.About.show(a) })
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
        // Upright: one row over the record button.
        lensColumn.orientation = if (upright) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (upright) {
            order.forEachIndexed { k, i ->
                val chip = roundChip(lensChipText(a.lenses[i].label)) { if (i != a.lensIndex) a.switchLens(i) }
                chip.setOnLongClickListener { a.showLensMenu(it); true }
                lensChips[i] = chip
                lensColumn.addView(chip, LinearLayout.LayoutParams(a.dp(44), a.dp(44)).apply { if (k > 0) marginStart = a.dp(10) })
            }
            if (order.isEmpty()) lensColumn.addView(kit.button("Lens") { a.showLensMenu(lensColumn) })
            return
        }
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
        val busy = recording || a.stopping || a.warmingUp || a.photoBusy
        recordButton.photo = a.photo
        recordButton.state = when {
            a.stopping || a.photoBusy -> RecordButton.State.SAVING
            a.warmingUp -> RecordButton.State.STARTING
            recording -> RecordButton.State.RECORDING
            else -> RecordButton.State.IDLE
        }
        updateRecPill()

        modeBox.select(modeButtons[a.modeIndex])
        modeBox.visibility = if (recording) View.INVISIBLE else View.VISIBLE // the REC pill sits there
        modeButtons.forEach { it.isEnabled = !busy }
        setCaption(lookChip, a.currentView()?.label ?: "")
        kit.setOn(lookChip, lookSheet.visibility == View.VISIBLE)
        if (lookSheet.visibility == View.VISIBLE) {
            if (currentLookKey() != lookKey) rebuildLooks()
            lookMarks.forEach { (b, current) -> kit.setOn(b, current()) }
        }
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
        flipButton.visibility = if (a.lenses.any { it.front } && !recording) View.VISIBLE else View.GONE
        kit.setOn(flipButton, current?.front == true)
        pauseButton.visibility = if (recording) View.VISIBLE else View.GONE
        pauseButton.isEnabled = !a.stopping
        kit.setOn(pauseButton, a.paused)
        pauseButton.contentDescription = if (a.paused) "Resume recording" else "Pause recording"
        pauseButton.invalidate()
        snapButton.visibility = if (recording && !a.warmingUp) View.VISIBLE else View.GONE
        snapButton.isEnabled = !a.snapBusy && !a.stopping
        snapButton.alpha = if (snapButton.isEnabled) 1f else 0.5f
        flipButton.isEnabled = !busy && !a.scanning
        mirrorButton.visibility = if (current?.front == true) View.VISIBLE else View.GONE
        kit.setOn(mirrorButton, a.mirrorFront)
        mirrorButton.isEnabled = !busy
        torchButton.visibility = if (current?.front != true && a.torchAvailable()) View.VISIBLE else View.GONE
        kit.setOn(torchButton, a.capture.torch)
        torchButton.contentDescription = if (a.capture.torch) "Torch on" else "Torch off"
        torchButton.invalidate()

        // Pro bar.
        val pro = !a.simple
        val sheetOpen = settingsSheet.visibility == View.VISIBLE || lookSheet.visibility == View.VISIBLE
        proBar.visibility = if (pro && !sheetOpen) View.VISIBLE else View.GONE
        // The info line sits behind the sheet's rows and showed through its glass.
        // (Upright, Adjust reaches the top of the image, where the stats line is.)
        a.info.visibility = if (sheetOpen || (upright && adjustPanel.visibility == View.VISIBLE)) View.INVISIBLE else View.VISIBLE
        // Upright the EV slider runs down the image, where the panels open.
        a.exposureSlider.visibility = if (upright && panels.any { it.visibility == View.VISIBLE }) View.INVISIBLE else View.VISIBLE
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
        fpsChip.visibility = if (a.photo) View.GONE else View.VISIBLE
        fpsRow.second(fpsOptions.indexOf(a.capture.fps))
        fpsChip.isEnabled = !busy
        setCaption(resetChip, "All auto")
        resetChip.isEnabled = !a.captureAtDefaults()
        resetChip.alpha = if (resetChip.isEnabled) 1f else 0.45f
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
        photoDials.visibility = if (a.simple) View.GONE else View.VISIBLE
        textureLabel.text = "Texture %+d".format(a.photoTexture)
        clarityLabel.text = "Clarity %+d".format(a.photoClarity)
        dehazeLabel.text = "Dehaze %+d".format(a.photoDehaze)
        fun sync(bar: SeekBar, p: Int) { if (bar.progress != p) bar.progress = p }
        sync(strengthBar, a.lutStrength)
        sync(saturationBar, a.saturationPct)
        sync(vibranceBar, a.vibrancePct + 100)
        sync(adjWarmthBar, a.wbWarmth + 100)
        sync(adjTintBar, a.wbTint + 100)
        sync(textureBar, a.photoTexture + 100)
        sync(clarityBar, a.photoClarity + 100)
        sync(dehazeBar, a.photoDehaze + 100)

        // Settings.
        settingsRows.forEach { it() }
        proSettings.forEach { it.visibility = if (pro) View.VISIBLE else View.GONE }
        // After the rows' own updates (the resolution note shows itself when it has something to say).
        if (a.photo) videoRows.forEach { it.visibility = View.GONE }
        else videoRows.forEach { if (it is LinearLayout && it !in proSettings) it.visibility = View.VISIBLE }
        photoRows.forEach { it.visibility = if (a.photo) View.VISIBLE else View.GONE }
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
            recDot.alpha = 1f
        }
        if (on) {
            // Just the time: Pro's info line has the frames, size and drops.
            val secs = a.recordedMs() / 1000
            recTime.text = (if (a.paused) "PAUSED  " else "") + "%02d:%02d".format(secs / 60, secs % 60)
            // A paused clip's dot stops pulsing and dims.
            if (a.paused && recPulse != null) {
                recPulse?.cancel()
                recPulse = null
                recDot.alpha = 0.4f
            } else if (!a.paused && recPulse == null) {
                recDot.alpha = 1f
                recPulse = UiKit.pulse(recDot)
            }
        }
    }

    override fun onStats() {
        if (!a.simple) updateExposureValues()
    }

    override fun openForTest(name: String) {
        when (name) {
            "settings" -> settingsSheet
            "looks" -> lookSheet.also { rebuildLooks(showCurrent = true) }
            "adjust" -> adjustPanel
            "ae" -> aePanel
            "iso" -> isoPanel
            "shutter" -> shutterPanel
            "wb" -> wbPanel
            "fps" -> fpsPanel
            else -> null
        }?.let { if (it.visibility != View.VISIBLE) toggle(it) }
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

/** Pause symbol (two bars) while recording, a play triangle while paused ([paused]). */
private class PauseIcon(private val view: TextView, private val stroke: Float, private val paused: () -> Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val s = minOf(bounds.width(), bounds.height()) * 0.5f
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        paint.color = view.currentTextColor
        paint.style = Paint.Style.FILL
        paint.strokeWidth = stroke
        if (paused()) {
            // Optically centred: a triangle's weight sits left of its box centre.
            val h = s * 0.42f
            path.reset()
            path.moveTo(cx - h * 0.7f, cy - h)
            path.lineTo(cx - h * 0.7f, cy + h)
            path.lineTo(cx + h * 1.0f, cy)
            path.close()
            canvas.drawPath(path, paint)
        } else {
            val h = s * 0.38f
            val w = s * 0.15f
            val gap = s * 0.13f
            canvas.drawRoundRect(cx - gap - w, cy - h, cx - gap, cy + h, w * 0.3f, w * 0.3f, paint)
            canvas.drawRoundRect(cx + gap, cy - h, cx + gap + w, cy + h, w * 0.3f, w * 0.3f, paint)
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

/** A lightning bolt in the button's text colour, filled while the torch is on. */
/** A camera: a rounded body with a lens (the still-while-recording button). */
private class SnapIcon(private val view: TextView, private val stroke: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val rect = android.graphics.RectF()

    override fun draw(canvas: Canvas) {
        val s = minOf(bounds.width(), bounds.height()) * 0.5f
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        paint.color = view.currentTextColor
        paint.strokeWidth = stroke
        rect.set(cx - s * 0.5f, cy - s * 0.3f, cx + s * 0.5f, cy + s * 0.36f)
        canvas.drawRoundRect(rect, s * 0.1f, s * 0.1f, paint)
        rect.set(cx - s * 0.18f, cy - s * 0.42f, cx + s * 0.06f, cy - s * 0.3f)
        canvas.drawRect(rect, paint)
        canvas.drawCircle(cx, cy + s * 0.03f, s * 0.17f, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

private class TorchIcon(private val view: TextView, private val stroke: Float, private val on: () -> Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val s = minOf(bounds.width(), bounds.height()) * 0.5f
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        paint.color = view.currentTextColor
        paint.strokeWidth = stroke
        paint.style = if (on()) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        path.reset()
        path.moveTo(cx + s * 0.10f, cy - s * 0.52f)
        path.lineTo(cx - s * 0.26f, cy + s * 0.06f)
        path.lineTo(cx - s * 0.02f, cy + s * 0.06f)
        path.lineTo(cx - s * 0.12f, cy + s * 0.52f)
        path.lineTo(cx + s * 0.26f, cy - s * 0.08f)
        path.lineTo(cx + s * 0.02f, cy - s * 0.08f)
        path.close()
        canvas.drawPath(path, paint)
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
