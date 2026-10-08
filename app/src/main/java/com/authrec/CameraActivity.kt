package com.authrec

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
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
import com.authrec.ui.CameraUi
import com.authrec.ui.ClassicUi
import com.authrec.ui.GlassUi
import com.authrec.ui.Stepper
import com.authrec.ui.UiKit
import com.authrec.ui.dp
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Live open-gate RAW → log preview with real-time looks/LUTs, and recording to HEVC/H.264 with
 * audio. Two modes: Simple (look, tap to focus, record) and Pro (everything); two layouts
 * ([GlassUi], [ClassicUi]) over the same state and actions, which live here.
 *
 * Looks: built-in film-style presets, looks made in [LookEditorActivity], and imported .cube
 * files (in Android/data/com.authrec/files/luts/).
 *
 * Can also be driven over adb (see [handleCommands]), e.g.
 * adb shell am start -n com.authrec/.CameraActivity --es cmd rec --es codec HEVC_10 --ei bitrate 150
 */
class CameraActivity : Activity() {

    /** Entries of the look menu. */
    internal sealed class ViewEntry(val label: String) {
        object LogView : ViewEntry("Log")
        class BuiltIn(val look: Look) : ViewEntry(look.name)
        class UserLook(val look: Look) : ViewEntry("★ ${look.name}")
        class CubeFile(val file: File) : ViewEntry("LUT ${file.nameWithoutExtension}")
    }

    private val cameraThread = HandlerThread("camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    internal val prefs by lazy { getSharedPreferences("authrec", MODE_PRIVATE) }

    internal var camera: RawCamera? = null
        private set
    private var renderer: Renderer? = null
    private var surface: Triple<SurfaceHolder, Int, Int>? = null
    private var displayQuarterTurns = 0

    internal var capture = CaptureSettings()
        private set
    internal var profile = LogProfile.APPLE_LOG
        private set
    internal var views: List<ViewEntry> = listOf(ViewEntry.LogView)
        private set
    internal var viewIndex = 1
        private set
    internal var codec = VideoCodec.HEVC_10
        private set
    internal var bitrateMbps = 150
        private set
    internal var bakeLut = false
        private set
    internal var superpixel = false
        private set
    internal var audioOn = true
        private set
    internal var simple = true
        private set
    /** See [Renderer.cleanup]. */
    internal var cleanup = 2
        private set
    /**
     * eDR (extended dynamic range of the view; not HDR): a tone curve that pulls the highlights
     * down and lifts the shadows before the look/LUT (see [Renderer.toneHighlights]). Xiaomi's AE
     * exposes for the sky and lets its ISP lift the rest; with one global gain our sky goes white
     * in the view while the log keeps it. Simple switches it on and off; Pro shapes it with
     * [edrHighlights] / [edrShadows] (−100..100) on the curve graph.
     */
    internal var edrOn = false
        private set
    internal var edrHighlights = EDR_HIGHLIGHTS
        private set
    internal var edrShadows = EDR_SHADOWS
        private set
    /** View adjustments: LUT strength 0..100 %, saturation 0..200 %, vibrance −100..100. */
    internal var lutStrength = 100
        private set
    internal var saturationPct = 100
        private set
    internal var vibrancePct = 0
        private set
    /** White balance trim on top of the ISP's AWB, −100..100 each (see [setWbShift]). */
    internal var wbWarmth = 0
        private set
    internal var wbTint = 0
        private set
    /**
     * Front camera as a mirror image (selfie style), on the preview and in the recording. Off, it
     * shows and records what the camera sees (text reads the right way round).
     */
    internal var mirrorFront = false
        private set
    /** Shape of the recorded frame: a centre crop of the 4:3 sensor image, or all of it. */
    internal enum class FrameAspect(val label: String, val ratio: Float) {
        OPEN_GATE("4:3", 4f / 3f), WIDE("16:9", 16f / 9f), UNIVISIUM("2:1", 2f), SCOPE("2.39:1", 2.39f)
    }
    internal var aspect = FrameAspect.OPEN_GATE
        private set

    /** The original layout instead of Glass (Settings → Interface). */
    internal var classicUi = false
        private set
    /**
     * The one exposure control (the slider, and the classic layout's −/+). AUTO/LOCKED: real
     * sensor exposure via AE compensation, any remainder below one AE step as digital gain.
     * PRIORITY: shifts our loop's brightness target. MANUAL: digital gain (ISO/shutter are yours).
     */
    internal var exposureEv = 0f
        private set

    /** Limits for AE: Priority. Shutter in ns (fastest = shortest). */
    private data class PriorityLimits(
        val fastestNs: Long = 1_000_000_000L / 60,
        val slowestNs: Long = 1_000_000_000L / 30,
        val isoMin: Int = 50,
        val isoMax: Int = 800,
        val preferLowIso: Boolean = true,
    )
    private var limits = PriorityLimits()

    internal var lenses: List<Lens> = emptyList()
        private set
    internal var lensIndex = 0
        private set
    internal var scanning = false
        private set
    private var pendingScan = false
    /** Read by the lens scan's thread to stop when we leave the foreground. */
    @Volatile private var resumed = false

    /** Failures of the current lens since it last streamed properly; drives retries and fallbacks. */
    private var cameraFailures = 0
    /** [RawCamera.variant] for the current lens; steps down when a layout fails before any frame. */
    private var sessionVariant = 0
    private var retryPending = false
    private var retryWaitStartMs = 0L

    /** Cameras the camera service reports busy or gone (e.g. while it restarts after a HAL crash). */
    private val unavailableCameras = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val availabilityCallback = object : android.hardware.camera2.CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(id: String) { unavailableCameras -= id }
        override fun onCameraUnavailable(id: String) { unavailableCameras += id }
    }

    private var recorder: Recorder? = null
    internal var recordStartMs = 0L
        private set
    internal var stopping = false
        private set
    private var lastResult: String? = null

    /** A clip is being recorded (past the pre-roll that follows pressing REC, see [startRecording]). */
    internal val recording get() = recorder != null && !warmingUp
    /** Frames lost while recording (from the renderer's stats). */
    internal var droppedFrames = 0
        private set

