package com.authrec.ui

import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import com.authrec.CameraActivity
import com.authrec.camera.AeMode
import kotlin.math.abs

/**
 * The original layout (Settings → Interface → Classic): plain buttons in two side columns, an
 * exposure bar at the bottom, every setting one tap away as a cycling button. The mode button
 * cycles Simple → Pro → Photo; Photo is Pro without the video-only buttons, and REC takes a still.
 */
internal class ClassicUi(private val a: CameraActivity) : CameraUi {

    private val kit = a.kit
    private val recButton = kit.button("") { a.toggleRecording() }.apply { textSize = 16f }
    /** Pause / resume, under REC while a clip records. */
    private val pauseButton = kit.button("") { a.togglePause() }
    private val modeSwitch = kit.button("") { a.setModeIndex((a.modeIndex + 1) % 3) }
    /** Photo mode: a DNG too (Glass: Settings → Photo). */
    private val rawButton = kit.button("") { a.setPhotoRaw(!a.photoRaw) }
    // The way back to Glass (and the lens tools) where it can be seen: this layout has no
    // settings sheet, and the same items in the lens menu went unnoticed.
    private val settingsButton = kit.button("⚙") { showSettingsMenu() }
    private val lensButton = kit.button("") { a.showLensMenu() }
    private val mirrorButton = kit.button("") { a.setMirrorFront(!a.mirrorFront) }
    private val profileButton = kit.button("") { a.cycleProfile() }
    private val viewButton = kit.button("") { a.showViewMenu() }
    private val importButton = kit.button("+ LUT") { a.pickLut() }
    private val edrButton = kit.button("") { a.setEdr(!a.edrOn) }
    /** Tap: on / off; hold: gentle → balanced → strong. */
    private val evoButton = kit.button("") { a.setEvo(!a.evoOn) }.apply {
        setOnLongClickListener { a.setEvoStrength((a.evoStrength + 1) % 3); true }
    }
    private fun evoText() = if (!a.evoOn) "EVO: off" else a.evoShift()?.let { "EVO: %+.1f".format(it) } ?: "EVO: ${a.evoStrengthLabel()}"
    /** The flash unit as a steady light (back lenses that have one). */
    private val torchButton = kit.button("") { a.setTorch(!a.capture.torch) }
    /** A still from the recording, under Pause while a clip records. */
    private val snapButton = kit.button("◉ Snap") { a.takeSnap() }
    private val adjustButton = kit.button("Adjust") {
        adjustPanel.visibility = if (adjustPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }
    private val fpsButton = kit.button("") { a.cycleFps() }
    private val codecButton = kit.button("") { a.cycleCodec() }
    private val bitrateButton = kit.button("") { a.cycleBitrate() }
    private val resButton = kit.button("") { a.setSuperpixel(!a.superpixel) }
    private val aspectButton = kit.button("") { a.cycleAspect() }
    private val audioButton = kit.button("") { a.toggleAudio() }
    private val bakeButton = kit.button("") { a.setBake(!a.bakeLut) }
    private val cleanButton = kit.button("") { a.cycleCleanup() }
    /** Colour NR's variant (Quality / Fast), shown while colour NR is on. */
    private val nrButton = kit.button("") { a.setNrFast(!a.nrFast) }
    private val prioGear = kit.button("⚙") { a.togglePriorityPanel() }
    private val aeButton = kit.button("") { a.cycleAe() }.apply {
        // Long-press in Priority mode opens the limits.
        setOnLongClickListener {
            if (a.capture.ae == AeMode.PRIORITY) a.togglePriorityPanel()
            true
        }
    }
    private val afButton = kit.button("") { a.cycleAf() }
    private val wbButton = kit.button("") { a.toggleAwbLock() }
    /** Exposure, white balance and focus back to automatic. */
    private val resetButton = kit.button("Reset") { a.resetCapture() }
    private val evGroup = Stepper(kit) { a.stepEv(it) }
    private val isoGroup = Stepper(kit) { a.stepIso(it) }
    private val shutterGroup = Stepper(kit) { a.stepShutter(it) }

    private lateinit var highlightsBar: SeekBar
    private lateinit var shadowsBar: SeekBar
    private lateinit var strengthBar: SeekBar
    private lateinit var saturationBar: SeekBar
    private lateinit var vibranceBar: SeekBar
    private lateinit var warmthBar: SeekBar
    private lateinit var tintBar: SeekBar
    private lateinit var textureBar: SeekBar
    private lateinit var clarityBar: SeekBar
    private lateinit var dehazeBar: SeekBar
    /** Texture / Clarity / Dehaze rows (Pro and Photo). */
    private lateinit var photoDials: List<View>
    private val adjustPanel = buildAdjustPanel()

    private val exposureBar = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(aeButton)
        addView(prioGear)
        evGroup.addTo(this)
        isoGroup.addTo(this)
        shutterGroup.addTo(this)
        addView(afButton)
        addView(wbButton)
        addView(fpsButton)
        addView(resetButton)
    }
    private val proOnly = listOf(profileButton, importButton, codecButton, bitrateButton, audioButton, bakeButton, cleanButton, nrButton, exposureBar,
        aspectButton)
    private val videoOnly = listOf(codecButton, bitrateButton, audioButton, bakeButton, cleanButton, nrButton, fpsButton)
    private val lockedWhileRecording = listOf(profileButton, fpsButton, codecButton, bitrateButton, resButton, audioButton, bakeButton,
        importButton, modeSwitch, lensButton, settingsButton, aspectButton, mirrorButton)

