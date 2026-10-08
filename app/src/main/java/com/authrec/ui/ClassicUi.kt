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
 * exposure bar at the bottom, every setting one tap away as a cycling button.
 */
internal class ClassicUi(private val a: CameraActivity) : CameraUi {

    private val kit = a.kit
    private val recButton = kit.button("") { a.toggleRecording() }.apply { textSize = 16f }
    private val modeSwitch = kit.button("") { a.setSimple(!a.simple) }
    // The way back to Glass (and the lens tools) where it can be seen: this layout has no
    // settings sheet, and the same items in the lens menu went unnoticed.
    private val settingsButton = kit.button("⚙") { showSettingsMenu() }
    private val lensButton = kit.button("") { a.showLensMenu() }
    private val profileButton = kit.button("") { a.cycleProfile() }
    private val viewButton = kit.button("") { a.showViewMenu() }
    private val importButton = kit.button("+ LUT") { a.pickLut() }
    private val edrButton = kit.button("") { a.setEdr(!a.edrOn) }
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
    }
    private val proOnly = listOf(profileButton, importButton, codecButton, bitrateButton, audioButton, bakeButton, cleanButton, exposureBar,
        aspectButton)
    private val lockedWhileRecording = listOf(profileButton, fpsButton, codecButton, bitrateButton, resButton, audioButton, bakeButton,
        importButton, modeSwitch, lensButton, settingsButton, aspectButton)

    override val lensAnchor: View get() = lensButton
    override val lookAnchor: View get() = viewButton

    override val root: View = FrameLayout(a).apply {
        fun column(vararg views: View) = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            views.forEach { addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)) }
        }
        val left = column(profileButton, viewButton, importButton, adjustButton, edrButton)
        val right = column(recButton, codecButton, bitrateButton, resButton, aspectButton, audioButton, bakeButton, cleanButton)
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
        fun slider(label: String, max: Int, initial: Int, format: (Int) -> String, onChange: (Int) -> Unit): Pair<LinearLayout, SeekBar> {
            val text = kit.label().apply { minWidth = 260 }
            val bar = kit.seekBar(max, initial).apply {
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
        val (hiRow, hi) = slider("eDR highlights", 200, a.edrHighlights + 100, signed) { a.setEdrCurve(it - 100, a.edrShadows) }
        val (loRow, lo) = slider("eDR shadows", 200, a.edrShadows + 100, signed) { a.setEdrCurve(a.edrHighlights, it - 100) }
        val (strengthRow, s) = slider("LUT strength", 100, a.lutStrength, { "$it%" }) { a.setLookAdjust(strength = it) }
        val (satRow, sat) = slider("Saturation", 200, a.saturationPct, { "$it%" }) { a.setLookAdjust(saturation = it) }
        val (vibRow, vib) = slider("Vibrance", 200, a.vibrancePct + 100, signed) { a.setLookAdjust(vibrance = it - 100) }
        val (warmRow, warm) = slider("Warmth", 200, a.wbWarmth + 100, signed) { a.setWbShift(it - 100, a.wbTint) }
        val (tintRow, tint) = slider("Tint", 200, a.wbTint + 100, signed) { a.setWbShift(a.wbWarmth, it - 100) }
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
            })
            addView(kit.button("Edit look / make LUT…") { a.openLookEditor() })
        }
        return LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xB0000000.toInt())
            setPadding(32, 16, 32, 16)
            visibility = View.GONE
            listOf(hiRow, loRow, strengthRow, satRow, vibRow, warmRow, tintRow).forEach { addView(it) }
            addView(buttons)
        }
    }

    override fun update() {
        val recording = a.recording
        recButton.text = when {
            a.stopping -> "Saving…"
            a.warmingUp -> "Starting…"
            recording -> "■ STOP"
            else -> "● REC"
        }
        recButton.setTextColor(if (recording) Color.RED else Color.WHITE)
        recButton.isEnabled = !a.stopping
        lockedWhileRecording.forEach { it.isEnabled = !recording && !a.stopping && !a.warmingUp }
        proOnly.forEach { it.visibility = if (a.simple) View.GONE else View.VISIBLE }
        modeSwitch.text = if (a.simple) "Pro ▸" else "Simple ▸"
        // Always shown, even with one lens: its menu is also where Rescan and Send diagnostics live.
        lensButton.text = a.lenses.getOrNull(a.lensIndex)?.label?.takeIf { it.isNotEmpty() } ?: "Lens"

        profileButton.text = a.profile.label
        viewButton.text = (if (a.simple) "Look: " else "View: ") + a.currentView()?.label
        fpsButton.text = "${a.capture.fps} fps"
        codecButton.text = a.codec.label
        bitrateButton.text = "${a.bitrateMbps} Mbps"
        resButton.text = if (a.simple) (if (a.superpixel) "2K" else "4K") else if (a.superpixel) "Superpixel 2K" else "Open gate 4K"
        audioButton.text = if (a.audioOn) "Audio: on" else "Audio: off"
        aspectButton.text = "Frame: ${a.aspect.label}"
        edrButton.text = when {
            !a.edrOn -> "eDR: off"
            a.edrHighlights == CameraActivity.EDR_HIGHLIGHTS && a.edrShadows == CameraActivity.EDR_SHADOWS -> "eDR: on"
            else -> "eDR: %+d / %+d".format(a.edrHighlights, a.edrShadows)
        }
        edrButton.isEnabled = a.edrAvailable()
        bakeButton.text = if (a.bakeLut) "Record: Look" else "Record: Log"
        cleanButton.text = a.cleanupLabel()

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

        // Sliders follow changes made elsewhere (adb, Reset).
        fun sync(bar: SeekBar, p: Int) { if (bar.progress != p) bar.progress = p }
        sync(highlightsBar, a.edrHighlights + 100)
        sync(shadowsBar, a.edrShadows + 100)
        sync(strengthBar, a.lutStrength)
        sync(saturationBar, a.saturationPct)
        sync(vibranceBar, a.vibrancePct + 100)
        sync(warmthBar, a.wbWarmth + 100)
        sync(tintBar, a.wbTint + 100)
        (adjustPanel.layoutParams as? FrameLayout.LayoutParams)?.let {
            val margin = if (a.simple) 120 else 170
            if (it.bottomMargin != margin) { it.bottomMargin = margin; adjustPanel.requestLayout() }
        }
    }

    private fun showSettingsMenu() {
        val menu = android.widget.PopupMenu(a, settingsButton)
        menu.menu.add(0, 0, 0, "Switch to the Glass layout")
        if (!a.scanning) menu.menu.add(0, 1, 1, "Rescan lenses")
        menu.menu.add(0, 2, 2, "Send diagnostics…")
        if (!a.scanning) menu.menu.add(0, 3, 3, "Run capability bench")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> a.setClassicUi(false)
                1 -> a.rescanLenses()
                2 -> a.sendDiagnostics()
                3 -> a.openBench()
            }
            true
        }
        menu.show()
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
