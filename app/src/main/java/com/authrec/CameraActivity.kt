package com.authrec

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.authrec.camera.AeMode
import com.authrec.camera.AfMode
import com.authrec.camera.CaptureSettings
import com.authrec.camera.Lens
import com.authrec.camera.LensProbe
import com.authrec.camera.RawCamera
import com.authrec.color.CubeLut
import com.authrec.color.LogProfile
import com.authrec.color.Look
import com.authrec.color.Looks
import com.authrec.gl.Renderer
import com.authrec.record.RecordConfig
import com.authrec.record.Recorder
import com.authrec.record.VideoCodec
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Live open-gate RAW → log preview with real-time looks/LUTs, and recording to HEVC/H.264 with
 * audio. Two layouts: Simple (look, tap to focus, record) and Pro (everything).
 *
 * Looks: built-in film-style presets, looks made in [LookEditorActivity], and imported .cube
 * files (in Android/data/com.authrec/files/luts/).
 *
 * Can also be driven over adb (see [handleCommands]), e.g.
 * adb shell am start -n com.authrec/.CameraActivity --es cmd rec --es codec HEVC_10 --ei bitrate 150
 */
class CameraActivity : Activity() {

    /** Entries the View/Look button cycles through. */
    private sealed class ViewEntry(val label: String) {
        object LogView : ViewEntry("Log")
        class BuiltIn(val look: Look) : ViewEntry(look.name)
        class UserLook(val look: Look) : ViewEntry("★ ${look.name}")
        class CubeFile(val file: File) : ViewEntry("LUT ${file.nameWithoutExtension}")
    }

    private val cameraThread = HandlerThread("camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val prefs by lazy { getSharedPreferences("authrec", MODE_PRIVATE) }

    private var camera: RawCamera? = null
    private var renderer: Renderer? = null
    private var surface: Triple<SurfaceHolder, Int, Int>? = null
    private var displayQuarterTurns = 0

    private var capture = CaptureSettings()
    private var profile = LogProfile.APPLE_LOG
    private var views: List<ViewEntry> = listOf(ViewEntry.LogView)
    private var viewIndex = 1
    private var codec = VideoCodec.HEVC_10
    private var bitrateMbps = 150
    private var bakeLut = false
    private var superpixel = false
    private var audioOn = true
    private var simple = true
    /** See [Renderer.cleanup]. */
    private var cleanup = 2
    /** User adjustment on top of the automatic per-lens gain before the log curve, in EV. */
    /**
     * The one exposure control (bottom −/+ and the slider show the same value). AUTO/LOCKED: real
     * sensor exposure via AE compensation, any remainder below one AE step as digital gain.
     * PRIORITY: shifts our loop's brightness target. MANUAL: digital gain (ISO/shutter are yours).
     */
    private var exposureEv = 0f

    /** Limits for AE: Priority. Shutter in ns (fastest = shortest). */
    private data class PriorityLimits(
        val fastestNs: Long = 1_000_000_000L / 60,
        val slowestNs: Long = 1_000_000_000L / 30,
        val isoMin: Int = 50,
        val isoMax: Int = 800,
        val preferLowIso: Boolean = true,
    )
    private var limits = PriorityLimits()

    private var lenses: List<Lens> = emptyList()
    private var lensIndex = 0
    private var scanning = false
    private var pendingScan = false
    private var resumed = false

    private var recorder: Recorder? = null
    private var recordStartMs = 0L
    private var stopping = false
    private var lastResult: String? = null

    private lateinit var info: TextView
    private lateinit var recButton: Button
    private lateinit var lockedWhileRecording: List<View>
    private lateinit var proOnly: List<View>
    private lateinit var modeSwitch: Button
    private lateinit var lensButton: Button
    private lateinit var profileButton: Button
    private lateinit var viewButton: Button
    private lateinit var fpsButton: Button
    private lateinit var codecButton: Button
    private lateinit var bitrateButton: Button
    private lateinit var resButton: Button
    private lateinit var audioButton: Button
    private lateinit var bakeButton: Button
    private lateinit var cleanButton: Button
    private lateinit var aeButton: Button
    private lateinit var afButton: Button
    private lateinit var wbButton: Button
    private lateinit var evGroup: Stepper
    private lateinit var isoGroup: Stepper
    private lateinit var shutterGroup: Stepper
    private lateinit var lookPanel: LinearLayout
    private lateinit var focusBar: LinearLayout
    private lateinit var focusSeek: SeekBar
    private lateinit var focusLabel: TextView
    private lateinit var focusSquare: View
    private lateinit var strengthBar: SeekBar
    private lateinit var saturationBar: SeekBar
    private lateinit var vibranceBar: SeekBar
    private lateinit var gainBar: LinearLayout
    private lateinit var exposureSeek: SeekBar
    private lateinit var exposureLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loadPrefs()
        installBundledLuts()
        buildUi()
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (Manifest.permission.CAMERA !in missing) findLensesThenStart()
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) audioOn = false
        handleCommands(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCommands(intent)
    }

    // ---- Preferences ----

    private fun loadPrefs() {
        simple = prefs.getBoolean("simple", true)
        profile = runCatching { LogProfile.valueOf(prefs.getString("profile", null)!!) }.getOrDefault(LogProfile.APPLE_LOG)
        codec = runCatching { VideoCodec.valueOf(prefs.getString("codec", null)!!) }.getOrDefault(VideoCodec.HEVC_10)
        bitrateMbps = prefs.getInt("bitrate", 150)
        bakeLut = prefs.getBoolean("recordLook", true)
        cleanup = prefs.getInt("cleanup", 2)
        superpixel = prefs.getBoolean("superpixel", false)
        audioOn = prefs.getBoolean("audio", true)
        capture = capture.copy(fps = prefs.getInt("fps", 30))
        exposureEv = prefs.getFloat("exposureEv", 0f)
        limits = PriorityLimits(
            prefs.getLong("prioFastestNs", limits.fastestNs), prefs.getLong("prioSlowestNs", limits.slowestNs),
            prefs.getInt("prioIsoMin", limits.isoMin), prefs.getInt("prioIsoMax", limits.isoMax),
            prefs.getBoolean("prioLowIso", limits.preferLowIso),
        )
    }

    private fun savePrefs() {
        prefs.edit()
            .putBoolean("simple", simple)
            .putString("profile", profile.name)
            .putString("codec", codec.name)
            .putInt("bitrate", bitrateMbps)
            .putBoolean("recordLook", bakeLut)
            .putInt("cleanup", cleanup)
            .putBoolean("superpixel", superpixel)
            .putBoolean("audio", audioOn)
            .putInt("fps", capture.fps)
            .putFloat("exposureEv", exposureEv)
            .putLong("prioFastestNs", limits.fastestNs)
            .putLong("prioSlowestNs", limits.slowestNs)
            .putInt("prioIsoMin", limits.isoMin)
            .putInt("prioIsoMax", limits.isoMax)
            .putBoolean("prioLowIso", limits.preferLowIso)
            .putString("view", views.getOrNull(viewIndex)?.label)
            .apply()
    }

    // ---- UI ----

    /** "− value +" control. */
    private inner class Stepper(onStep: (Int) -> Unit) {
        val minus = smallButton("−") { onStep(-1) }
        val value = smallButton("") { }.apply { isClickable = false }
        val plus = smallButton("+") { onStep(1) }
        val views = listOf(minus, value, plus)
        var visible: Boolean = true
            set(v) {
                field = v
                views.forEach { it.visibility = if (v) View.VISIBLE else View.GONE }
            }
    }