    override val lensAnchor: View get() = lensButton
    override val lookAnchor: View get() = viewButton

    override val root: View = FrameLayout(a).apply {
        fun column(vararg views: View) = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            views.forEach { addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)) }
        }
        val left = column(profileButton, viewButton, importButton, adjustButton, edrButton, evoButton, torchButton)
        val right = column(recButton, pauseButton, snapButton, rawButton, codecButton, bitrateButton, resButton, aspectButton, audioButton, bakeButton, cleanButton, nrButton)
        right.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val lp = a.exposureSlider.layoutParams as FrameLayout.LayoutParams
            val margin = v.width + 48 + 12
            if (lp.rightMargin != margin) { lp.rightMargin = margin; a.exposureSlider.requestLayout() }
        }
        setBackgroundColor(Color.BLACK)
        addView(a.surfaceView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(a.focusSquare, FrameLayout.LayoutParams(140, 140))
        addView(a.info, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(48, 32, 0, 0)
        })
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(lensButton)
            addView(mirrorButton)
            addView(modeSwitch)
            addView(settingsButton)
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, 24, 130, 0) // clear of the system's camera-in-use indicator
        })
        addView(left, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START).apply {
            setMargins(48, 0, 0, 0)
        })
        addView(right, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, 170, 48, 0) // below the lens / mode buttons
        })
        addView(exposureBar, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(0, 0, 0, 24)
        })
        addView(a.focusBar, FrameLayout.LayoutParams(900, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(0, 0, 0, 170)
        })
        addView(adjustPanel, FrameLayout.LayoutParams(900, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(0, 0, 0, 170)
        })
        // Beside the right-hand buttons, in the black margin next to the 4:3 image.
        addView(a.exposureSlider, FrameLayout.LayoutParams(170, (a.resources.displayMetrics.heightPixels * 0.62f).toInt(),
            Gravity.CENTER_VERTICAL or Gravity.END))
        addView(a.priorityPanel, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(0, 0, 0, 170)
        })
    }

    private fun buildAdjustPanel(): LinearLayout {
        fun slider(label: String, max: Int, initial: Int, format: (Int) -> String, neutral: Int = 100,
                   onChange: (Int) -> Unit): Pair<LinearLayout, SeekBar> {
            val text = kit.label().apply { minWidth = 260 }
            val bar = kit.seekBar(max, initial, neutral).apply {
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        text.text = "$label ${format(p)}"
                        if (fromUser) onChange(p)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar) = Unit
                })
            }
            text.text = "$label ${format(initial)}"
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text)
                addView(bar, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            return row to bar
        }
        val signed = { p: Int -> "%+d".format(p - 100) }
        val (hiRow, hi) = slider("eDR highlights", 200, a.edrHighlights + 100, signed, CameraActivity.EDR_HIGHLIGHTS + 100) {
            a.setEdrCurve(it - 100, a.edrShadows)
        }
        val (loRow, lo) = slider("eDR shadows", 200, a.edrShadows + 100, signed, CameraActivity.EDR_SHADOWS + 100) {
            a.setEdrCurve(a.edrHighlights, it - 100)
        }
        val (strengthRow, s) = slider("LUT strength", 100, a.lutStrength, { "$it%" }) { a.setLookAdjust(strength = it) }
        val (satRow, sat) = slider("Saturation", 200, a.saturationPct, { "$it%" }) { a.setLookAdjust(saturation = it) }
        val (vibRow, vib) = slider("Vibrance", 200, a.vibrancePct + 100, signed) { a.setLookAdjust(vibrance = it - 100) }
        val (warmRow, warm) = slider("Warmth", 200, a.wbWarmth + 100, signed) { a.setWbShift(it - 100, a.wbTint) }
        val (tintRow, tint) = slider("Tint", 200, a.wbTint + 100, signed) { a.setWbShift(a.wbWarmth, it - 100) }
        val (texRow, tex) = slider("Texture", 200, a.photoTexture + 100, signed) { a.setPhotoFinish(texture = it - 100) }
        val (claRow, cla) = slider("Clarity", 200, a.photoClarity + 100, signed) { a.setPhotoFinish(clarity = it - 100) }
        val (dehRow, deh) = slider("Dehaze", 200, a.photoDehaze + 100, signed) { a.setPhotoFinish(dehaze = it - 100) }
        textureBar = tex
        clarityBar = cla
        dehazeBar = deh
        photoDials = listOf(texRow, claRow, dehRow)
        highlightsBar = hi
        shadowsBar = lo
        strengthBar = s
        saturationBar = sat
        vibranceBar = vib
        warmthBar = warm
        tintBar = tint
        val buttons = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(kit.button("Reset") {
                a.setLookAdjust(100, 100, 0)
                a.setWbShift(0, 0)
                a.resetEdrCurve()
                if (!a.simple) a.setPhotoFinish(0, 0, 0)
            })
            addView(kit.button("Edit look / make LUT…") { a.openLookEditor() })
        }
        return LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xB0000000.toInt())
            setPadding(32, 16, 32, 16)
            visibility = View.GONE
            listOf(hiRow, loRow, strengthRow, satRow, vibRow, warmRow, tintRow, texRow, claRow, dehRow).forEach { addView(it) }
            addView(buttons)
        }
    }

    override fun update() {
        val recording = a.recording
        recButton.text = when {
            a.stopping || a.photoBusy -> "Saving…"
            a.warmingUp -> "Starting…"
            recording -> "■ STOP"
            a.photo -> "◉ PHOTO"
            else -> "● REC"
        }
        recButton.setTextColor(if (recording) Color.RED else Color.WHITE)
        recButton.isEnabled = !a.stopping && !a.photoBusy
        pauseButton.visibility = if (recording) View.VISIBLE else View.GONE
        pauseButton.text = if (a.paused) "▶ Resume" else "❚❚ Pause"
        pauseButton.isEnabled = !a.stopping
        snapButton.visibility = if (recording && !a.warmingUp) View.VISIBLE else View.GONE
        snapButton.isEnabled = !a.snapBusy && !a.stopping
        lockedWhileRecording.forEach { it.isEnabled = !recording && !a.stopping && !a.warmingUp && !a.photoBusy }
        proOnly.forEach { it.visibility = if (a.simple) View.GONE else View.VISIBLE }
        // Photo mode: Pro without what only video uses (a photo is always the full RAW, fully
        // cleaned up); its own RAW switch.
        resButton.visibility = if (a.photo) View.GONE else View.VISIBLE
        if (a.photo) videoOnly.forEach { it.visibility = View.GONE }
        fpsButton.visibility = if (a.photo) View.GONE else View.VISIBLE // inside the Pro-only exposure bar
        rawButton.visibility = if (a.photo) View.VISIBLE else View.GONE
        rawButton.text = if (a.photoRaw) "RAW: DNG" else "RAW: off"
        rawButton.isEnabled = !a.photoBusy
        a.info.visibility = View.VISIBLE // Glass hides it behind its settings sheet
        modeSwitch.text = when (a.modeIndex) {
            0 -> "Pro ▸"
            1 -> "Photo ▸"
            else -> "Simple ▸"
        }
        // Always shown, even with one lens: its menu is also where Rescan and Send diagnostics live.
        lensButton.text = a.lenses.getOrNull(a.lensIndex)?.label?.takeIf { it.isNotEmpty() } ?: "Lens"
        mirrorButton.visibility = if (a.lenses.getOrNull(a.lensIndex)?.front == true) View.VISIBLE else View.GONE
        mirrorButton.text = if (a.mirrorFront) "Mirror: on" else "Mirror: off"

        profileButton.text = a.profile.label
        viewButton.text = (if (a.simple) "Look: " else "View: ") + a.currentView()?.let { a.plainName(it) }
        fpsButton.text = "${a.capture.fps} fps"
        codecButton.text = a.effectiveCodec().label
        bitrateButton.text = "${a.bitrateMbps} Mbps"
        val twoK = a.effectiveSuperpixel()
        resButton.text = if (a.simple) (if (twoK) "2K" else "4K") else if (twoK) "Superpixel 2K" else "Open gate 4K"
        // Where 4K wouldn't record more than 2K (CameraActivity.fourKOffered) there's nothing to switch.
        resButton.isEnabled = resButton.isEnabled && (a.fourKOffered() || a.superpixel)
        audioButton.text = if (a.audioOn) "Audio: on" else "Audio: off"
        aspectButton.text = "Frame: ${a.aspect.label}"
        edrButton.text = when {
            !a.edrOn -> "eDR: off"
            a.edrHighlights == CameraActivity.EDR_HIGHLIGHTS && a.edrShadows == CameraActivity.EDR_SHADOWS -> "eDR: on"
            else -> "eDR: %+d / %+d".format(a.edrHighlights, a.edrShadows)
        }
        edrButton.isEnabled = a.edrAvailable()
        evoButton.text = evoText()
        torchButton.visibility = if (a.torchAvailable()) View.VISIBLE else View.GONE
        torchButton.text = if (a.capture.torch) "Torch: on" else "Torch: off"
        bakeButton.text = if (a.bakeLut) "Record: Look" else "Record: Log"
        cleanButton.text = a.cleanupLabel()
        nrButton.text = if (a.nrFast) "NR: Fast" else "NR: Quality"
        // Hidden while recording: Pause takes a row in this column then (and a switch mid-clip
        // would restart the NR's history).
        if (!a.simple && !a.photo) nrButton.visibility = if (a.cleanup >= 2 && !recording) View.VISIBLE else View.GONE

        aeButton.text = a.aeLabel()
        afButton.text = a.afLabel()
        wbButton.text = if (a.capture.awbLock) "WB: Locked" else "WB: Auto"
        val manual = a.capture.ae == AeMode.MANUAL
        evGroup.visible = !manual
        isoGroup.visible = manual
        shutterGroup.visible = manual
        evGroup.value.text = "EV %+.1f".format(a.exposureEv)
        isoGroup.value.text = "ISO ${a.capture.iso}"
        val denom = (1e9 / a.capture.exposureNs).toInt()
        shutterGroup.value.text = "1/$denom" + if (abs(denom - 2 * a.capture.fps) <= 1) " (180°)" else ""
        afButton.visibility = if (a.hasAfLens() && !a.simple) View.VISIBLE else View.GONE
        prioGear.visibility = if (a.capture.ae == AeMode.PRIORITY) View.VISIBLE else View.GONE
        resetButton.isEnabled = !a.captureAtDefaults()

        // Sliders follow changes made elsewhere (adb, Reset).
        fun sync(bar: SeekBar, p: Int) { if (bar.progress != p) bar.progress = p }
        sync(highlightsBar, a.edrHighlights + 100)
        sync(shadowsBar, a.edrShadows + 100)
        sync(strengthBar, a.lutStrength)
        sync(saturationBar, a.saturationPct)
        sync(vibranceBar, a.vibrancePct + 100)
        sync(warmthBar, a.wbWarmth + 100)
        sync(tintBar, a.wbTint + 100)
        sync(textureBar, a.photoTexture + 100)
        sync(clarityBar, a.photoClarity + 100)
        sync(dehazeBar, a.photoDehaze + 100)
        photoDials.forEach { it.visibility = if (a.simple) View.GONE else View.VISIBLE }
        (adjustPanel.layoutParams as? FrameLayout.LayoutParams)?.let {
            val margin = if (a.simple) 120 else 170
            if (it.bottomMargin != margin) { it.bottomMargin = margin; adjustPanel.requestLayout() }
        }
    }

    private fun showSettingsMenu() {
        val menu = android.widget.PopupMenu(a, settingsButton)
        menu.menu.add(0, 0, 0, "Switch to the Glass layout")
        menu.menu.add(0, 4, 1, "Recordings…")
        menu.menu.add(0, 5, 5, "About & licences")
        if (!a.scanning) menu.menu.add(0, 1, 1, "Rescan lenses")
        menu.menu.add(0, 2, 2, "Send diagnostics…")
        if (!a.scanning) menu.menu.add(0, 3, 3, "Run capability bench")
        if (!a.scanning && a.recordingLimits != null) menu.menu.add(0, 6, 4, "Re-check recording")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> a.setClassicUi(false)
                1 -> a.rescanLenses()
                2 -> a.sendDiagnostics()
                3 -> a.openBench()
                4 -> a.openRecordings()
                5 -> com.authrec.About.show(a)
                6 -> a.recheckRecording()
            }
            true
        }
        menu.show()
    }

    override fun onStats() {
        // EVO's shift changes by itself.
        evoButton.text = evoText()
    }

    override fun dismissPanels(): Boolean {
        if (adjustPanel.visibility != View.VISIBLE) return false
        adjustPanel.visibility = View.GONE
        return true
    }

    override fun showHint(text: String, action: String, onAction: () -> Unit, onClose: () -> Unit) {
        AlertDialog.Builder(a)
            .setMessage(text)
            .setPositiveButton(action) { _, _ -> onAction() }
            .setNegativeButton("Not now", null)
            .setOnDismissListener { onClose() }
            .show()
    }
}