    // Views both layouts place; the rest belongs to [ui].
    internal lateinit var surfaceView: SurfaceView
        private set
    internal lateinit var info: TextView
        private set
    internal lateinit var focusSquare: View
        private set
    internal lateinit var exposureSlider: ExposureSlider
        private set
    internal lateinit var focusBar: LinearLayout
        private set
    private lateinit var focusSeek: SeekBar
    private lateinit var focusLabel: TextView
    internal lateinit var priorityPanel: LinearLayout
        private set
    private lateinit var ui: CameraUi
    /** Style of the shared panels (priority limits, focus bar), set by [buildUi]. */
    internal lateinit var kit: UiKit
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this)
        EventLog.log("App ${packageManager.getPackageInfo(packageName, 0).versionName} started on ${android.os.Build.MODEL}")
        getSystemService(android.hardware.camera2.CameraManager::class.java).registerAvailabilityCallback(availabilityCallback, Handler(mainLooper))
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
        // A recreate (layout switch) keeps the launching intent; its adb commands already ran.
        if (savedInstanceState == null) handleCommands(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCommands(intent)
    }

    // ---- Preferences ----

    private fun loadPrefs() {
        simple = prefs.getBoolean("simple", true)
        classicUi = prefs.getString("uiStyle", "glass") == "classic"
        aspect = runCatching { FrameAspect.valueOf(prefs.getString("aspect", null)!!) }.getOrDefault(FrameAspect.OPEN_GATE)
        profile = runCatching { LogProfile.valueOf(prefs.getString("profile", null)!!) }.getOrDefault(LogProfile.APPLE_LOG)
        codec = runCatching { VideoCodec.valueOf(prefs.getString("codec", null)!!) }.getOrDefault(VideoCodec.HEVC_10)
        bitrateMbps = prefs.getInt("bitrate", 150)
        bakeLut = prefs.getBoolean("recordLook", true)
        cleanup = prefs.getInt("cleanup", 2)
        if (prefs.contains("edrOn")) {
            edrOn = prefs.getBoolean("edrOn", false)
            edrHighlights = prefs.getInt("edrHi", EDR_HIGHLIGHTS)
            edrShadows = prefs.getInt("edrLo", EDR_SHADOWS)
        } else {
            // 0.2.2's "Balance" (toneHi / toneLo); its old default becomes eDR's slightly stronger one.
            val hi = prefs.getInt("toneHi", 0)
            val lo = prefs.getInt("toneLo", 0)
            edrOn = hi != 0 || lo != 0
            if (edrOn && !(hi == -60 && lo == 30)) {
                edrHighlights = hi
                edrShadows = lo
            }
        }
        lutStrength = prefs.getInt("lutStrength", 100)
        saturationPct = prefs.getInt("saturation", 100)
        vibrancePct = prefs.getInt("vibrance", 0)
        wbWarmth = prefs.getInt("wbWarm", 0)
        wbTint = prefs.getInt("wbTint", 0)
        mirrorFront = prefs.getBoolean("mirrorFront", false)
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
            .putString("uiStyle", if (classicUi) "classic" else "glass")
            .putString("aspect", aspect.name)
            .putString("profile", profile.name)
            .putString("codec", codec.name)
            .putInt("bitrate", bitrateMbps)
            .putBoolean("recordLook", bakeLut)
            .putInt("cleanup", cleanup)
            .putBoolean("edrOn", edrOn)
            .putInt("edrHi", edrHighlights)
            .putInt("edrLo", edrShadows)
            .remove("toneHi")
            .remove("toneLo")
            .putInt("lutStrength", lutStrength)
            .putInt("saturation", saturationPct)
            .putInt("vibrance", vibrancePct)
            .putInt("wbWarm", wbWarmth)
            .putInt("wbTint", wbTint)
            .putBoolean("mirrorFront", mirrorFront)
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

    /**
     * Builds the views both layouts share (preview with its gestures, info text, focus square,
     * EV slider, manual focus bar, priority limits), then the chosen layout around them.
     */
    private fun buildUi() {
        kit = UiKit(this, glass = !classicUi)
        surfaceView = SurfaceView(this)
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
        // Tap: focus and meter there, and keep tracking it. Long-press: lock focus there.
        // Double-tap: back to automatic focus on the whole scene.
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { onPreviewTap(e.x, e.y, lock = false); return true }
            override fun onLongPress(e: MotionEvent) = onPreviewTap(e.x, e.y, lock = true)
            override fun onDoubleTap(e: MotionEvent): Boolean { resetFocusToAuto(); return true }
        })
        surfaceView.setOnTouchListener { _, e -> gestures.onTouchEvent(e); true }

        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            textSize = if (classicUi) 12f else 11.5f
        }
        focusSquare = View(this).apply {
            background = GradientDrawable().apply { setStroke(4, Color.WHITE) }
            visibility = View.GONE
        }
        exposureSlider = ExposureSlider(this, glass = !classicUi) { ev -> setExposureEv(ev, fromSlider = true) }
        focusBar = buildFocusBar()
        priorityPanel = buildPriorityPanel()

        ui = if (classicUi) ClassicUi(this) else GlassUi(this)
        setContentView(ui.root)
        updateUi()
    }

    private fun buildFocusBar(): LinearLayout {
        focusLabel = kit.label(size = 12f).apply { minWidth = dp(76) }
        focusSeek = kit.seekBar(1000, 0).apply {
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
        return kit.panel().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (classicUi) setPadding(32, 8, 32, 8)
            addView(focusLabel)
            addView(focusSeek, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }

    internal fun setExposureEv(ev: Float, fromSlider: Boolean = false) {
        exposureEv = (Math.round(ev * 10) / 10f).coerceIn(-EV_RANGE, EV_RANGE)
        applyExposure()
        updateUi()
        // While dragging, save once the finger has settled.
        info.removeCallbacks(saveSoon)
        if (fromSlider) info.postDelayed(saveSoon, 1000) else savePrefs()
    }

    private val saveSoon = Runnable { savePrefs() }

    /** Saves a second after the last change (sliders send many). */
    private fun saveSoon() {
        info.removeCallbacks(saveSoon)
        info.postDelayed(saveSoon, 1000)
    }

    /** eDR on/off with the current curve. */
    internal fun setMirrorFront(on: Boolean) {
        mirrorFront = on
        renderer?.mirror = mirrored()
        updateUi()
        saveSoon()
    }

    /** Whether the current lens is shown mirrored. */
    private fun mirrored() = mirrorFront && camera?.info?.front == true

    internal fun setEdr(on: Boolean) {
        edrOn = on
        applyTone()
        updateUi()
        saveSoon()
    }

    /** Shapes the eDR curve (both −100..100) and switches it on. */
    internal fun setEdrCurve(highlights: Int, shadows: Int) {
        edrHighlights = highlights.coerceIn(-100, 100)
        edrShadows = shadows.coerceIn(-100, 100)
        edrOn = true
        applyTone()
        updateUi()
        saveSoon()
    }

    internal fun resetEdrCurve() = setEdrCurve(EDR_HIGHLIGHTS, EDR_SHADOWS)

    private fun applyTone() {
        renderer?.toneHighlights = if (edrOn) edrHighlights / 100f else 0f
        renderer?.toneShadows = if (edrOn) edrShadows / 100f else 0f
    }

    /** LUT strength (0..100 %), saturation (0..200 %), vibrance (−100..100) of the view. */
    internal fun setLookAdjust(strength: Int = lutStrength, saturation: Int = saturationPct, vibrance: Int = vibrancePct) {
        lutStrength = strength.coerceIn(0, 100)
        saturationPct = saturation.coerceIn(0, 200)
        vibrancePct = vibrance.coerceIn(-100, 100)
        applyLookAdjust()
        updateUi()
        saveSoon()
    }

    private fun applyLookAdjust() {
        renderer?.lutStrength = lutStrength / 100f
        renderer?.saturation = saturationPct / 100f
        renderer?.vibrance = vibrancePct / 100f
    }

    /**
     * White balance trim on top of the ISP's AWB (Xiaomi's leans green or magenta now and then):
     * warmth ±100 = about ±0.5 EV between red and blue, tint ±100 = ±0.25 EV of green (+ = magenta).
     * Applied like the ISP's own gains, so it's in the recorded log as well.
     */
    internal fun setWbShift(warmth: Int, tint: Int) {
        wbWarmth = warmth.coerceIn(-100, 100)
        wbTint = tint.coerceIn(-100, 100)
        applyWbShift()
        updateUi()
        saveSoon()
    }

    private fun applyWbShift() {
        val w = Math.pow(2.0, wbWarmth / 100.0 * 0.25).toFloat()
        val g = Math.pow(2.0, -wbTint / 100.0 * 0.25).toFloat()
        renderer?.wbShift = floatArrayOf(w, g, g, 1f / w)
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

    internal fun hasAfLens() = (camera?.info?.minFocusDiopters ?: 1f) > 0f

    /** Manual focus (Pro, on a lens that can focus): the focus bar and peaking show. */
    internal val manualFocus get() = capture.af == AfMode.MANUAL && !simple && hasAfLens()

    /** The AF state as the controls name it. */
    internal fun afLabel() = when (capture.af) {
        AfMode.CONTINUOUS -> "AF: Auto"
        AfMode.TAP -> if (tapTracking) "AF: Auto" else "AF: Locked"
        AfMode.MANUAL -> "MF"
        AfMode.SOFTWARE -> if (softContinuous) "AF: Auto" else "AF: Locked"
    }

    internal fun aeLabel() = when (capture.ae) {
        AeMode.AUTO -> "AE: Auto"
        AeMode.LOCKED -> "AE: Locked"
        AeMode.MANUAL -> "Manual"
        AeMode.PRIORITY -> "AE: Priority"
    }

    internal fun cleanupLabel() = when (cleanup) {
        0 -> "Clean: off"
        1 -> "Clean: pixels"
        2 -> "Clean: + colour"
        else -> "Clean: + colour+"
    }

    internal fun currentView() = views.getOrNull(viewIndex)

    /** Where the Glass layout's controls are (surface px), for the blurred backdrop behind them. */
    private var glassRects: List<com.authrec.gl.GlassRect> = emptyList()

    internal fun setGlassRects(rects: List<com.authrec.gl.GlassRect>) {
        glassRects = rects
        renderer?.glassRects = rects
    }

    /** eDR shapes looks and LUTs; the plain log view shows the log as recorded. */
    internal fun edrAvailable() = currentView() !is ViewEntry.LogView

    internal fun updateUi() {
        if (!::ui.isInitialized) return
        val mf = manualFocus
        focusBar.visibility = if (mf) View.VISIBLE else View.GONE
        if (mf) {
            val min = camera?.info?.minFocusDiopters ?: 0f
            if (min > 0) focusSeek.progress = (kotlin.math.sqrt(capture.focusDiopters / min) * 1000).toInt()
            updateFocusLabel()
        }
        renderer?.peaking = mf
        if (mf) focusSquare.visibility = View.GONE
        exposureSlider.setValue(exposureEv)
        exposureSlider.sensorRange = when (capture.ae) {
            // Beyond the AE compensation range the rest is digital gain.
            AeMode.AUTO, AeMode.LOCKED -> camera?.info?.let { it.evRange.lower * it.evStep..it.evRange.upper * it.evStep } ?: -EV_RANGE..EV_RANGE
            AeMode.PRIORITY -> -EV_RANGE..EV_RANGE
            AeMode.MANUAL -> 0f..0f // all digital gain: ISO and shutter are set by hand
        }
        priorityPanel.visibility = if (!simple && capture.ae == AeMode.PRIORITY && priorityPanelOpen) View.VISIBLE else View.GONE
        updatePriorityPanel()
        ui.update()
    }

    internal fun settingsChanged() {
        savePrefs()
        updateUi()
    }

    internal fun setSimple(on: Boolean) {
        simple = on
        // Simple mode is fully automatic.
        if (on) setCapture(capture.copy(ae = AeMode.AUTO, af = AfMode.CONTINUOUS, focusPoint = null, evSteps = 0, awbLock = false))
        settingsChanged()
    }

    /** Switches between the Glass and Classic layouts (the screen is rebuilt; the camera reopens). */
    internal fun setClassicUi(on: Boolean) {
        if (on == classicUi || recording) return
        classicUi = on
        savePrefs()
        recreate()
    }

    internal fun cycleFps() = setCapture(capture.copy(fps = when (capture.fps) { 24 -> 25; 25 -> 30; else -> 24 }))
    internal fun setFps(fps: Int) = setCapture(capture.copy(fps = fps))
    internal fun cycleCodec() = setCodec(VideoCodec.entries[(codec.ordinal + 1) % VideoCodec.entries.size])
    internal fun setCodec(c: VideoCodec) { codec = c; settingsChanged() }
    internal fun cycleBitrate() = setBitrate(when (bitrateMbps) { 50 -> 100; 100 -> 150; else -> 50 })
    internal fun setBitrate(mbps: Int) { bitrateMbps = mbps; settingsChanged() }
    internal fun setBake(on: Boolean) { bakeLut = on; settingsChanged() }
    internal fun cycleCleanup() = setCleanup((cleanup + 1) % 4)
    internal fun setCleanup(level: Int) {
        cleanup = level.coerceIn(0, 3)
        renderer?.cleanup = cleanup
        settingsChanged()
    }
    internal fun toggleAwbLock() = setCapture(capture.copy(awbLock = !capture.awbLock))
    internal fun cycleAspect() = setAspect(FrameAspect.entries[(aspect.ordinal + 1) % FrameAspect.entries.size])
    internal fun setAspect(a: FrameAspect) {
        aspect = a
        applyAspect()
        settingsChanged()
    }

    private fun applyAspect() {
        val cam = camera ?: return
        renderer?.frameCrop = frameSize(cam).third
    }

    /**
     * Recorded frame for the current resolution and [aspect]: width, height (multiples of 16, which
     * every encoder takes) and the share of the image's height it keeps.
     */
    internal fun frameSize(cam: RawCamera): Triple<Int, Int, Float> {
        val w = if (superpixel) cam.info.size.width / 2 else cam.info.size.width
        val h = if (superpixel) cam.info.size.height / 2 else cam.info.size.height
        if (aspect.ratio <= w.toFloat() / h + 0.01f) return Triple(w, h, 1f)
        val cropH = (Math.round(w / aspect.ratio / 16f) * 16).coerceAtMost(h)
        return Triple(w, cropH, cropH.toFloat() / h)
    }
    internal fun togglePriorityPanel() {
        priorityPanelOpen = !priorityPanelOpen
        updateUi()
    }


    // ---- Focus ----

    private fun onPreviewTap(x: Float, y: Float, lock: Boolean) {
        // A tap on the image first dismisses any open panel.
        val closed = ui.dismissPanels()
        if (priorityPanelOpen || closed) {
            priorityPanelOpen = false
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
        if (mirrored()) u = 1 - u
        repeat(displayQuarterTurns) { val t = u; u = v; v = 1 - t }

        focusAt(u, v, lock)
        // Stays while focus follows (white, dimmed after a moment) or is locked (yellow) at that spot.
        focusSquare.apply {
            translationX = x - 70
            translationY = y - 70
            (background as GradientDrawable).setStroke(if (lock) 6 else 4, if (lock) Color.YELLOW else Color.WHITE)
            animate().cancel()
            alpha = 1f
            visibility = View.VISIBLE
            animate().alpha(if (lock) 1f else 0.45f).setStartDelay(1200).setDuration(400).start()
        }
        if (lock) performHapticFeedbackCompat()
        updateUi()
    }

    private fun performHapticFeedbackCompat() =
        window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

    /** Double-tap: focus (and metering) back to the whole scene, automatic. */
    internal fun resetFocusToAuto() {
        focusSquare.visibility = View.GONE
        if (capture.af == AfMode.MANUAL && !simple) return
        stopSpotWatch()
        softAf?.cancel()
        if (hasAf() && usesSoftwareAf()) {
            runSoftwareAf(0.5f, 0.5f, continuous = true, meter = false)
        } else {
            setCapture(capture.copy(af = AfMode.CONTINUOUS, focusPoint = null))
        }
    }

    private var softAf: SoftwareAf? = null
    /** On a contrast-AF lens: true = "AF: Auto" (keeps refocusing), false = "AF: Locked" (holds the tapped spot). */
    private var softContinuous = true
    /** TAP on an ISP-AF lens: true = following the spot (shown as "AF: Auto"), false = locked there. */
    private var tapTracking = false
    private var spotWatch: Runnable? = null

    private fun softAfKey() = "softAf:${lenses.getOrNull(lensIndex)?.key}"

    /** True once we've seen this lens ignore the ISP's autofocus. */
    private fun usesSoftwareAf() = camera?.zoomRouted != true && prefs.getBoolean(softAfKey(), false)

    private fun hasAf() = (camera?.info?.minFocusDiopters ?: 0f) > 0f

    /**
     * Focus and meter at image point (u, v). [lock] = false: continuous AF keeps following that
     * spot (the mode stays "AF: Auto"); true: focus once there and hold ("AF: Locked").
     * Uses the ISP's AF; a lens whose ISP ignores the AF trigger (its state stays inactive) is
     * switched to our contrast AF for good. Zoom-routed lenses always use contrast AF for a spot:
     * their native AF ignores regions and triggers.
     */
    private fun focusAt(u: Float, v: Float, lock: Boolean = false) {
        val cam = camera ?: return
        if (hasAf() && (usesSoftwareAf() || cam.zoomRouted)) {
            runSoftwareAf(u, v, continuous = !lock)
            return
        }
        softAf?.cancel()
        if (!hasAf()) {
            // Fixed focus: the spot only steers auto exposure.
            capture = capture.copy(focusPoint = PointF(u, v))
            cam.update(capture)
            updateUi()
            return
        }
        // The ISP's continuous AF ignores spots (Xiaomi 14: regions and cancel triggers change
        // nothing), so its fast one-shot AF focuses there; to follow the spot, it's triggered
        // again whenever the spot goes soft.
        tapTracking = !lock
        capture = capture.copy(af = AfMode.TAP, focusPoint = PointF(u, v))
        cam.tapToFocus(capture)
        updateUi()
        info.postDelayed({
            if (capture.af == AfMode.TAP && camera === cam && cam.latestMeta?.afState == 0) {
                EventLog.log("Lens ${lenses.getOrNull(lensIndex)?.key} ignores AF triggers; using contrast AF")
                prefs.edit().putBoolean(softAfKey(), true).apply()
                runSoftwareAf(u, v, continuous = !lock)
            }
        }, 900)
        if (!lock) watchSpot(cam, u, v)
    }

    /**
     * Keeps a tapped spot sharp with the ISP's one-shot AF: once focus has locked, the spot's
     * sharpness becomes the baseline; if it stays well below that for half a second (the subject
     * moved, or the framing changed), AF is triggered again at the same spot.
     */
    private fun watchSpot(cam: RawCamera, u: Float, v: Float) {
        stopSpotWatch()
        val r = renderer ?: return
        val half = 0.07f
        r.sharpnessRegion = floatArrayOf(u - half, v - half, u + half, v + half)
        var triggeredAt = SystemClock.elapsedRealtime()
        var waitingForLock = true
        var baseline = 0f
        var samples = 0
        var low = 0
        var ignoreUntil = 0L
        var lastFrame = r.frameCounter
        val tick = object : Runnable {
            override fun run() {
                if (spotWatch !== this) return
                if (camera !== cam || renderer !== r || capture.af != AfMode.TAP || !tapTracking) {
                    stopSpotWatch()
                    return
                }
                val now = SystemClock.elapsedRealtime()
                val state = cam.latestMeta?.afState ?: 0
                if (waitingForLock) {
                    // 4/5 = (not) focused, locked. Measure only frames taken after the lens settled.
                    if (state == 4 || state == 5 || now - triggeredAt > 3000) {
                        waitingForLock = false
                        baseline = 0f
                        samples = 0
                        low = 0
                        ignoreUntil = r.frameCounter + 3
                    }
                } else if (r.frameCounter != lastFrame && r.frameCounter > ignoreUntil) {
                    lastFrame = r.frameCounter
                    val s = r.sharpness
                    when {
                        samples < 5 -> { baseline += s / 5; samples++ }
                        s < baseline * 0.6f -> if (++low >= 15) {
                            Log.d(TAG, "spot went soft (%.5f vs %.5f); refocusing".format(s, baseline))
                            cam.tapToFocus(capture)
                            triggeredAt = now
                            waitingForLock = true
                        }
                        else -> {
                            low = 0
                            baseline = baseline * 0.98f + s * 0.02f
                        }
                    }
                }
                info.postDelayed(this, 30)
            }
        }
        spotWatch = tick
        info.postDelayed(tick, 100)
    }

    private fun stopSpotWatch() {
        spotWatch?.let { info.removeCallbacks(it) }
        if (spotWatch != null && softAf?.isSearching != true) renderer?.sharpnessRegion = null
        spotWatch = null
    }

    /** [meter]: also weight auto exposure to the spot (not for the default centre AF). */
    private fun runSoftwareAf(u: Float, v: Float, continuous: Boolean, meter: Boolean = true) {
        val cam = camera ?: return
        val r = renderer ?: return
        stopSpotWatch()
        softAf?.cancel()
        softContinuous = continuous
        capture = capture.copy(af = AfMode.SOFTWARE, focusPoint = if (meter) PointF(u, v) else null)
        // Centre AF looks at a bigger area so it isn't fooled by one small detail; a tapped spot
        // a small one (it used to get the centre's 24 % box when following, so busy background
        // around a small subject decided where focus went).
        val half = if (meter) TAP_AF_HALF else 0.12f
        softAf = SoftwareAf(
            Handler(mainLooper), r, cam.info.minFocusDiopters,
            sensorClockNs = { if (cam.info.timestampsAreBoottime) SystemClock.elapsedRealtimeNanos() else System.nanoTime() },
            moveLens = { d -> capture = capture.copy(focusDiopters = d); cam.update(capture) },
            onLocked = { d -> Log.i(TAG, "contrast AF locked at %.2f diopters (%.2f m)".format(d, if (d > 0) 1 / d else Float.POSITIVE_INFINITY)) },
        ).also { it.start(floatArrayOf(u - half, v - half, u + half, v + half), continuous, spot = meter) }
        updateUi()
    }

    /** Auto ⇄ MF (a tap anywhere gives Tap). Same on every lens, whoever runs the autofocus. */
    internal fun cycleAf() {
        val soft = usesSoftwareAf()
        Log.d(TAG, "cycleAf from ${capture.af} soft=$soft continuous=$softContinuous")
        focusSquare.visibility = View.GONE
        stopSpotWatch()
        if (capture.af == AfMode.MANUAL) {
            if (soft) {
                runSoftwareAf(0.5f, 0.5f, continuous = true, meter = false)
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
        stopSpotWatch()
        softAf?.cancel()
        softAf = null
        focusSquare.visibility = View.GONE
        capture = capture.copy(af = AfMode.CONTINUOUS, focusPoint = null)
        if (hasAf() && usesSoftwareAf()) {
            softContinuous = true
            capture = capture.copy(af = AfMode.SOFTWARE)
            // Give the stream a moment to start before the first sweep.
            info.postDelayed({ if (capture.af == AfMode.SOFTWARE && softContinuous) runSoftwareAf(0.5f, 0.5f, continuous = true, meter = false) }, 1200)
        }
    }

    // ---- Exposure ----

    internal fun setCapture(newSettings: CaptureSettings, updateUiNow: Boolean = true) {
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

    internal fun cycleAe() = setAe(when (capture.ae) {
        AeMode.AUTO -> AeMode.PRIORITY
        AeMode.PRIORITY -> AeMode.LOCKED
        AeMode.LOCKED -> AeMode.MANUAL
        AeMode.MANUAL -> AeMode.AUTO
    })

    internal fun setAe(next: AeMode) {
        if (next == capture.ae) return
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

    /** ISO by hand (the Glass ISO dial): manual exposure, keeping the shutter auto exposure chose. */
    internal fun setManualIso(iso: Int) {
        setAe(AeMode.MANUAL)
        setCapture(capture.copy(iso = iso))
    }

    /** Shutter by hand (the Glass shutter dial): manual exposure, keeping the ISO auto exposure chose. */
    internal fun setManualShutter(ns: Long) {
        setAe(AeMode.MANUAL)
        setCapture(capture.copy(exposureNs = ns))
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
    internal var priorityPanelOpen = false

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
        prioFast = Stepper(kit) { d ->
            val v = stepShutterLimit(limits.fastestNs, d)
            limits = limits.copy(fastestNs = v, slowestNs = maxOf(limits.slowestNs, v))
            limitsChanged()
        }
        prioSlow = Stepper(kit) { d ->
            val v = stepShutterLimit(limits.slowestNs, d)
            limits = limits.copy(slowestNs = v, fastestNs = minOf(limits.fastestNs, v))
            limitsChanged()
        }
        prioIsoMin = Stepper(kit) { d ->
            val v = stepIsoLimit(limits.isoMin, d)
            limits = limits.copy(isoMin = v, isoMax = maxOf(limits.isoMax, v))
            limitsChanged()
        }
        prioIsoMax = Stepper(kit) { d ->
            val v = stepIsoLimit(limits.isoMax, d)
            limits = limits.copy(isoMax = v, isoMin = minOf(limits.isoMin, v))
            limitsChanged()
        }
        prioPrefer = kit.button("") {
            limits = limits.copy(preferLowIso = !limits.preferLowIso)
            limitsChanged()
        }
        fun row(label: String, a: Stepper, b: Stepper) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(kit.label(label).apply { minWidth = dp(56) })
            a.addTo(this)
            addView(kit.label("  to  ", dim = true))
            b.addTo(this)
        }
        return kit.panel().apply {
            if (classicUi) setPadding(24, 12, 24, 12)
            visibility = View.GONE
            addView(row("Shutter", prioFast, prioSlow))
            addView(row("ISO", prioIsoMin, prioIsoMax))
            addView(LinearLayout(this@CameraActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(prioPrefer)
                addView(kit.button("✕ Done") { priorityPanelOpen = false; updateUi() })
            })
        }
    }

    private fun limitsChanged() {
        savePrefs()
        updatePriorityPanel()
    }

    private fun updatePriorityPanel() {
        if (!::prioPrefer.isInitialized) return
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

    internal fun stepEv(dir: Int) {
        val step = camera?.info?.evStep ?: (1f / 3)
        setExposureEv(exposureEv + dir * step)
    }

    internal fun stepIso(dir: Int) {
        val stops = isoStops()
        val i = stops.indexOfFirst { it >= capture.iso }.let { if (it < 0) stops.lastIndex else it }
        setCapture(capture.copy(iso = stops[(i + dir).coerceIn(0, stops.lastIndex)]))
    }

    /** dir +1 = faster shutter. */
    internal fun stepShutter(dir: Int) {
        val stops = shutterStops() // longest first
        val i = stops.indexOfFirst { it <= capture.exposureNs }.let { if (it < 0) stops.lastIndex else it }
        setCapture(capture.copy(exposureNs = stops[(i + dir).coerceIn(0, stops.lastIndex)]))
    }

    internal fun isoStops(): List<Int> {
        val range = camera?.info?.isoRange ?: return listOf(capture.iso)
        val stops = listOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400)
        return (stops.filter { it in range.lower..range.upper } + range.upper).distinct().sorted()
    }

    /** Shutter times that fit in one frame at the current fps, longest first; includes 180° (1/2fps). */
    internal fun shutterStops(): List<Long> {
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
        updateUi()
        Thread {
            val found = runCatching { probe.scan({ msg -> runOnUiThread { info.text = msg } }, keepGoing = { resumed }) }
                .onFailure { EventLog.log("Lens scan failed", it) }
                .getOrDefault(emptyList())
            runOnUiThread {
                scanning = false
                if (found == null) {
                    // We went to the background mid-scan; onResume starts it again.
                    pendingScan = true
                    if (resumed) findLensesThenStart(forceScan = true)
                    return@runOnUiThread
                }
                lenses = found
                selectSavedLens()
                cameraFailures = 0
                sessionVariant = 0
                note("Found ${found.size} lens${if (found.size == 1) "" else "es"}: " + found.joinToString { it.label })
                setupPipeline()
                if (resumed) camera?.open(capture)
                updateUi()
            }
        }.start()
    }

    private fun selectSavedLens() {
        val key = prefs.getString("lens", null)
        lensIndex = lenses.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: mainLensIndex() ?: 0
        // Prefer the native-AF route over its backup.
        lenses.getOrNull(lensIndex)?.let { l -> twinOf(l)?.takeIf { l.zoomRatio == 1f }?.let { lensIndex = lenses.indexOf(it) } }
    }

    /** The 1x lens (or failing that the first back lens): where we retreat when a lens keeps failing. */
    private fun mainLensIndex(): Int? = lenses.indexOfFirst { it.label == "1x" }.takeIf { it >= 0 }
        ?: lenses.indexOfFirst { !it.front }.takeIf { it >= 0 }

    /** Zoom routes whose native AF failed this session (we're on their backup). */
    private val nativeFailed = mutableSetOf<String>()

    /** The other route to the same sensor: zoom route ⇄ direct backup. */
    private fun twinOf(l: Lens): Lens? = if (l.zoomRatio != 1f) {
        lenses.firstOrNull { it.zoomRatio == 1f && it.physicalId == null && it.openId == l.physicalId }
    } else {
        lenses.firstOrNull { it.zoomRatio != 1f && it.physicalId == l.openId }
    }

    /** Backups only show in the menu while their native route has failed (or they're in use). */
    internal fun inMenu(i: Int): Boolean {
        val l = lenses[i]
        val native = twinOf(l)?.takeIf { l.zoomRatio == 1f } ?: return true
        return i == lensIndex || native.key in nativeFailed
    }

    /** Switches a failing zoom route to its direct backup. Returns false if there is none. */
    private fun fallBackToBackup(reason: String): Boolean {
        val current = lenses.getOrNull(lensIndex) ?: return false
        if (current.zoomRatio == 1f) return false
        val backup = twinOf(current) ?: return false
        EventLog.log("Native AF route ${current.key} failed ($reason); using backup ${backup.key}")
        nativeFailed += current.key
        stopRecording("Stopped: ${current.label} native AF route failed") // also cancels a warm-up
        lastResult = "${current.label}: native AF route stopped ($reason); switched to backup AF"
        switchLens(lenses.indexOf(backup), remember = false)
        return true
    }

    /** Lenses in menu order: back lenses by focal length, front last. */
    internal fun lensOrder(): List<Int> = lenses.indices.sortedWith(compareBy({ lenses[it].front }, { lenses[it].equivFocalMm })).filter { inMenu(it) }

    /** Drop-down of all working lenses (back lenses in zoom order, front last) plus a rescan. */
    internal fun showLensMenu(anchor: View = ui.lensAnchor) {
        if (recorder != null || stopping) return
        val menu = android.widget.PopupMenu(this, anchor)
        // Lenses can't be switched mid-scan, but a report can always be sent.
        val order = if (scanning) emptyList() else lenses.indices.sortedWith(compareBy({ lenses[it].front }, { lenses[it].equivFocalMm }))
        order.filter { inMenu(it) }.forEachIndexed { pos, i ->
            val l = lenses[i]
            val mark = if (i == lensIndex) "● " else ""
            menu.menu.add(0, i, pos, "$mark${l.label}  ·  ${"%.0f".format(l.equivFocalMm)} mm")
        }
        if (!scanning) menu.menu.add(1, RESCAN_ITEM, order.size, "Rescan lenses")
        menu.menu.add(1, DIAGNOSTICS_ITEM, order.size + 1, "Send diagnostics…")
        if (!scanning) menu.menu.add(1, BENCH_ITEM, order.size + 2, "Run capability bench")
        menu.menu.add(1, STYLE_ITEM, order.size + 3, if (classicUi) "Switch to the Glass layout" else "Switch to the Classic layout")
        menu.setOnMenuItemClickListener { item ->
            when {
                item.itemId == RESCAN_ITEM -> rescanLenses()
                item.itemId == DIAGNOSTICS_ITEM -> sendDiagnostics()
                item.itemId == BENCH_ITEM -> openBench()
                item.itemId == STYLE_ITEM -> setClassicUi(!classicUi)
                item.itemId != lensIndex -> {
                    nativeFailed -= lenses[item.itemId].key
                    switchLens(item.itemId)
                }
            }
            true
        }
        menu.show()
    }

    internal fun sendDiagnostics() = Thread {
        runCatching { Diagnostics.share(this) }
            .onFailure { e -> runOnUiThread { lastResult = "Diagnostics failed: ${e.message}" } }
    }.start()

    internal fun openBench() = startActivity(Intent(this, com.authrec.bench.BenchActivity::class.java))

    /** Drop-down of every look and LUT. */
    internal fun showViewMenu(anchor: View = ui.lookAnchor) {
        val menu = android.widget.PopupMenu(this, anchor)
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
        // An imported LUT is built for one log format; ours gets converted to it before the lookup.
        val cube = views.getOrNull(viewIndex) as? ViewEntry.CubeFile
        if (cube != null && !simple) {
            val current = lutInputOf(cube.file)
            val sub = menu.menu.addSubMenu(4, LUT_INPUT_MENU, views.size, "LUT expects: ${current?.label ?: "our log as is"} ▸")
            sub.add(5, LUT_INPUT_BASE, 0, (if (current == null) "● " else "") + "Our log as is (no conversion)")
            LogProfile.entries.forEach { p -> sub.add(5, LUT_INPUT_BASE + 1 + p.ordinal, p.ordinal + 1, (if (current == p) "● " else "") + p.label) }
        }
        if (!simple) menu.menu.add(6, IMPORT_ITEM, views.size + 1, "Import a .cube LUT…")
        menu.setOnMenuItemClickListener { item ->
            when {
                item.itemId == LUT_INPUT_MENU -> return@setOnMenuItemClickListener false // opens the submenu
                item.itemId == IMPORT_ITEM -> pickLut()
                item.itemId >= LUT_INPUT_BASE -> {
                    val p = LogProfile.entries.getOrNull(item.itemId - LUT_INPUT_BASE - 1)
                    prefs.edit().putString("lutInput:${cube?.file?.name}", p?.name ?: "NONE").apply()
                    applyView()
                    updateUi()
                }
                else -> {
                    viewIndex = item.itemId
                    applyView()
                    settingsChanged()
                }
            }
            true
        }
        menu.show()
    }

    /**
     * The log format an imported LUT expects: what the user picked, else a guess from its file
     * name (most are named after their input, e.g. "…SLog3…"), else null (apply to our log as is).
     */
    private fun lutInputOf(file: File): LogProfile? {
        prefs.getString("lutInput:${file.name}", null)?.let { return runCatching { LogProfile.valueOf(it) }.getOrNull() }
        val name = file.name.lowercase().replace(Regex("[^a-z0-9]"), "")
        return when {
            "slog3" in name || "sgamut3" in name || "slog" in name -> LogProfile.SLOG3
            "logc" in name || "alexa" in name || "arri" in name || "awg" in name -> LogProfile.LOGC3
            "applelog" in name -> LogProfile.APPLE_LOG
            name.startsWith("tealmaxx") -> LogProfile.SLOG3 // bundled; built for S-Log3 input
            else -> null
        }
    }

    internal fun rescanLenses() {
        if (recorder != null || scanning) return
        EventLog.log("Rescan requested")
        teardownPipeline()
        findLensesThenStart(forceScan = true)
    }

    /** Switches to lens [index] with a fresh start (failure count, session layout). */
    internal fun switchLens(index: Int, remember: Boolean = true) {
        val l = lenses.getOrNull(index) ?: return
        EventLog.log("Switching to ${l.label} (${l.key})")
        lensIndex = index
        if (remember) prefs.edit().putString("lens", l.key).apply()
        cameraFailures = 0
        sessionVariant = 0
        restartPipeline()
    }

    /**
     * Frames stop going to the renderer first, then the renderer finishes its frame and lets go of
     * GL, and only then are the camera and its readers closed (on the camera thread, behind any
     * request still being built there). The next camera's open queues up behind that close.
     */
    private fun teardownPipeline() {
        info.removeCallbacks(retryCamera)
        retryPending = false
        camera?.reader?.setOnImageAvailableListener(null, null)
        renderer?.release()
        camera?.release()
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

    private val retryCamera: Runnable = object : Runnable {
        override fun run() {
            // While the camera service has the camera down (restarting HAL, device still closing),
            // an attempt would only fail again and count against the lens: wait for it, up to 10 s.
            val id = lenses.getOrNull(lensIndex)?.openId
            if (id != null && id in unavailableCameras && SystemClock.elapsedRealtime() - retryWaitStartMs < 10_000) {
                info.postDelayed(this, 300)
                return
            }
            retryPending = false
            if (resumed && !scanning) restartPipeline()
        }
    }

    /**
     * The camera failed or stopped delivering frames. Retries with growing pauses (a HAL that just
     * restarted, or a previous device still closing, usually recovers within seconds), steps to a
     * plainer session layout if one keeps failing before its first frame, and finally retreats
     * to the main lens, so a bad moment never leaves a frozen screen.
     */
    private fun onCameraFailure(cam: RawCamera, reason: String) {
        if (cam !== camera || retryPending) return // an old session's late news, or already handled
        // A stall seen by the watchdog leaves the device open; let go of it so it shows as available.
        cam.close()
        stopRecording("Stopped: camera problem ($reason)") // also cancels a warm-up
        if (!resumed) return // onResume reopens
        if (fallBackToBackup(reason)) return
        val lens = lenses.getOrNull(lensIndex)
        val noFrames = (renderer?.frameCounter ?: 0L) == 0L
        cameraFailures++
        if (noFrames && cameraFailures % 2 == 0 && sessionVariant < cam.lastVariant) {
            sessionVariant++
            EventLog.log("Lens ${lens?.key}: no frames with this session layout twice; trying layout $sessionVariant")
        }
        if (cameraFailures <= MAX_CAMERA_RETRIES) {
            val delay = minOf(4000L, 600L shl (cameraFailures - 1))
            EventLog.log("Lens ${lens?.key}: retry $cameraFailures/$MAX_CAMERA_RETRIES in $delay ms ($reason)")
            showMessage("Camera problem: $reason. Retrying ($cameraFailures/$MAX_CAMERA_RETRIES)…")
            retryPending = true
            retryWaitStartMs = SystemClock.elapsedRealtime() + delay
            info.postDelayed(retryCamera, delay)
            return
        }
        val main = mainLensIndex()
        if (main != null && main != lensIndex) {
            EventLog.log("Lens ${lens?.key} keeps failing; switching to ${lenses[main].key}")
            showMessage("${lens?.label} failed ($reason); switched to ${lenses[main].label}")
            switchLens(main)
            return
        }
        EventLog.log("Giving up on lens ${lens?.key} after $cameraFailures failures")
        showMessage("Camera failed: $reason. Lens button → Rescan lenses, or Send diagnostics")
    }

    /**
     * A passing status line (clip saved, lenses found): Glass lets it go after a few seconds, the
     * classic layout keeps it until the next one, as it always did.
     */
    private fun note(msg: String) {
        lastResult = msg
        // The stats line redraws every second while frames flow, which drops it from view.
        if (!classicUi) info.postDelayed({ if (lastResult == msg) lastResult = null }, 8000)
    }

    /** Shows [msg] now (the stats line, which normally carries it, only updates while frames flow). */
    private fun showMessage(msg: String) {
        lastResult = msg
        info.text = msg
        updateUi()
    }

    private fun setupPipeline() {
        if (camera != null) return
        lateinit var cam: RawCamera
        cam = try {
            RawCamera(this, cameraHandler, lenses.getOrNull(lensIndex), sessionVariant) { reason ->
                runOnUiThread { onCameraFailure(cam, reason) }
            }
        } catch (e: Exception) {
            EventLog.log("Camera unavailable for lens ${lenses.getOrNull(lensIndex)?.key}", e)
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
        camera = cam
        renderer = r
        // After the swap: these write to the current renderer (exposure was lost on lens switches
        // when it ran before it, e.g. Manual's digital EV).
        applyTone()
        applyLookAdjust()
        applyWbShift()
        applyExposure()
        r.glassRects = glassRects
        applyAspect()
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
        r.mirror = mirrored()
        r.attachSurface(holder.surface, w, h, displayQuarterTurns)
    }

    /**
     * Treats a camera that stops delivering frames (a lens can stall in some conditions), or never
     * starts, like any other camera failure; and clears the failure count once a lens streams well.
     */
    private val watchdog: Runnable = object : Runnable {
        override fun run() {
            info.removeCallbacks(this)
            if (!resumed) return
            val r = renderer
            val cam = camera
            if (r != null && cam != null && !scanning && !retryPending && !cam.referenceCapturing && cam.openRequestedMs > 0) {
                val now = SystemClock.elapsedRealtime()
                val last = r.lastFrameMs
                when {
                    // Frames of this open have arrived and then stopped.
                    last >= cam.openRequestedMs && now - last > 2500 -> onCameraFailure(cam, "no frames for 2.5 s")
                    // None yet since the (re)open. Logical cameras can take seconds to switch to a zoom
                    // route's lens (15 Ultra periscope: 4.2 s).
                    last < cam.openRequestedMs && now - cam.openRequestedMs > (if (cam.zoomRouted) 10_000 else 6000) ->
                        onCameraFailure(cam, "no frames after opening")
                    cameraFailures > 0 && last >= cam.openRequestedMs && now - cam.openRequestedMs > 5000 && now - last < 500 -> {
                        EventLog.log("Lens ${lenses.getOrNull(lensIndex)?.key} streaming again (layout $sessionVariant)")
                        cameraFailures = 0
                        if (lastResult?.startsWith("Camera problem") == true) lastResult = null
                    }
                }
            }
            info.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(batteryReceiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        getSystemService(PowerManager::class.java).addThermalStatusListener(mainExecutor, thermalListener)
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
        runCatching { unregisterReceiver(batteryReceiver) }
        getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalListener)
        stopRecording() // also cancels a warm-up
        info.removeCallbacks(retryCamera)
        retryPending = false
        camera?.close()
        savePrefs()
        super.onPause()
    }

    override fun onDestroy() {
        getSystemService(android.hardware.camera2.CameraManager::class.java).unregisterAvailabilityCallback(availabilityCallback)
        renderer?.release()
        camera?.release()
        cameraThread.quitSafely()
        super.onDestroy()
    }

    internal fun setSuperpixel(on: Boolean) {
        superpixel = on
        renderer?.superpixel = on
        applyAspect()
        settingsChanged()
    }

    internal fun toggleAudio() {
        if (!audioOn && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMISSIONS)
            return
        }
        audioOn = !audioOn
        settingsChanged()
    }

    // ---- Heat ----

    /**
     * HyperOS's PowerKeeper force-stops even the app in front once the battery passes ~48 °C (seen
     * on the Xiaomi 14: "mAllowedKillBatteryTempThreshhold is 48"; Android's own thermal status
     * still said "none" at 50 °C), and a recording cut off that way is lost. So the battery
     * temperature is watched: a warning as it climbs, and recording stops cleanly at [HOT_STOP_C].
     */
    private var batteryTempC = Float.NaN
    private var thermalStatus = PowerManager.THERMAL_STATUS_NONE
    private var heatLevel = 0

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            val t = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (t != Int.MIN_VALUE) onHeat(t / 10f, thermalStatus)
        }
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status -> onHeat(batteryTempC, status) }

    private fun onHeat(tempC: Float, status: Int) {
        if (status != thermalStatus) EventLog.log("Thermal status $status (battery %.1f °C)".format(tempC))
        batteryTempC = tempC
        thermalStatus = status
        val level = when {
            tempC >= HOT_STOP_C -> 3
            tempC >= HOT_WARN_C -> 2
            tempC >= WARM_C -> 1
            else -> 0
        }
        if (level != heatLevel) {
            EventLog.log("Battery %.1f °C (heat level $heatLevel → $level)".format(tempC))
            heatLevel = level
        }
        if (recorder != null && (tempC >= HOT_STOP_C || status >= PowerManager.THERMAL_STATUS_SEVERE)) {
            EventLog.log("Stopping recording: battery %.1f °C, thermal status $status".format(tempC))
            stopRecording("Stopped: phone too hot (%.1f °C; the system closes apps at about 48 °C)".format(tempC))
        }
    }

    /** One line about heat for the info text, or null while the phone is cool. */
    private fun heatNote(): String? = when {
        batteryTempC >= HOT_STOP_C -> "🌡 %.1f °C: too hot to record, let the phone cool".format(batteryTempC)
        batteryTempC >= HOT_WARN_C -> "🌡 %.1f °C: recording stops at %.0f °C".format(batteryTempC, HOT_STOP_C)
        batteryTempC >= WARM_C -> "🌡 %.1f °C".format(batteryTempC)
        thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> "🌡 phone is throttling (thermal status $thermalStatus)"
        else -> null
    }

    // ---- Recording ----

    internal fun toggleRecording() = if (recorder == null) startRecording() else stopRecording()

    /**
     * Pre-roll: between pressing REC and the clip's first frame. The encoder already runs (its
     * frames are thrown away) while the GPU clocks up at full resolution and the encoder allocates
     * its buffers; see Recorder. Jumping straight in lost frames in some clips' first seconds.
     */
    internal var warmingUp = false
        private set
    private val commitRecording = Runnable {
        if (!warmingUp || recorder == null) return@Runnable
        warmingUp = false
        renderer?.commitRecording()
        recordStartMs = SystemClock.elapsedRealtime()
        updateUi()
    }

    private fun startRecording() {
        val cam = camera ?: return
        val r = renderer ?: return
        if (stopping) return
        if (batteryTempC >= HOT_STOP_C) {
            showMessage("Phone too hot to record (%.1f °C); the system would close the app mid-recording".format(batteryTempC))
            return
        }
        val (w, h, keep) = frameSize(cam)
        val config = RecordConfig(codec, bitrateMbps, capture.fps, audioOn, cam.info.timestampsAreBoottime)
        val rec = try {
            Recorder(this, config, w, h)
        } catch (e: Exception) {
            EventLog.log("Recorder setup failed", e)
            lastResult = "Can't record: ${e.message}"
            updateUi()
            return
        }
        recorder = rec
        warmingUp = true
        recordStartMs = SystemClock.elapsedRealtime()
        lastResult = null
        EventLog.log("Recording ${w}x$h (${aspect.label}) ${codec.name} $bitrateMbps Mbps ${capture.fps} fps on ${lenses.getOrNull(lensIndex)?.key}, " +
            "${profile.name}, ${if (bakeLut || simple) "look baked" else "log"}, clean $cleanup, " +
            "eDR ${if (edrOn) "$edrHighlights/$edrShadows" else "off"}, WB shift $wbWarmth/$wbTint, " +
            (if (mirrored()) "mirrored, " else "") +
            "battery %.1f °C".format(batteryTempC))
        // In simple mode the recording is what you see, so bake the look in.
        r.startRecording(rec, capture.fps, bakeLut || simple, w, h, keep) { err ->
            runOnUiThread {
                lastResult = "Encoder surface failed: $err"
                stopRecording()
            }
        }
        // The preview runs at half resolution between recordings to save power; the pre-roll runs
        // at the recorded size so the GPU has clocked up before the clip starts.
        info.postDelayed(commitRecording, if (superpixel) PRE_ROLL_2K_MS else PRE_ROLL_MS)
        updateUi()
    }

    /** [why]: shown with the result when something other than the user stopped the recording. */
    private fun stopRecording(why: String? = null) {
        info.removeCallbacks(commitRecording)
        // Stopped before the clip began: the encoder only ever had pre-roll, nothing to keep.
        val cancelled = warmingUp
        warmingUp = false
        val rec = recorder ?: return
        val r = renderer ?: return
        recorder = null
        stopping = true
        updateUi()
        r.stopRecording { timing ->
            if (!cancelled) timing?.let { EventLog.log(it) }
            rec.stop { result ->
                if (cancelled) {
                    EventLog.log("Recording cancelled during the pre-roll" + (why?.let { ": $it" } ?: ""))
                    runOnUiThread {
                        stopping = false
                        why?.let { lastResult = it }
                        updateUi()
                    }
                    return@stop
                }
                val secs = (SystemClock.elapsedRealtime() - recordStartMs) / 1000.0
                val msg = (why?.let { "$it. " } ?: "") + "Saved ${result.frames} frames, ${result.bytes / 1_000_000} MB, " +
                    "≈%.0f Mbps".format(result.bytes * 8 / secs / 1e6) +
                    (if (result.audio) " + audio" else "") +
                    (result.error?.let { " — $it" } ?: "")
                EventLog.log("Recording: $msg → ${result.uri}")
                runOnUiThread {
                    stopping = false
                    if (why != null || result.error != null) lastResult = msg else note(msg)
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

    internal fun pickLut() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // .cube has no registered MIME type
        }, REQ_PICK_LUT)
    }

    /** Grabs the live log frame and opens the editor on the current look. */
    internal fun openLookEditor() {
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
        note("Imported ${target.nameWithoutExtension}")
        settingsChanged()
    }

    internal fun cycleProfile() = setProfile(LogProfile.entries[(profile.ordinal + 1) % LogProfile.entries.size])

    internal fun setProfile(p: LogProfile) {
        profile = p
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
        r.lutInput = (views.getOrNull(viewIndex) as? ViewEntry.CubeFile)?.let { lutInputOf(it.file) }
        r.setLut(lut)
    }

    private fun showStats(stats: Renderer.Stats) {
        val cam = camera ?: return
        val m = stats.meta
        val shutter = if (m != null && m.exposureNs > 0) "1/${(1e9 / m.exposureNs).toInt()}" else "-"
        val (fw, fh) = frameSize(cam)
        val out = "$fw×$fh" + (if (aspect != FrameAspect.OPEN_GATE) " ${aspect.label}" else "") + if (superpixel) " superpixel" else ""
        // Sensor ISO times the digital gain before the log curve: what the noise looks like.
        val gainEv = renderer?.totalGainEv ?: 0f
        val effectiveIso = m?.iso?.let { (it * Math.pow(2.0, gainEv.toDouble())).roundToInt() }
        exposureSlider.warn = (effectiveIso ?: 0) >= NOISY_ISO
        droppedFrames = stats.droppedWhileRecording
        lastEffectiveIso = effectiveIso
        maybeSuggestEdr()
        ui.onStats()
        info.text = buildString {
            heatNote()?.let { append(it) }
            if (!simple) {
                if (isNotEmpty()) append("\n")
                append("$out · ${profile.label}\n")
                append("ISO ${m?.iso ?: "-"} (≈${effectiveIso ?: "-"} with gain) · $shutter · gain %+.1f EV · %.1f fps · frame %.1f ms".format(
                    gainEv, stats.fps, stats.gpuMs))
                renderer?.lutInput?.takeIf { it != profile && views.getOrNull(viewIndex) is ViewEntry.CubeFile }
                    ?.let { append("\nLUT expects ${it.label}: converting from ${profile.label}") }
                if (capture.ae == AeMode.PRIORITY && priorityAtLimit) append("\n⚠ priority limits reached: image darker than target")
                renderer?.recoverableClipFraction?.takeIf { it > 0.01f && !edrOn && edrAvailable() }
                    ?.let { append("\n%.0f%% of the view blown out; eDR can bring it back".format(it * 100)) }
                if (m != null && m.shading == null) append("\nno lens shading map")
            }
            recorder?.takeIf { !warmingUp && (classicUi || !simple) }?.let {
                val secs = (SystemClock.elapsedRealtime() - recordStartMs) / 1000
                if (isNotEmpty()) append("\n")
                append("● REC %d:%02d · %s".format(secs / 60, secs % 60, if (bakeLut || simple) "look baked in" else "clean log"))
                if (!simple) append(" · %d frames · %d MB · dropped %d".format(it.framesWritten, it.bytesWritten / 1_000_000, stats.droppedWhileRecording))
            }
            lastResult?.let { if (isNotEmpty()) append("\n"); append(it) }
        }
    }

    /** Sensor ISO × the digital gain before the log curve, as of the last stats update. */
    internal var lastEffectiveIso: Int? = null
        private set

    /** Seconds in a row the view has shown blown highlights that the RAW still holds. */
    private var clipSeconds = 0

    /**
     * Simple mode, once ever: when bright parts stay blown out in the view although the RAW holds
     * them (what eDR fixes), suggest eDR and say what it does.
     */
    private fun maybeSuggestEdr() {
        val r = renderer ?: return
        if (!simple || edrOn || recorder != null || !edrAvailable() || prefs.getBoolean("edrHintShown", false)) {
            clipSeconds = 0
            return
        }
        clipSeconds = if (r.recoverableClipFraction > EDR_HINT_CLIP) clipSeconds + 1 else 0
        if (clipSeconds < 3) return
        prefs.edit().putBoolean("edrHintShown", true).apply()
        EventLog.log("Suggested eDR (%.0f%% of the view blown out, recoverable)".format(r.recoverableClipFraction * 100))
        showEdrHint()
    }

    private fun showEdrHint() = ui.showHint("Bright parts are blowing out. eDR brings them back and lifts the shadows a little.",
        "Turn on eDR", onAction = { setEdr(true) })

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
     * afverbose = true|false (log AF state every frame), layout = 0..2 (session layout, see
     * RawCamera.variant), cmd = failcam (simulate a camera failure) | rescan, lock = true (with tap:
     * lock focus there), afreset = true (as a double-tap), fullpreview = true|false (full-res preview),
     * fakeheat = °C (pretend battery temperature, until the next real reading) after fakeheatdelay ms,
     * lutinput = APPLE_LOG | SLOG3 | LOGC3 | NONE (what the selected imported LUT expects),
     * tonehi / tonelo = -100..100 (the eDR curve; switches eDR on), edr = true|false,
     * wbwarm / wbtint = -100..100 (white balance trim), mirror = true (front camera as a mirror image),
     * ui = glass | classic (rebuilds the screen),
     * edrhint = reset (the one-time eDR suggestion may come again) | show (show it now),
     * aspect = 4:3 | 16:9 | 2:1 | 2.39:1 (recorded frame), glasstiming = true (log the backdrop's GPU time),
     * pipetiming = true (log each pipeline stage's time, with glFinish between them),
     * bakedview = false (view math per pixel instead of the baked view LUT, to compare),
     * recdebug = split | dropkey | bars | off (encoder output handling: deliver frames in pieces /
     * lose the first key frame; bars: colour bars in the recording; see Recorder).
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
            switchLens(lenses.lastIndex, remember = false)
        }
        extras.getString("lens")?.let { key ->
            lenses.indexOfFirst { it.key == key }.takeIf { it >= 0 && it != lensIndex && recorder == null }?.let { switchLens(it) }
        }
        // Debug: force a session layout (see RawCamera.variant) on the current lens.
        if (extras.containsKey("layout") && recorder == null) {
            sessionVariant = extras.getInt("layout").coerceIn(0, 2)
            restartPipeline()
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
        // Debug: the current imported LUT's expected input (APPLE_LOG | SLOG3 | LOGC3 | NONE = as is).
        extras.getString("lutinput")?.let { v ->
            (views.getOrNull(viewIndex) as? ViewEntry.CubeFile)?.let { prefs.edit().putString("lutInput:${it.file.name}", v).apply() }
            applyView()
        }
        if (extras.containsKey("tonehi") || extras.containsKey("tonelo")) {
            setEdrCurve(extras.getInt("tonehi", edrHighlights), extras.getInt("tonelo", edrShadows))
        }
        if (extras.containsKey("edr")) setEdr(extras.getBoolean("edr"))
        when (extras.getString("edrhint")) {
            "reset" -> prefs.edit().remove("edrHintShown").apply()
            "show" -> showEdrHint()
        }
        if (extras.containsKey("mirror")) setMirrorFront(extras.getBoolean("mirror"))
        if (extras.containsKey("wbwarm") || extras.containsKey("wbtint")) {
            setWbShift(extras.getInt("wbwarm", wbWarmth), extras.getInt("wbtint", wbTint))
        }
        if (extras.containsKey("strength") || extras.containsKey("sat") || extras.containsKey("vib")) {
            setLookAdjust(extras.getInt("strength", lutStrength), extras.getInt("sat", saturationPct), extras.getInt("vib", vibrancePct))
        }
        if (extras.containsKey("afverbose")) camera?.afVerbose = extras.getBoolean("afverbose")
        extras.getString("tap")?.split(",")?.map { it.toFloat() }?.let { (u, v) -> focusAt(u, v, lock = extras.getBoolean("lock", false)) }
        if (extras.getBoolean("afreset", false)) resetFocusToAuto()
        if (extras.containsKey("fullpreview")) renderer?.previewFullRes = extras.getBoolean("fullpreview")
        if (extras.containsKey("glasstiming")) renderer?.glassTiming = extras.getBoolean("glasstiming")
        if (extras.containsKey("pipetiming")) renderer?.pipeTiming = extras.getBoolean("pipetiming")
        if (extras.containsKey("bakedview")) renderer?.bakeViewLut = extras.getBoolean("bakedview")
        extras.getString("recdebug")?.let {
            Recorder.debugSplitFrames = it == "split"
            Recorder.debugDropFirstKey = it == "dropkey"
            Recorder.debugBars = it == "bars"
        }
        // Debug: pretend the battery is this hot (until the next real reading), e.g. fakeheat=47.5.
        if (extras.containsKey("fakeheat")) {
            info.postDelayed({ onHeat(extras.getFloat("fakeheat"), thermalStatus) }, extras.getInt("fakeheatdelay", 500).toLong())
        }
        savePrefs()
        updateUi()
        extras.getString("aspect")?.let { v -> FrameAspect.entries.firstOrNull { it.label == v || it.name == v }?.let { setAspect(it) } }
        extras.getString("ui")?.let { setClassicUi(it == "classic") }
        when (extras.getString("cmd")) {
            // Give the camera a moment to start when launched and told to record in one go.
            "rec" -> info.postDelayed({ if (recorder == null) startRecording() }, 1500)
            "stop" -> stopRecording()
            "edit" -> info.postDelayed({ openLookEditor() }, 1500)
            "dumpcams" -> Thread { dumpCameras() }.start()
            // Exercises the failure handling: the camera closes as if the HAL had reported an error
            // (after the resume that follows this intent), and the next `count` opens fail too.
            "failcam" -> info.postDelayed({
                RawCamera.debugFailOpens = extras.getInt("count", 0)
                camera?.simulateFailure()
            }, 2000)
            "rescan" -> rescanLenses()
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
        /** Retries of a failing lens before falling back to the main lens. */
        private const val MAX_CAMERA_RETRIES = 6
        /** Battery temperatures (°C): show it, warn, stop recording (PowerKeeper kills at ~48). */
        private const val WARM_C = 43f
        private const val HOT_WARN_C = 45f
        private const val HOT_STOP_C = 47f
        /** eDR's default curve: highlights well down, shadows up a little. */
        const val EDR_HIGHLIGHTS = -75
        const val EDR_SHADOWS = 30
        /** Share of the view blown out (but held by the RAW) that makes Simple suggest eDR. */
        private const val EDR_HINT_CLIP = 0.04f
        /** ± range of the EV slider. */
        const val EV_RANGE = 8f
        /** Half-size of a tapped contrast-AF region (0..1 of the image; the measure weights its centre). */
        private const val TAP_AF_HALF = 0.06f
        /** Effective ISO from which the exposure slider's value turns amber. */
        private const val NOISY_ISO = 3200
        /** Pre-roll before a clip starts (see [warmingUp]): 4K, and 2K superpixel. */
        private const val PRE_ROLL_MS = 1000L
        private const val PRE_ROLL_2K_MS = 500L
        private const val LUT_INPUT_MENU = 9_000
        private const val LUT_INPUT_BASE = 9_001
        private const val RESCAN_ITEM = 10_000
        private const val DIAGNOSTICS_ITEM = 10_001
        private const val BENCH_ITEM = 10_002
        private const val STYLE_ITEM = 10_003
        private const val IMPORT_ITEM = 10_004
    }
}