    private fun smallButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        // Compact: the default 48 dp minimum height makes the side columns collide on landscape phones.
        minHeight = 0
        minimumHeight = 0
        setPadding(28, 28, 28, 28)
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                surface = Triple(holder, width, height)
                attachSurface()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surface = null
                renderer?.detachSurface()
            }
        })
        surfaceView.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) onPreviewTap(e.x, e.y)
            true
        }

        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            textSize = 12f
        }
        focusSquare = View(this).apply {
            background = GradientDrawable().apply { setStroke(4, Color.YELLOW) }
            visibility = View.GONE
        }

        recButton = smallButton("") { toggleRecording() }.apply { textSize = 16f }
        modeSwitch = smallButton("") { setSimple(!simple) }
        lensButton = smallButton("") { showLensMenu() }
        profileButton = smallButton("") { cycleProfile() }
        viewButton = smallButton("") { showViewMenu() }
        val importButton = smallButton("+ LUT") { pickLut() }
        val lookButton = smallButton("Adjust") {
            lookPanel.visibility = if (lookPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            lookPanel.post { updateUi() } // exposure bar sits above the panel, needs its height
        }
        fpsButton = smallButton("") { setCapture(capture.copy(fps = when (capture.fps) { 24 -> 25; 25 -> 30; else -> 24 })) }
        codecButton = smallButton("") { codec = VideoCodec.entries[(codec.ordinal + 1) % VideoCodec.entries.size]; settingsChanged() }
        bitrateButton = smallButton("") { bitrateMbps = when (bitrateMbps) { 50 -> 100; 100 -> 150; else -> 50 }; settingsChanged() }
        resButton = smallButton("") { setSuperpixel(!superpixel) }
        audioButton = smallButton("") { toggleAudio() }
        bakeButton = smallButton("") { bakeLut = !bakeLut; settingsChanged() }
        cleanButton = smallButton("") {
            cleanup = (cleanup + 1) % 4
            renderer?.cleanup = cleanup
            settingsChanged()
        }
        prioGear = smallButton("⚙") { priorityPanelOpen = !priorityPanelOpen; updateUi() }
        aeButton = smallButton("") { cycleAe() }.apply {
            // Long-press in Priority mode opens the limits.
            setOnLongClickListener {
                if (capture.ae == AeMode.PRIORITY) { priorityPanelOpen = !priorityPanelOpen; updateUi() }
                true
            }
        }
        afButton = smallButton("") { cycleAf() }
        wbButton = smallButton("") { setCapture(capture.copy(awbLock = !capture.awbLock)) }
        evGroup = Stepper { stepEv(it) }
        isoGroup = Stepper { stepIso(it) }
        shutterGroup = Stepper { stepShutter(it) }
        lookPanel = buildLookPanel()
        priorityPanel = buildPriorityPanel()
        focusBar = buildFocusBar()
        gainBar = buildExposureBar()

        fun column(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            views.forEach { addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)) }
        }
        val exposureBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(aeButton)
            addView(prioGear)
            (evGroup.views + isoGroup.views + shutterGroup.views).forEach { addView(it) }
            addView(afButton)
            addView(wbButton)
            addView(fpsButton)
        }
        val left = column(profileButton, viewButton, importButton, lookButton)
        val right = column(recButton, codecButton, bitrateButton, resButton, audioButton, bakeButton, cleanButton)

        proOnly = listOf(profileButton, importButton, codecButton, bitrateButton, audioButton, bakeButton, cleanButton, exposureBar)
        lockedWhileRecording = listOf(profileButton, fpsButton, codecButton, bitrateButton, resButton, audioButton, bakeButton, importButton, modeSwitch, lensButton)

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(focusSquare, FrameLayout.LayoutParams(140, 140))
            addView(info, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                setMargins(48, 32, 0, 0)
            })
            addView(LinearLayout(this@CameraActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(lensButton)
                addView(modeSwitch)
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
            addView(focusBar, FrameLayout.LayoutParams(900, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                setMargins(0, 0, 0, 170)
            })
            addView(lookPanel, FrameLayout.LayoutParams(900, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                setMargins(0, 0, 0, 170)
            })
            addView(gainBar, FrameLayout.LayoutParams(900, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                setMargins(0, 0, 0, 32)
            })
            addView(priorityPanel, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                setMargins(0, 0, 0, 170)
            })
        })
        updateUi()
    }

    private fun buildLookPanel(): LinearLayout {
        fun slider(label: String, max: Int, initial: Int, format: (Int) -> String, onChange: (Int) -> Unit): Pair<LinearLayout, SeekBar> {
            val text = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 12f
                minWidth = 260
            }
            val bar = SeekBar(this).apply {
                this.max = max
                progress = initial
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        text.text = "$label ${format(p)}"
                        onChange(p)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar) = Unit
                })
            }
            text.text = "$label ${format(initial)}"
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text)
                addView(bar, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            return row to bar
        }
        val (strengthRow, s) = slider("LUT strength", 100, 100, { "$it%" }) { renderer?.lutStrength = it / 100f }
        val (satRow, sat) = slider("Saturation", 200, 100, { "$it%" }) { renderer?.saturation = it / 100f }
        val (vibRow, vib) = slider("Vibrance", 200, 100, { "%+d".format(it - 100) }) { renderer?.vibrance = (it - 100) / 100f }
        strengthBar = s
        saturationBar = sat
        vibranceBar = vib
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(smallButton("Reset") {
                strengthBar.progress = 100
                saturationBar.progress = 100
                vibranceBar.progress = 100
            })
            addView(smallButton("Edit look / make LUT…") { openLookEditor() })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xB0000000.toInt())
            setPadding(32, 16, 32, 16)
            visibility = View.GONE
            addView(strengthRow)
            addView(satRow)
            addView(vibRow)
            addView(buttons)
        }
    }

    private fun buildFocusBar(): LinearLayout {
        focusLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            minWidth = 200
        }
        focusSeek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val min = camera?.info?.minFocusDiopters ?: return
                    // Squared so the far end (where most shots live) gets more travel.
                    setCapture(capture.copy(focusDiopters = min * (p / 1000f) * (p / 1000f)), updateUiNow = false)
                    updateFocusLabel()
                }
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xB0000000.toInt())
            setPadding(32, 8, 32, 8)
            addView(focusLabel)
            addView(focusSeek, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }

    /**
     * Exposure slider, ±5 EV in 1/10 stops: same value as the bottom −/+ (see [exposureEv]).
     * Beyond the camera's AE compensation range the rest is applied as gain before the log curve.
     * Shown on its own in Simple mode; in Pro it sits above the Look panel.
     */
    private fun buildExposureBar(): LinearLayout {
        exposureLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            minWidth = 230
        }
        exposureSeek = SeekBar(this).apply {
            max = 100
            progress = ((exposureEv + 5) * 10).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) setExposureEv(p / 10f - 5)
                }
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = savePrefs()
            })
        }
        val reset = smallButton("↺") { setExposureEv(0f) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x80000000.toInt())
            setPadding(32, 4, 16, 4)
            addView(exposureLabel)
            addView(exposureSeek, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(reset)
        }
    }

    private fun setExposureEv(ev: Float) {
        exposureEv = (Math.round(ev * 10) / 10f).coerceIn(-5f, 5f)
        applyExposure()
        updateUi()
    }

    /** Routes [exposureEv] to sensor exposure or digital gain depending on the AE mode. */
    private fun applyExposure() {
        val r = renderer
        // The auto gain follows the ISP's rendering, which only means something while the ISP runs
        // AE uncompensated: the ISP's tone mapping partly undoes exposure compensation, and a
        // gain chasing it would cancel the user's EV. So it learns at EV 0 in AUTO and holds otherwise.
        r?.autoGainFrozen = capture.ae == AeMode.MANUAL || capture.ae == AeMode.PRIORITY || abs(exposureEv) > 0.05f
        when (capture.ae) {
            AeMode.AUTO, AeMode.LOCKED -> {
                val info = camera?.info
                val step = info?.evStep ?: (1f / 3)
                val steps = Math.round(exposureEv / step).coerceIn(info?.evRange?.lower ?: 0, info?.evRange?.upper ?: 0)
                r?.exposureOffsetEv = exposureEv - steps * step
                if (steps != capture.evSteps) setCapture(capture.copy(evSteps = steps), updateUiNow = false)
            }
            AeMode.PRIORITY -> r?.exposureOffsetEv = 0f // the loop's target carries it
            AeMode.MANUAL -> r?.exposureOffsetEv = exposureEv
        }
    }

    private fun updateFocusLabel() {
        val d = capture.focusDiopters
        focusLabel.text = "MF  " + if (d < 0.05f) "∞" else "%.2f m".format(1 / d)
    }

    private fun updateUi() {
        val recording = recorder != null
        recButton.text = when {
            stopping -> "Saving…"
            recording -> "■ STOP"
            else -> "● REC"
        }
        recButton.setTextColor(if (recording) Color.RED else Color.WHITE)
        recButton.isEnabled = !stopping
        lockedWhileRecording.forEach { it.isEnabled = !recording && !stopping }
        proOnly.forEach { it.visibility = if (simple) View.GONE else View.VISIBLE }
        modeSwitch.text = if (simple) "Pro ▸" else "Simple ▸"
        lensButton.text = lenses.getOrNull(lensIndex)?.label ?: "Lens"
        lensButton.visibility = if (lenses.size > 1) View.VISIBLE else View.GONE

        profileButton.text = profile.label
        viewButton.text = if (simple) "Look: ${views.getOrNull(viewIndex)?.label}" else "View: ${views.getOrNull(viewIndex)?.label}"
        fpsButton.text = "${capture.fps} fps"
        codecButton.text = codec.label
        bitrateButton.text = "$bitrateMbps Mbps"
        resButton.text = if (simple) (if (superpixel) "2K" else "4K") else if (superpixel) "Superpixel 2K" else "Open gate 4K"
        audioButton.text = if (audioOn) "Audio: on" else "Audio: off"
        bakeButton.text = if (bakeLut) "Record: Look" else "Record: Log"
        cleanButton.text = when (cleanup) {
            0 -> "Clean: off"
            1 -> "Clean: pixels"
            2 -> "Clean: + colour"
            else -> "Clean: + colour+"
        }

        aeButton.text = when (capture.ae) {
            AeMode.AUTO -> "AE: Auto"
            AeMode.LOCKED -> "AE: Locked"
            AeMode.MANUAL -> "Manual"
            AeMode.PRIORITY -> "AE: Priority"
        }
        afButton.text = when (capture.af) {
            AfMode.CONTINUOUS -> "AF: Auto"
            AfMode.TAP -> "AF: Tap"
            AfMode.MANUAL -> "MF"
            AfMode.SOFTWARE -> if (softContinuous) "AF: Auto" else "AF: Tap"
        }
        wbButton.text = if (capture.awbLock) "WB: Locked" else "WB: Auto"
        val manual = capture.ae == AeMode.MANUAL
        evGroup.visible = capture.ae != AeMode.MANUAL
        isoGroup.visible = manual
        shutterGroup.visible = manual
        evGroup.value.text = "EV %+.1f".format(exposureEv)
        isoGroup.value.text = "ISO ${capture.iso}"
        val denom = (1e9 / capture.exposureNs).toInt()
        shutterGroup.value.text = "1/$denom" + if (abs(denom - 2 * capture.fps) <= 1) " (180°)" else ""

        val hasAf = (camera?.info?.minFocusDiopters ?: 1f) > 0f
        afButton.visibility = if (hasAf && !simple) View.VISIBLE else View.GONE
        val mf = capture.af == AfMode.MANUAL && !simple && hasAf
        focusBar.visibility = if (mf) View.VISIBLE else View.GONE
        if (mf) {
            val min = camera?.info?.minFocusDiopters ?: 0f
            if (min > 0) focusSeek.progress = (kotlin.math.sqrt(capture.focusDiopters / min) * 1000).toInt()
            updateFocusLabel()
        }
        renderer?.peaking = mf
        exposureLabel.text = "Exposure %+.1f".format(exposureEv)
        exposureSeek.progress = ((exposureEv + 5) * 10).toInt()
        // Simple: the exposure slider is the one control. Pro: inside the Look panel.
        gainBar.visibility = if (simple || lookPanel.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        (gainBar.layoutParams as FrameLayout.LayoutParams).bottomMargin = when {
            lookPanel.visibility == View.VISIBLE -> 170 + lookPanel.height + 8
            else -> 32
        }
        (lookPanel.layoutParams as FrameLayout.LayoutParams).bottomMargin = if (simple) 120 else 170
        lookPanel.requestLayout()
        gainBar.requestLayout()
        priorityPanel.visibility = if (!simple && capture.ae == AeMode.PRIORITY && priorityPanelOpen) View.VISIBLE else View.GONE
        prioGear.visibility = if (capture.ae == AeMode.PRIORITY) View.VISIBLE else View.GONE
        updatePriorityPanel()
    }

    private fun settingsChanged() {
        savePrefs()
        updateUi()
    }

    private fun setSimple(on: Boolean) {
        simple = on
        // Simple mode is fully automatic.
        if (on) setCapture(capture.copy(ae = AeMode.AUTO, af = AfMode.CONTINUOUS, focusPoint = null, evSteps = 0, awbLock = false))
        settingsChanged()
    }

    // ---- Focus ----

    private fun onPreviewTap(x: Float, y: Float) {
        // A tap on the image first dismisses any open panel.
        if (priorityPanelOpen || lookPanel.visibility == View.VISIBLE) {
            priorityPanelOpen = false
            lookPanel.visibility = View.GONE
            updateUi()
            return
        }
        val (_, vw, vh) = surface ?: return
        val cam = camera ?: return
        if (capture.af == AfMode.MANUAL && !simple) return
        // Undo the preview letterboxing, then the display rotation, to get image coordinates.
        val w = cam.info.size.width.toFloat()
        val h = cam.info.size.height.toFloat()
        val quarter = displayQuarterTurns % 2 == 1
        val imgAspect = if (quarter) h / w else w / h
        val (iw, ih) = if (vw.toFloat() / vh > imgAspect) vh * imgAspect to vh.toFloat() else vw.toFloat() to vw / imgAspect
        var u = (x - (vw - iw) / 2) / iw
        var v = (y - (vh - ih) / 2) / ih
        if (u !in 0f..1f || v !in 0f..1f) return
        repeat(displayQuarterTurns) { val t = u; u = v; v = 1 - t }

        focusAt(u, v)
        focusSquare.apply {
            translationX = x - 70
            translationY = y - 70
            visibility = View.VISIBLE
            removeCallbacks(hideFocusSquare)
            postDelayed(hideFocusSquare, 1500)
        }
        updateUi()
    }

    private val hideFocusSquare = Runnable { focusSquare.visibility = View.GONE }

    private var softAf: SoftwareAf? = null
    /** On a contrast-AF lens: true = "AF: Auto" (centre, keeps refocusing), false = "AF: Tap" (holds the tapped spot). */
    private var softContinuous = true

    private fun softAfKey() = "softAf:${lenses.getOrNull(lensIndex)?.key}"

    /** True once we've seen this lens ignore the ISP's autofocus. */
    private fun usesSoftwareAf() = camera?.zoomRouted != true && prefs.getBoolean(softAfKey(), false)

    private fun hasAf() = (camera?.info?.minFocusDiopters ?: 0f) > 0f

    /**
     * Focus (and meter) at image point (u, v). Uses the ISP's AF; if the lens ignores the AF
     * trigger (its AF state stays inactive), switches that lens to our contrast AF for good.
     * On zoom-routed lenses taps always use contrast AF (see below).
     */
    private fun focusAt(u: Float, v: Float) {
        val cam = camera ?: return
        // Zoom routes: the phone's native AF only does continuous focus (taps and regions are
        // ignored there), so a tap uses our contrast AF on the spot; "AF: Auto" stays native.
        if (hasAf() && (usesSoftwareAf() || cam.zoomRouted)) {
            runSoftwareAf(u, v, continuous = false)
            return
        }
        softAf?.cancel()
        capture = capture.copy(af = AfMode.TAP, focusPoint = PointF(u, v))
        cam.tapToFocus(capture)
        updateUi()
        // Zoom routes focus natively via continuous AF + region (no trigger to check).
        if (hasAf() && !cam.zoomRouted) info.postDelayed({
            if (capture.af == AfMode.TAP && camera === cam && cam.latestMeta?.afState == 0) {
                Log.i(TAG, "Lens ${lenses.getOrNull(lensIndex)?.key} ignores AF triggers; using contrast AF")
                prefs.edit().putBoolean(softAfKey(), true).apply()
                runSoftwareAf(u, v, continuous = false)
            }
        }, 900)
    }

    private fun runSoftwareAf(u: Float, v: Float, continuous: Boolean) {
        val cam = camera ?: return
        val r = renderer ?: return
        softAf?.cancel()
        softContinuous = continuous
        capture = capture.copy(af = AfMode.SOFTWARE, focusPoint = PointF(u, v))
        // Centre AF looks at a bigger area so it isn't fooled by one small detail.
        val half = if (continuous) 0.12f else 0.07f
        softAf = SoftwareAf(
            Handler(mainLooper), r, cam.info.minFocusDiopters,
            sensorClockNs = { if (cam.info.timestampsAreBoottime) SystemClock.elapsedRealtimeNanos() else System.nanoTime() },
            moveLens = { d -> capture = capture.copy(focusDiopters = d); cam.update(capture) },
            onLocked = { d -> Log.i(TAG, "contrast AF locked at %.2f diopters (%.2f m)".format(d, if (d > 0) 1 / d else Float.POSITIVE_INFINITY)) },
        ).also { it.start(floatArrayOf(u - half, v - half, u + half, v + half), continuous) }
        updateUi()
    }

    /** Auto ⇄ MF (a tap anywhere gives Tap). Same on every lens, whoever runs the autofocus. */
    private fun cycleAf() {
        val soft = usesSoftwareAf()
        Log.d(TAG, "cycleAf from ${capture.af} soft=$soft continuous=$softContinuous")
        if (capture.af == AfMode.MANUAL) {
            if (soft) {
                runSoftwareAf(0.5f, 0.5f, continuous = true)
            } else {
                setCapture(capture.copy(af = AfMode.CONTINUOUS, focusPoint = null))
            }
        } else {
            softAf?.cancel()
            // Manual focus starts where focus is now.
            val d = if (soft) capture.focusDiopters else camera?.latestMeta?.focusDiopters ?: 0f
            setCapture(capture.copy(af = AfMode.MANUAL, focusDiopters = d, focusPoint = null))
        }
    }

    /** After switching lenses: focus modes and tap points don't carry over between lenses. */
    private fun resetFocusForLens() {
        softAf?.cancel()
        softAf = null
        capture = capture.copy(af = AfMode.CONTINUOUS, focusPoint = null)
        if (hasAf() && usesSoftwareAf()) {
            softContinuous = true
            capture = capture.copy(af = AfMode.SOFTWARE)
            // Give the stream a moment to start before the first sweep.
            info.postDelayed({ if (capture.af == AfMode.SOFTWARE && softContinuous) runSoftwareAf(0.5f, 0.5f, continuous = true) }, 1200)
        }
    }

    // ---- Exposure ----

    private fun setCapture(newSettings: CaptureSettings, updateUiNow: Boolean = true) {
        var settings = newSettings
        val fpsChanged = settings.fps != capture.fps
        val priorityStarted = settings.ae == AeMode.PRIORITY && capture.ae != AeMode.PRIORITY
        if (priorityStarted) {
            // Start from the ISP's current exposure, re-split into shutter and ISO.
            val meta = camera?.latestMeta
            val e = (meta?.iso ?: capture.iso).toDouble() * (meta?.exposureNs ?: capture.exposureNs)
            settings = splitExposure(e).let { (iso, ns) -> settings.copy(iso = iso, exposureNs = ns) }
            // Keep the brightness the ISP's auto exposure chose for this lens and scene; the loop
            // only redistributes it between shutter and ISO.
            priorityTarget = renderer?.rawBrightness?.takeIf { !it.isNaN() && it > 0f }?.toDouble() ?: DEFAULT_PRIORITY_TARGET
        }
        val modeChanged = settings.ae != capture.ae
        capture = settings
        if (priorityStarted) {
            info.post(priorityTick)
            priorityPanelOpen = true
        }
        if (modeChanged) applyExposure()
        camera?.update(capture)
        if (fpsChanged) savePrefs()
        if (updateUiNow) updateUi()
    }

    private fun cycleAe() {
        val next = when (capture.ae) {
            AeMode.AUTO -> AeMode.PRIORITY
            AeMode.PRIORITY -> AeMode.LOCKED
            AeMode.LOCKED -> AeMode.MANUAL
            AeMode.MANUAL -> AeMode.AUTO
        }
        if (next == AeMode.MANUAL) {
            // Start manual from what auto exposure was just doing, snapped to the nearest stops.
            val meta = camera?.latestMeta
            val iso = isoStops().minBy { abs(it - (meta?.iso ?: capture.iso)) }
            val shutter = shutterStops().minBy { abs(it - (meta?.exposureNs ?: capture.exposureNs)) }
            setCapture(capture.copy(ae = next, iso = iso, exposureNs = shutter))
        } else {
            setCapture(capture.copy(ae = next))
        }
    }

    // ---- AE: Priority (our own exposure loop inside user limits) ----

    /**
     * Holds the brightness the ISP's AE had when the mode was switched on (shifted by
     * [exposureEv]) while keeping shutter and ISO inside [limits]: "low ISO first" lengthens the
     * shutter to its slowest limit before raising ISO; "fast shutter first" raises ISO to its
     * limit before slowing the shutter. Fix ISO (min = max) for ISO priority, or the shutter
     * (fastest = slowest) for shutter priority. Too bright for the limits: the shutter goes
     * faster anyway (no ND to fall back on). Too dark: stays at the limits and says so.
     * 5 updates/s, moving half-way (in stops) each time, so it settles without pumping.
     */
    private val priorityTick: Runnable = object : Runnable {
        override fun run() {
            info.removeCallbacks(this)
            if (capture.ae != AeMode.PRIORITY) return
            val r = renderer
            val measured = r?.rawBrightness ?: Float.NaN
            if (r != null && !measured.isNaN() && measured > 0f) {
                val current = capture.iso.toDouble() * capture.exposureNs
                val goal = priorityTarget * Math.pow(2.0, exposureEv.toDouble())
                var target = current * Math.pow(goal / measured, 0.5)
                if (r.rawClipFraction > 0.02f) target = minOf(target, current * 0.8)
                val (iso, ns) = splitExposure(target)
                priorityAtLimit = iso.toDouble() * ns < target * 0.8
                if (abs(iso.toDouble() * ns / current - 1) > 0.04) setCapture(capture.copy(iso = iso, exposureNs = ns), updateUiNow = false)
            }
            info.postDelayed(this, 200)
        }
    }

    private var priorityTarget = DEFAULT_PRIORITY_TARGET
    private var priorityAtLimit = false
    private var priorityPanelOpen = false
    private lateinit var prioGear: Button

    /** Exposure (ISO × ns) → (ISO, shutter ns) inside [limits] (and the sensor's own range). */
    private fun splitExposure(e: Double): Pair<Int, Long> {
        val info = camera?.info ?: return capture.iso to capture.exposureNs
        val frameNs = 1_000_000_000.0 / capture.fps
        val fast = maxOf(limits.fastestNs.toDouble(), info.exposureRangeNs.lower.toDouble())
        val slow = minOf(limits.slowestNs.toDouble(), frameNs).coerceAtLeast(fast)
        val isoLo = maxOf(limits.isoMin, info.isoRange.lower).toDouble()
        val isoHi = minOf(limits.isoMax, info.isoRange.upper).toDouble().coerceAtLeast(isoLo)
        var t: Double
        var iso: Double
        if (limits.preferLowIso) {
            t = (e / isoLo).coerceIn(fast, slow)
            iso = (e / t).coerceIn(isoLo, isoHi)
        } else {
            iso = (e / fast).coerceIn(isoLo, isoHi)
            t = (e / iso).coerceIn(fast, slow)
        }
        if (e < isoLo * fast) {
            // Brighter than the limits allow: faster shutter rather than overexpose.
            iso = isoLo
            t = maxOf(e / isoLo, info.exposureRangeNs.lower.toDouble())
        }
        return iso.toInt() to t.toLong()
    }

    private lateinit var priorityPanel: LinearLayout
    private lateinit var prioFast: Stepper
    private lateinit var prioSlow: Stepper
    private lateinit var prioIsoMin: Stepper
    private lateinit var prioIsoMax: Stepper
    private lateinit var prioPrefer: Button

    private fun buildPriorityPanel(): LinearLayout {
        fun stepShutterLimit(current: Long, dir: Int): Long {
            val stops = priorityShutterStops() // slowest first
            val i = stops.indexOfFirst { it <= current }.let { if (it < 0) stops.lastIndex else it }
            return stops[(i + dir).coerceIn(0, stops.lastIndex)]
        }
        fun stepIsoLimit(current: Int, dir: Int): Int {
            val stops = isoStops()
            val i = stops.indexOfFirst { it >= current }.let { if (it < 0) stops.lastIndex else it }
            return stops[(i + dir).coerceIn(0, stops.lastIndex)]
        }
        prioFast = Stepper { d ->
            val v = stepShutterLimit(limits.fastestNs, d)
            limits = limits.copy(fastestNs = v, slowestNs = maxOf(limits.slowestNs, v))
            limitsChanged()
        }
        prioSlow = Stepper { d ->
            val v = stepShutterLimit(limits.slowestNs, d)
            limits = limits.copy(slowestNs = v, fastestNs = minOf(limits.fastestNs, v))
            limitsChanged()
        }
        prioIsoMin = Stepper { d ->
            val v = stepIsoLimit(limits.isoMin, d)
            limits = limits.copy(isoMin = v, isoMax = maxOf(limits.isoMax, v))
            limitsChanged()
        }
        prioIsoMax = Stepper { d ->
            val v = stepIsoLimit(limits.isoMax, d)
            limits = limits.copy(isoMax = v, isoMin = minOf(limits.isoMin, v))
            limitsChanged()
        }
        prioPrefer = smallButton("") {
            limits = limits.copy(preferLowIso = !limits.preferLowIso)
            limitsChanged()
        }
        fun row(label: String, a: Stepper, b: Stepper) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@CameraActivity).apply { text = label; setTextColor(Color.WHITE); textSize = 12f; minWidth = 150 })
            a.views.forEach { addView(it) }
            addView(TextView(this@CameraActivity).apply { text = "  to  "; setTextColor(Color.LTGRAY); textSize = 12f })
            b.views.forEach { addView(it) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xB0000000.toInt())
            setPadding(24, 12, 24, 12)
            visibility = View.GONE
            addView(row("Shutter", prioFast, prioSlow))
            addView(row("ISO", prioIsoMin, prioIsoMax))
            addView(LinearLayout(this@CameraActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(prioPrefer)
                addView(smallButton("✕ Done") { priorityPanelOpen = false; updateUi() })
            })
        }
    }

    private fun limitsChanged() {
        savePrefs()
        updatePriorityPanel()
    }

    private fun updatePriorityPanel() {
        if (!::priorityPanel.isInitialized) return
        prioFast.value.text = "1/${(1e9 / limits.fastestNs).roundToInt()}"
        prioSlow.value.text = "1/${(1e9 / limits.slowestNs).roundToInt()}"
        prioIsoMin.value.text = "${limits.isoMin}"
        prioIsoMax.value.text = "${limits.isoMax}"
        prioPrefer.text = if (limits.preferLowIso) "Prefer: low ISO (slower shutter first)" else "Prefer: fast shutter (higher ISO first)"
    }

    /** Shutter choices for the priority limits, slowest first, never longer than one frame. */
    private fun priorityShutterStops(): List<Long> {
        val frameNs = 1_000_000_000L / capture.fps
        return listOf(24, 25, 30, 48, 50, 60, 100, 120, 125, 250, 500, 1000)
            .map { 1_000_000_000L / it }.filter { it <= frameNs }.distinct().sortedDescending()
    }

    private fun stepEv(dir: Int) {
        val step = camera?.info?.evStep ?: (1f / 3)
        setExposureEv(exposureEv + dir * step)
    }

    private fun stepIso(dir: Int) {
        val stops = isoStops()
        val i = stops.indexOfFirst { it >= capture.iso }.let { if (it < 0) stops.lastIndex else it }
        setCapture(capture.copy(iso = stops[(i + dir).coerceIn(0, stops.lastIndex)]))
    }

    /** dir +1 = faster shutter. */
    private fun stepShutter(dir: Int) {
        val stops = shutterStops() // longest first
        val i = stops.indexOfFirst { it <= capture.exposureNs }.let { if (it < 0) stops.lastIndex else it }
        setCapture(capture.copy(exposureNs = stops[(i + dir).coerceIn(0, stops.lastIndex)]))
    }

    private fun isoStops(): List<Int> {
        val range = camera?.info?.isoRange ?: return listOf(capture.iso)
        val stops = listOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400)
        return (stops.filter { it in range.lower..range.upper } + range.upper).distinct().sorted()
    }

    /** Shutter times that fit in one frame at the current fps, longest first; includes 180° (1/2fps). */
    private fun shutterStops(): List<Long> {
        val frameNs = 1_000_000_000L / capture.fps
        val minNs = camera?.info?.exposureRangeNs?.lower ?: 100_000L
        val denoms = (listOf(24, 25, 30, 48, 50, 60, 100, 120, 125, 250, 500, 1000, 2000, 4000, 8000) + 2 * capture.fps).distinct()
        return denoms.map { 1_000_000_000L / it }.filter { it in minNs..frameNs }.sortedDescending()
    }

    // ---- Pipeline ----

    // ---- Lenses ----

    /** Uses the cached lens list, or probes every lens first (takes a few seconds, once per firmware). */
    private fun findLensesThenStart(forceScan: Boolean = false) {
        val probe = LensProbe(this)
        val cached = if (forceScan) null else probe.cached()
        if (cached != null) {
            lenses = cached
            selectSavedLens()
            setupPipeline()
            return
        }
        // Cameras can only be opened in the foreground; onResume starts the scan if we're early.
        if (!resumed) {
            pendingScan = true
            return
        }
        pendingScan = false
        scanning = true
        info.text = "Finding lenses…"
        Thread {
            val found = runCatching { probe.scan { msg -> runOnUiThread { info.text = msg } } }
                .onFailure { Log.e(TAG, "lens scan failed", it) }
                .getOrDefault(emptyList())
            runOnUiThread {
                scanning = false
                lenses = found
                selectSavedLens()
                lastResult = "Found ${found.size} lens${if (found.size == 1) "" else "es"}: " + found.joinToString { it.label }
                setupPipeline()
                if (resumed) camera?.open(capture)
                updateUi()
            }
        }.start()
    }

    private fun selectSavedLens() {
        val key = prefs.getString("lens", null)
        lensIndex = lenses.indexOfFirst { it.key == key }.takeIf { it >= 0 }
            ?: lenses.indexOfFirst { it.label == "1x" }.takeIf { it >= 0 } ?: 0
        // Prefer the native-AF route over its backup.
        twinOf(lenses[lensIndex])?.takeIf { lenses[lensIndex].zoomRatio == 1f }?.let { lensIndex = lenses.indexOf(it) }
    }

    /** Zoom routes whose native AF failed this session (we're on their backup). */
    private val nativeFailed = mutableSetOf<String>()

    /** The other route to the same sensor: zoom route ⇄ direct backup. */
    private fun twinOf(l: Lens): Lens? = if (l.zoomRatio != 1f) {
        lenses.firstOrNull { it.zoomRatio == 1f && it.physicalId == null && it.openId == l.physicalId }
    } else {
        lenses.firstOrNull { it.zoomRatio != 1f && it.physicalId == l.openId }
    }

    /** Backups only show in the menu while their native route has failed (or they're in use). */
    private fun inMenu(i: Int): Boolean {
        val l = lenses[i]
        val native = twinOf(l)?.takeIf { l.zoomRatio == 1f } ?: return true
        return i == lensIndex || native.key in nativeFailed
    }

    /** Switches a failing zoom route to its direct backup. Returns false if there is none. */
    private fun fallBackToBackup(reason: String): Boolean {
        val current = lenses.getOrNull(lensIndex) ?: return false
        if (current.zoomRatio == 1f) return false
        val backup = twinOf(current) ?: return false
        Log.w(TAG, "Native AF route ${current.key} failed ($reason); using backup ${backup.key}")
        nativeFailed += current.key
        if (recorder != null) stopRecording()
        lensIndex = lenses.indexOf(backup)
        lastResult = "${current.label}: native AF route stopped ($reason); switched to backup AF"
        restartPipeline()
        return true
    }

    /** Drop-down of all working lenses (back lenses in zoom order, front last) plus a rescan. */
    private fun showLensMenu() {
        if (recorder != null || stopping) return
        val menu = android.widget.PopupMenu(this, lensButton)
        val order = lenses.indices.sortedWith(compareBy({ lenses[it].front }, { lenses[it].equivFocalMm }))
        order.filter { inMenu(it) }.forEachIndexed { pos, i ->
            val l = lenses[i]
            val mark = if (i == lensIndex) "● " else ""
            menu.menu.add(0, i, pos, "$mark${l.label}  ·  ${"%.0f".format(l.equivFocalMm)} mm")
        }
        menu.menu.add(1, RESCAN_ITEM, order.size, "Rescan lenses")
        menu.menu.add(1, DIAGNOSTICS_ITEM, order.size + 1, "Send diagnostics…")
        menu.menu.add(1, BENCH_ITEM, order.size + 2, "Run capability bench")
        menu.setOnMenuItemClickListener { item ->
            when {
                item.itemId == RESCAN_ITEM -> rescanLenses()
                item.itemId == DIAGNOSTICS_ITEM -> Thread {
                    runCatching { Diagnostics.share(this) }
                        .onFailure { e -> runOnUiThread { lastResult = "Diagnostics failed: ${e.message}" } }
                }.start()
                item.itemId == BENCH_ITEM -> startActivity(Intent(this, com.authrec.bench.BenchActivity::class.java))
                item.itemId != lensIndex -> {
                    nativeFailed -= lenses[item.itemId].key
                    lensIndex = item.itemId
                    prefs.edit().putString("lens", lenses[lensIndex].key).apply()
                    restartPipeline()
                }
            }
            true
        }
        menu.show()
    }

    /** Drop-down of every look and LUT. */
    private fun showViewMenu() {
        val menu = android.widget.PopupMenu(this, viewButton)
        views.forEachIndexed { i, v ->
            if (simple && v is ViewEntry.LogView) return@forEachIndexed
            val group = when (v) {
                ViewEntry.LogView -> 0
                is ViewEntry.BuiltIn -> 1
                is ViewEntry.UserLook -> 2
                is ViewEntry.CubeFile -> 3
            }
            menu.menu.add(group, i, i, (if (i == viewIndex) "● " else "") + v.label)
        }
        menu.setOnMenuItemClickListener { item ->
            viewIndex = item.itemId
            applyView()
            settingsChanged()
            true
        }
        menu.show()
    }

    private fun rescanLenses() {
        if (recorder != null || scanning) return
        teardownPipeline()
        findLensesThenStart(forceScan = true)
    }

    private fun teardownPipeline() {
        camera?.close()
        camera?.release()
        renderer?.release()
        camera = null
        renderer = null
    }

    private fun restartPipeline() {
        softAf?.cancel()
        teardownPipeline()
        setupPipeline()
        if (resumed) camera?.open(capture)
        updateUi()
    }

    private fun setupPipeline() {
        if (camera != null) return
        val cam = try {
            RawCamera(this, cameraHandler, lenses.getOrNull(lensIndex)) { msg ->
                runOnUiThread { if (!fallBackToBackup("camera error")) info.text = msg }
            }
        } catch (e: Exception) {
            info.text = "Camera unavailable: ${e.message}"
            return
        }
        val r = Renderer(cam.info, cam.reader, { cam.latestMeta }, { cam.ispLinearLogAverage }) { stats ->
            runOnUiThread { showStats(stats) }
        }
        cam.reader.setOnImageAvailableListener({ r.onFrameAvailable() }, cameraHandler)
        r.profile = profile
        r.superpixel = superpixel
        r.cleanup = cleanup
        applyExposure()
        camera = cam
        renderer = r
        refreshViews(selectLabel = prefs.getString("view", null))
        applyView()
        attachSurface()
        resetFocusForLens()
        updateUi()
        // The camera opens in onResume (which always follows): the camera service refuses
        // apps that aren't in the foreground yet.
    }

    private fun attachSurface() {
        val (holder, w, h) = surface ?: return
        val r = renderer ?: return
        val cam = camera ?: return
        // Quarter turns needed to show the back sensor upright at the current display rotation.
        val displayDeg = display.rotation * 90
        // Front sensors are mounted the other way round, so their rotation adds the display's.
        displayQuarterTurns = if (cam.info.front) {
            ((cam.info.sensorOrientation + displayDeg) % 360) / 90
        } else {
            ((cam.info.sensorOrientation - displayDeg + 360) % 360) / 90
        }
        r.attachSurface(holder.surface, w, h, displayQuarterTurns)
    }

    /**
     * Restarts the camera if frames stop arriving (a lens can stall in some conditions). Gives up
     * after a few tries in a row and says so instead of looping.
     */
    private var stallRestarts = 0
    private val watchdog: Runnable = object : Runnable {
        override fun run() {
            info.removeCallbacks(this)
            if (!resumed) return
            val r = renderer
            val last = r?.lastFrameMs ?: 0L
            if (r != null && camera != null && !scanning && last > 0 && SystemClock.elapsedRealtime() - last > 2500) {
                if (fallBackToBackup("no frames")) {
                    stallRestarts = 0
                } else if (stallRestarts < 3) {
                    stallRestarts++
                    Log.w(TAG, "No frames from lens ${lenses.getOrNull(lensIndex)?.key} for 2.5 s; restarting camera ($stallRestarts)")
                    if (recorder != null) stopRecording()
                    restartPipeline()
                } else {
                    lastResult = "This lens stopped delivering frames; try another lens"
                }
            } else if (last > 0 && SystemClock.elapsedRealtime() - last < 1000) {
                stallRestarts = 0
            }
            info.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        info.postDelayed(watchdog, 3000)
        if (capture.ae == AeMode.PRIORITY) priorityTick.run()
        refreshViews(selectLabel = views.getOrNull(viewIndex)?.label)
        applyView()
        updateUi()
        resumed = true
        if (pendingScan) findLensesThenStart(forceScan = true) else camera?.open(capture)
    }

    override fun onPause() {
        resumed = false
        if (recorder != null) stopRecording()
        camera?.close()
        savePrefs()
        super.onPause()
    }

    override fun onDestroy() {
        renderer?.release()
        camera?.release()
        cameraThread.quitSafely()
        super.onDestroy()
    }

    private fun setSuperpixel(on: Boolean) {
        superpixel = on
        renderer?.superpixel = on
        settingsChanged()
    }

    private fun toggleAudio() {
        if (!audioOn && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMISSIONS)
            return
        }
        audioOn = !audioOn
        settingsChanged()
    }

    // ---- Recording ----

    private fun toggleRecording() = if (recorder == null) startRecording() else stopRecording()

    private fun startRecording() {
        val cam = camera ?: return
        val r = renderer ?: return
        if (stopping) return
        val w = if (superpixel) cam.info.size.width / 2 else cam.info.size.width
        val h = if (superpixel) cam.info.size.height / 2 else cam.info.size.height
        val config = RecordConfig(codec, bitrateMbps, capture.fps, audioOn, cam.info.timestampsAreBoottime)
        val rec = try {
            Recorder(this, config, w, h)
        } catch (e: Exception) {
            Log.e(TAG, "recorder setup failed", e)
            lastResult = "Can't record: ${e.message}"
            updateUi()
            return
        }
        recorder = rec
        recordStartMs = SystemClock.elapsedRealtime()
        lastResult = null
        // In simple mode the recording is what you see, so bake the look in.
        r.startRecording(rec, capture.fps, bakeLut || simple) { err ->
            runOnUiThread {
                lastResult = "Encoder surface failed: $err"
                stopRecording()
            }
        }
        updateUi()
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        val r = renderer ?: return
        recorder = null
        stopping = true
        updateUi()
        r.stopRecording {
            rec.stop { result ->
                val secs = (SystemClock.elapsedRealtime() - recordStartMs) / 1000.0
                val msg = "Saved ${result.frames} frames, ${result.bytes / 1_000_000} MB, " +
                    "≈%.0f Mbps".format(result.bytes * 8 / secs / 1e6) +
                    (if (result.audio) " + audio" else "") +
                    (result.error?.let { " — $it" } ?: "")
                Log.i(TAG, "$msg → ${result.uri}")
                runOnUiThread {
                    stopping = false
                    lastResult = msg
                    updateUi()
                }
            }
        }
    }

    // ---- Looks and LUTs ----

    private fun lutDir() = File(getExternalFilesDir(null), "luts").apply { mkdirs() }

    /** Copies .cube files bundled in assets/luts into the LUT folder (once; deleting one there is respected). */
    private fun installBundledLuts() {
        val installed = prefs.getStringSet("bundledLuts", emptySet())!!
        val names = assets.list("luts").orEmpty().filter { it.endsWith(".cube", ignoreCase = true) && it !in installed }
        names.forEach { name ->
            runCatching { assets.open("luts/$name").use { input -> File(lutDir(), name).outputStream().use { input.copyTo(it) } } }
        }
        if (names.isNotEmpty()) prefs.edit().putStringSet("bundledLuts", installed + names).apply()
    }

    /** Rebuilds the view list: Log, built-in looks, the user's looks, imported .cube files. */
    private fun refreshViews(selectLabel: String?) {
        val files = lutDir().listFiles().orEmpty().sortedBy { it.name.lowercase() }
        val userLooks = files.filter { it.name.endsWith(Look.SUFFIX) }
            .mapNotNull { f -> runCatching { ViewEntry.UserLook(Look.load(f)) }.getOrNull() }
        val cubes = files.filter { it.extension.equals("cube", ignoreCase = true) }.map { ViewEntry.CubeFile(it) }
        views = listOf(ViewEntry.LogView) + Looks.presets.map { ViewEntry.BuiltIn(it) } + userLooks + cubes
        viewIndex = views.indexOfFirst { it.label == selectLabel }.takeIf { it >= 0 } ?: 1
    }

    private fun pickLut() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // .cube has no registered MIME type
        }, REQ_PICK_LUT)
    }

    /** Grabs the live log frame and opens the editor on the current look. */
    private fun openLookEditor() {
        val r = renderer ?: return
        val sample = File(cacheDir, "look-sample.png")
        r.captureLogFrame(1000) { bmp ->
            sample.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            runOnUiThread {
                val intent = Intent(this, LookEditorActivity::class.java)
                    .putExtra(LookEditorActivity.EXTRA_PROFILE, profile.name)
                    .putExtra(LookEditorActivity.EXTRA_SAMPLE, sample.absolutePath)
                when (val v = views.getOrNull(viewIndex)) {
                    is ViewEntry.UserLook -> intent.putExtra(LookEditorActivity.EXTRA_LOOK, v.look.file?.absolutePath)
                    is ViewEntry.BuiltIn -> intent.putExtra(LookEditorActivity.EXTRA_PRESET, v.look.name)
                    else -> Unit
                }
                startActivityForResult(intent, REQ_EDIT_LOOK)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_PICK_LUT -> data.data?.let { importLut(it) }
            REQ_EDIT_LOOK -> data.getStringExtra(LookEditorActivity.EXTRA_LOOK)?.let { path ->
                // onResume follows and re-applies; point the selection at the saved look first.
                val saved = runCatching { Look.load(File(path)) }.getOrNull() ?: return
                refreshViews(selectLabel = ViewEntry.UserLook(saved).label)
                savePrefs()
            }
        }
    }

    private fun importLut(uri: Uri) {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "imported.cube"
        val target = File(lutDir(), if (name.endsWith(".cube", ignoreCase = true)) name else "$name.cube")
        try {
            contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
            CubeLut.parse(target) // reject files that aren't valid 3D LUTs right away
        } catch (e: Exception) {
            target.delete()
            lastResult = "Not a usable .cube LUT: ${e.message}"
            updateUi()
            return
        }
        refreshViews(selectLabel = ViewEntry.CubeFile(target).label)
        applyView()
        lastResult = "Imported ${target.nameWithoutExtension}"
        settingsChanged()
    }

    private fun cycleProfile() {
        profile = LogProfile.entries[(profile.ordinal + 1) % LogProfile.entries.size]
        renderer?.profile = profile
        applyView()
        settingsChanged()
    }

    private fun applyView() {
        val r = renderer ?: return
        val lut = when (val v = views.getOrNull(viewIndex)) {
            null, ViewEntry.LogView -> null
            is ViewEntry.BuiltIn -> v.look.toLut(profile)
            is ViewEntry.UserLook -> v.look.toLut(profile)
            is ViewEntry.CubeFile -> runCatching { CubeLut.parse(v.file) }
                .onFailure { lastResult = "LUT error: ${it.message}" }
                .getOrNull()
        }
        r.setLut(lut)
    }

    private fun showStats(stats: Renderer.Stats) {
        val cam = camera ?: return
        val m = stats.meta
        val shutter = if (m != null && m.exposureNs > 0) "1/${(1e9 / m.exposureNs).toInt()}" else "-"
        val out = if (superpixel) "${cam.info.size.width / 2}×${cam.info.size.height / 2} superpixel" else "${cam.info.size.width}×${cam.info.size.height}"
        info.text = buildString {
            if (!simple) {
                append("$out · ${profile.label}\n")
                append("ISO ${m?.iso ?: "-"} · $shutter · gain %+.1f EV · %.1f fps · frame %.1f ms".format(
                    (renderer?.autoGainEv ?: 0f) + (renderer?.exposureOffsetEv ?: 0f), stats.fps, stats.gpuMs))
                if (capture.ae == AeMode.PRIORITY && priorityAtLimit) append("\n⚠ priority limits reached: image darker than target")
                if (m != null && m.shading == null) append("\nno lens shading map")
            }
            recorder?.let {
                val secs = (SystemClock.elapsedRealtime() - recordStartMs) / 1000
                if (isNotEmpty()) append("\n")
                append("● REC %d:%02d · %s".format(secs / 60, secs % 60, if (bakeLut || simple) "look baked in" else "clean log"))
                if (!simple) append(" · %d frames · %d MB · dropped %d".format(it.framesWritten, it.bytesWritten / 1_000_000, stats.droppedWhileRecording))
            }
            lastResult?.let { if (isNotEmpty()) append("\n"); append(it) }
        }
    }

    /**
     * adb control, for testing without touching the phone
     * (adb shell am start -n com.authrec/.CameraActivity --es cmd rec ...). Extras:
     * cmd = rec | stop | edit (look editor) | dumpcams (log every camera id) | refshot (RAW+ISP JPEG
     *   reference into files/ref/, see tools/refshot),
     * codec = HEVC_10 | HEVC_8 | AVC_8, bitrate = Mbps, fps = 24|25|30,
     * bake / superpixel / audio / wblock / simple = true|false, profile = APPLE_LOG | SLOG3 | LOGC3,
     * view = index into the view list, ae = AUTO | LOCKED | MANUAL | PRIORITY, ev = EV (float),
     * iso = ISO, shutter = 1/x denominator, af = CONTINUOUS | MANUAL, focus = diopters (float),
     * clean = 0..3, strength / sat / vib = percent (vib: -100..100),
     * tap = "x,y" in 0..1 image coordinates, lens = lens key (e.g. "0", "3", "5/4"),
     * rawlens = "open/physical" + zoom = ratio (stream an arbitrary route; not saved),
     * afverbose = true|false (log AF state every frame).
     */
    private fun handleCommands(intent: Intent?) {
        // Launchers add their own extras (Xiaomi's sends e.g. "profile"); only adb-style intents
        // without the launcher category are commands.
        if (intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true) return
        val extras = intent?.extras ?: return
        if (recorder == null) {
            if (extras.containsKey("simple")) setSimple(extras.getBoolean("simple"))
            extras.getString("codec")?.let { codec = VideoCodec.valueOf(it) }
            if (extras.containsKey("bitrate")) bitrateMbps = extras.getInt("bitrate")
            if (extras.containsKey("bake")) bakeLut = extras.getBoolean("bake")
            if (extras.containsKey("superpixel")) setSuperpixel(extras.getBoolean("superpixel"))
            if (extras.containsKey("audio")) audioOn = extras.getBoolean("audio") &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            extras.getString("profile")?.let {
                profile = LogProfile.valueOf(it)
                renderer?.profile = profile
                applyView()
            }
            var c = capture
            if (extras.containsKey("fps")) c = c.copy(fps = extras.getInt("fps"))
            extras.getString("ae")?.let { c = c.copy(ae = AeMode.valueOf(it)) }
            if (extras.containsKey("iso")) c = c.copy(iso = extras.getInt("iso"))
            if (extras.containsKey("shutter")) c = c.copy(exposureNs = 1_000_000_000L / extras.getInt("shutter"))
            if (extras.containsKey("wblock")) c = c.copy(awbLock = extras.getBoolean("wblock"))
            extras.getString("af")?.let { c = c.copy(af = AfMode.valueOf(it), focusPoint = null) }
            if (extras.containsKey("focus")) c = c.copy(focusDiopters = extras.getFloat("focus"))
            if (c != capture) setCapture(c)
        }
        // Debug: stream an arbitrary route, e.g. rawlens=5/4 zoom=2.6 (not saved to the lens list).
        extras.getString("rawlens")?.takeIf { recorder == null }?.let { route ->
            val (openId, physId) = route.split("/").let { it[0] to it.getOrNull(1) }
            val cm = getSystemService(android.hardware.camera2.CameraManager::class.java)
            val c = cm.getCameraCharacteristics(physId ?: openId)
            val size = LensProbe.rawSize(c)!!
            val focal = c.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!![0]
            val sensor = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
            val lens = Lens(openId, physId, false, focal * 43.27f / kotlin.math.hypot(sensor.width, sensor.height), size.width, size.height,
                "test $route", extras.getFloat("zoom", 1f))
            lenses = lenses.filterNot { it.label.startsWith("test ") } + lens
            lensIndex = lenses.lastIndex
            restartPipeline()
        }
        extras.getString("lens")?.let { key ->
            lenses.indexOfFirst { it.key == key }.takeIf { it >= 0 && it != lensIndex && recorder == null }?.let {
                lensIndex = it
                prefs.edit().putString("lens", key).apply()
                restartPipeline()
            }
        }
        if (extras.containsKey("ev")) setExposureEv(extras.getFloat("ev"))
        if (extras.containsKey("clean")) {
            cleanup = extras.getInt("clean").coerceIn(0, 3)
            renderer?.cleanup = cleanup
        }
        if (extras.containsKey("view")) {
            viewIndex = extras.getInt("view").coerceIn(0, views.lastIndex)
            applyView()
        }
        if (extras.containsKey("strength")) strengthBar.progress = extras.getInt("strength")
        if (extras.containsKey("sat")) saturationBar.progress = extras.getInt("sat")
        if (extras.containsKey("vib")) vibranceBar.progress = extras.getInt("vib") + 100
        if (extras.containsKey("afverbose")) camera?.afVerbose = extras.getBoolean("afverbose")
        extras.getString("tap")?.split(",")?.map { it.toFloat() }?.let { (u, v) -> focusAt(u, v) }
        savePrefs()
        updateUi()
        when (extras.getString("cmd")) {
            // Give the camera a moment to start when launched and told to record in one go.
            "rec" -> info.postDelayed({ if (recorder == null) startRecording() }, 1500)
            "stop" -> stopRecording()
            "edit" -> info.postDelayed({ openLookEditor() }, 1500)
            "dumpcams" -> Thread { dumpCameras() }.start()
            "refshot" -> info.postDelayed({
                camera?.referenceCapture(File(getExternalFilesDir(null), "ref")) { msg ->
                    Log.i(TAG, msg)
                    runOnUiThread { lastResult = msg }
                }
            }, 1500)
        }
    }

    /** Debug: logs what every camera id (listed or hidden) reports about focus, ISO and routing. */
    private fun dumpCameras() {
        Diagnostics.cameraReport(this).lines().forEach { Log.i(TAG, it) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_PERMISSIONS) return
        permissions.zip(grantResults.toList()).forEach { (perm, result) ->
            val granted = result == PackageManager.PERMISSION_GRANTED
            when (perm) {
                Manifest.permission.CAMERA -> if (granted) findLensesThenStart() else info.text = "Camera permission is required."
                Manifest.permission.RECORD_AUDIO -> audioOn = granted
            }
        }
        updateUi()
    }

    companion object {
        private const val TAG = "AuthRec"
        private const val REQ_PERMISSIONS = 1
        private const val REQ_PICK_LUT = 2
        private const val REQ_EDIT_LOOK = 3
        /** Fallback RAW log-average for AE: Priority if no measurement exists yet. */
        private const val DEFAULT_PRIORITY_TARGET = 0.025
        private const val RESCAN_ITEM = 10_000
        private const val DIAGNOSTICS_ITEM = 10_001
        private const val BENCH_ITEM = 10_002
    }
}
