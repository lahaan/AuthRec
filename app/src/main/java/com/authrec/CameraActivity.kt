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
import android.os.Build
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
import com.authrec.gl.Evo
import com.authrec.color.Looks
import com.authrec.gl.Renderer
import com.authrec.record.PhotoSaver
import com.authrec.record.RecordConfig
import com.authrec.record.Recorder
import com.authrec.record.RecordingCheck
import com.authrec.record.VideoCodec
import com.authrec.ui.CameraUi
import com.authrec.ui.ClassicUi
import com.authrec.ui.Glass
import com.authrec.ui.GlassDialog
import com.authrec.ui.GlassUi
import com.authrec.ui.Stepper
import com.authrec.ui.UiKit
import com.authrec.ui.dp
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Live open-gate RAW → log preview with real-time looks/LUTs, and recording to HEVC/H.264 with
 * audio. Three modes: Simple (look, tap to focus, record), Pro (everything) and Photo (Pro's
 * controls without the video-only ones, the record button takes a still: [takePhoto]); two
 * layouts ([GlassUi], [ClassicUi]) over the same state and actions, which live here.
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
    /** Named settings at the top of the Look menu (see [Profile]). */
    internal var profiles: List<Profile> = emptyList()
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
    /**
     * Photo mode: Pro's controls ([simple] is false) without the video-only ones (frame rate,
     * codec, bitrate, recording format, audio, resolution, clean-up: photos get the full
     * clean-up), Manual's shutter up to ½ s, and the record button takes a still ([takePhoto]).
     * Owner: "why are the manual controls all gone in photo".
     */
    internal var photo = false
        private set
    /** Photo mode also saves the burst's first RAW frame as a DNG (Settings → Photo). */
    internal var photoRaw = false
        private set
    /** A photo's burst is being taken: the shutter, lenses and modes wait for it (saving doesn't hold them). */
    internal var photoBusy = false
        private set
    /** Photos taken and not yet saved, saved one at a time ([photoSaver]). */
    private val photosSaving = java.util.concurrent.atomic.AtomicInteger()
    private val photoSaver = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** The shutter was pressed during a burst: the next one starts as soon as it's done. */
    private var photoQueued = false
    /** A still from the recording is on its way ([takeSnap]). */
    internal var snapBusy = false
        private set
    /** Texture, Clarity and Dehaze, −100..100 (Adjust, Pro and Photo; see Renderer.photoTexture). */
    internal var photoTexture = 0
        private set
    internal var photoClarity = 0
        private set
    internal var photoDehaze = 0
        private set
    /** See [Renderer.cleanup]. */
    internal var cleanup = 2
        private set
    /** Colour NR's Fast variant instead of Quality (Settings → Colour NR; see Renderer.nrFast). */
    internal var nrFast = false
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
    /**
     * EVO, the Exposure Value Optimizer (com.authrec.gl.Evo; owner's idea, 2026-11-12): moves the
     * exposure so bright surfaces aren't blown out, on top of the meter and under the user's own
     * EV. In AE Auto it shifts the view and, where the sensor itself clips, real exposure; in
     * Priority the view only (the limits are the user's); Locked holds it; Manual has none.
     */
    internal var evoOn = false
        private set
    /** How readily EVO darkens: 0 gentle, 1 balanced, 2 strong (Settings; owner: "change EVO aggression/strength"). */
    internal var evoStrength = 1
        private set
    /** Real exposure EVO asks for right now (EV, whole AE steps; the renderer's, see applyExposure). */
    private var evoSensorEv = 0f
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
    /** Failures of the current layout that got as far as asking for a session, with no frame. */
    private var layoutFailures = 0
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
    /** The clip is paused (see [togglePause]). */
    internal val paused get() = recorder?.pauses?.paused == true
    /** Time spent paused in the current clip, closed pauses only, and when the open one began. */
    private var pausedMs = 0L
    private var pauseStartMs = 0L

    /** How long the current clip has recorded so far, pauses left out. */
    internal fun recordedMs(): Long {
        val now = SystemClock.elapsedRealtime()
        return now - recordStartMs - pausedMs - if (paused) now - pauseStartMs else 0L
    }

    /**
     * Pause / resume the clip being recorded: nothing captured while paused goes into the file, and
     * it plays on without a gap. The encoder keeps running (no new warm-up, no key-frame wait), so
     * resuming is instant; the camera and GPU keep running too (the preview stays live), so the
     * phone keeps warming as while recording. Not during the pre-roll.
     */
    internal fun togglePause() {
        val rec = recorder ?: return
        if (warmingUp || stopping) return
        tap()
        val now = SystemClock.elapsedRealtime()
        if (paused) {
            rec.resume()
            pausedMs += now - pauseStartMs
            EventLog.log("Recording resumed at %.1f s (paused %.1f s)".format(recordedMs() / 1000.0, (now - pauseStartMs) / 1000.0))
        } else {
            rec.pause()
            pauseStartMs = now
            EventLog.log("Recording paused at %.1f s".format(recordedMs() / 1000.0))
        }
        updateUi()
    }
    /** Frames lost while recording (from the renderer's stats). */
    internal var droppedFrames = 0
        private set

    /**
     * A short, strong tap when recording starts, stops or pauses (owner: "not a long lasting one
     * but a strong short one"; 30 ms felt like a cheap phone's buzz): ~9 ms rising to full and
     * falling off, in 1–4 ms steps of amplitude (the X14's motor has amplitude control but no
     * primitives or envelopes for apps). [shutter]: a photo, two quick taps 22 ms apart, the
     * second softer (a shutter's two stages, "extremely quick"). The click primitive where a
     * motor has it, else a predefined click. As touch feedback: it follows the haptics setting.
     */
    private fun tap(shutter: Boolean = false) {
        val vibrator = getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator ?: return
        if (!vibrator.hasVibrator()) return
        val primitive = android.os.VibrationEffect.Composition.PRIMITIVE_CLICK
        val effect = when {
            vibrator.areAllPrimitivesSupported(primitive) -> android.os.VibrationEffect.startComposition().apply {
                addPrimitive(primitive, 1f)
                if (shutter) addPrimitive(primitive, 0.6f, SHUTTER_GAP_MS.toInt())
            }.compose()
            vibrator.hasAmplitudeControl() -> if (shutter) {
                android.os.VibrationEffect.createWaveform(longArrayOf(4, SHUTTER_GAP_MS, 3), intArrayOf(255, 0, 180), -1)
            } else {
                android.os.VibrationEffect.createWaveform(longArrayOf(2, 4, 3), intArrayOf(160, 255, 90), -1)
            }
            else -> android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_TOUCH))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect)
            }
        }
    }

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
        installBundledPresets()
        recordingLimits = RecordingCheck.load(this)?.also { r -> r.box?.let { encoderBox = it } }
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
        if (savedInstanceState == null && !staleLaunch(intent)) handleCommands(intent)
        // Opened with a preset file from another app (not again when Android restores the task).
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            incomingFile(intent)?.let { uri -> info.post { importFile(uri) } }
        }
    }

    /**
     * An intent this activity was already created with: Android restarts a task's first activity
     * with the intent that made the task (from recents, or the launcher bringing the task back
     * after the process died), so an adb test's settings came back over the owner's: Record went
     * back to Log, Photo mode came on (2026-11-01). Remembered per launch; a fresh adb intent
     * reaches a running task through [onNewIntent] instead.
     */
    private fun staleLaunch(intent: Intent?): Boolean {
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return true
        if (intent.hasCategory(Intent.CATEGORY_LAUNCHER) || intent.extras == null) return false
        val key = intent.toUri(0)
        if (prefs.getString("lastLaunch", null) == key) return true
        prefs.edit().putString("lastLaunch", key).apply()
        return false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCommands(intent)
        incomingFile(intent)?.let { importFile(it) }
    }

    /** The file another app opened AuthRec with or shared to it (a preset), if any. */
    private fun incomingFile(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
    }

    // ---- Preferences ----

    private fun loadPrefs() {
        profiles = Profile.load(prefs)
        simple = prefs.getBoolean("simple", true)
        photo = !simple && prefs.getBoolean("photo", false)
        photoRaw = prefs.getBoolean("photoRaw", false)
        photoTexture = prefs.getInt("photoTexture", 0)
        photoClarity = prefs.getInt("photoClarity", 0)
        photoDehaze = prefs.getInt("photoDehaze", 0)
        classicUi = prefs.getString("uiStyle", "glass") == "classic"
        aspect = runCatching { FrameAspect.valueOf(prefs.getString("aspect", null)!!) }.getOrDefault(FrameAspect.OPEN_GATE)
        profile = runCatching { LogProfile.valueOf(prefs.getString("profile", null)!!) }.getOrDefault(LogProfile.APPLE_LOG)
        codec = runCatching { VideoCodec.valueOf(prefs.getString("codec", null)!!) }.getOrDefault(VideoCodec.HEVC_10)
        bitrateMbps = prefs.getInt("bitrate", 150)
        bakeLut = prefs.getBoolean("recordLook", true)
        cleanup = prefs.getInt("cleanup", 2)
        nrFast = prefs.getBoolean("nrFast", false)
        evoOn = prefs.getBoolean("evo", false)
        evoStrength = prefs.getInt("evoStrength", 1).coerceIn(0, 3)
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
        capture = capture.copy(fps = prefs.getInt("fps", 30), longExposure = photo)
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
            .putBoolean("photo", photo)
            .putBoolean("photoRaw", photoRaw)
            .putInt("photoTexture", photoTexture)
            .putInt("photoClarity", photoClarity)
            .putInt("photoDehaze", photoDehaze)
            .putString("uiStyle", if (classicUi) "classic" else "glass")
            .putString("aspect", aspect.name)
            .putString("profile", profile.name)
            .putString("codec", codec.name)
            .putInt("bitrate", bitrateMbps)
            .putBoolean("recordLook", bakeLut)
            .putInt("cleanup", cleanup)
            .putBoolean("nrFast", nrFast)
            .putBoolean("edrOn", edrOn)
            .putBoolean("evo", evoOn)
            .putInt("evoStrength", evoStrength)
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

        info = com.authrec.ui.RimText(this).apply {
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
        applyPose()
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

    /**
     * How the phone is held, in OrientationEventListener's degrees snapped to quarter turns
     * (0 = upright portrait, 270 = the landscape the screen is locked to); -1 = unknown (flat).
     * The screen stays landscape, so a clip filmed with the phone upright came out sideways in a
     * 4:3 landscape frame (owner, 2026-10-15); it now carries a rotation for players instead.
     */
    @Volatile private var heldDegrees = -1

    private val orientationListener by lazy {
        object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return // flat: keep the last pose
                val snapped = ((degrees + 45) / 90 % 4) * 90
                // Near a diagonal, keep the current pose (no flicking between two).
                val off = abs(((degrees - heldDegrees + 540) % 360) - 180)
                if ((heldDegrees < 0 || off > 60) && snapped != heldDegrees) {
                    heldDegrees = snapped
                    info.removeCallbacks(poseCheck)
                    info.postDelayed(poseCheck, 250)
                }
            }
        }
    }

    /**
     * Clockwise turn players should give a recording started now (MediaMuxer.setOrientationHint):
     * the frames are the locked landscape screen's image, upright when the phone is held that way.
     * A mirrored front recording turns the other way round.
     */
    private fun recordingRotation(): Int {
        val turn = poseTurn()
        val front = camera?.info?.front == true
        return if (front && !mirrored()) (360 - turn) % 360 else turn
    }

    /**
     * The controls follow the phone's pose (Glass): once it has settled, and never while a clip
     * records or a burst is taken, so a clip's controls stay the way they started (owner: "in video
     * if recording cant go vertical mid record"). They catch up when it's over.
     */
    private val poseCheck = Runnable { applyPose() }

    private fun applyPose() {
        if (!::ui.isInitialized || recorder != null || warmingUp || stopping || photoBusy) return
        ui.setTurn(poseTurn())
    }

    /** How far the phone is turned clockwise from the screen's landscape (0 when unknown). */
    private fun poseTurn(): Int {
        val held = heldDegrees.takeIf { it >= 0 } ?: return 0
        val reference = (360 - display.rotation * 90) % 360 // the listener's degrees in the screen's landscape
        return (held - reference + 360) % 360
    }

    internal fun setEdr(on: Boolean) {
        edrOn = on
        applyTone()
        updateUi()
        saveSoon()
    }

    internal fun setEvo(on: Boolean) {
        evoOn = on
        applyEvo()
        updateUi()
        saveSoon()
    }

    internal fun setEvoStrength(level: Int) {
        evoStrength = level.coerceIn(0, 3)
        applyEvo()
        settingsChanged()
    }

    internal fun evoStrengthLabel() = arrayOf("gentle", "balanced", "strong", "extra")[evoStrength]

    /** Tells the renderer whether EVO runs and what counts as white (the view's, and a clip's). */
    private fun applyEvo() {
        val r = renderer ?: return
        r.evoStrength = evoStrength
        r.evoMode = when {
            !evoOn || capture.ae == AeMode.MANUAL -> Evo.Mode.OFF
            // Locked means locked; and a burst's frames must match each other.
            capture.ae == AeMode.LOCKED || photoBusy -> Evo.Mode.HOLD
            else -> Evo.Mode.RUN
        }
        r.evoQuick = photo
        val tone = viewTone()
        r.evoTone = tone
        val white = tone.white
        r.evoRecordedWhite = if (bakeLut || simple || photo) white else Evo.LOG_WHITE
    }

    /** The look as last given to the renderer (null: the plain log view), for [viewTone]. */
    private var viewLut: CubeLut? = null
    private var toneKey: List<Any?>? = null
    private var toneValue: Evo.Tone? = null

    /**
     * How the current view shows each scene brightness (Evo.toneOfLook), worked out again when the
     * look, its strength, the log profile, eDR or Dehaze change.
     */
    private fun viewTone(): Evo.Tone {
        val edr = edrOn && edrAvailable()
        val input = renderer?.lutInput
        val dehaze = if (simple) 0 else photoDehaze
        val key = listOf(viewLut, profile, input, lutStrength, if (edr) edrHighlights else 0, if (edr) edrShadows else 0, dehaze)
        toneValue?.takeIf { key == toneKey }?.let { return it }
        val tone = Evo.toneOfLook(viewLut, profile, input, lutStrength / 100f,
            if (edr) edrHighlights / 100f else 0f, if (edr) edrShadows / 100f else 0f, dehaze / 100f)
        toneKey = key
        toneValue = tone
        if (evoOn) EventLog.log("EVO: \"${currentView()?.label}\"${if (dehaze != 0) " with Dehaze %+d".format(dehaze) else ""} goes white " +
            "%.1f stops over middle grey and shows middle grey at %.0f %%".format(kotlin.math.log2(tone.white / 0.18f), tone.at(0f) * 100))
        return tone
    }

    /** What EVO is doing, for the controls: null when off or at rest, else its shift of the picture in EV. */
    /** EVO is switched on but has nothing to work with: exposure is Manual. */
    internal fun evoIdle() = evoOn && renderer?.evoMode == Evo.Mode.OFF

    internal fun evoShift(): Float? = renderer?.takeIf { evoOn }?.let { r ->
        val shown = r.evoViewEv
        val sensor = r.evoSensorEv
        (if (abs(shown) >= 0.05f) shown else sensor).takeIf { abs(it) >= 0.05f }
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
        // EVO's real exposure rides on AE compensation too (Auto; Locked keeps what it had).
        val evoSensor = if (capture.ae == AeMode.AUTO || capture.ae == AeMode.LOCKED) evoSensorEv else 0f
        r?.autoGainFrozen = capture.ae == AeMode.MANUAL || capture.ae == AeMode.PRIORITY || abs(exposureEv) > 0.05f || evoSensor != 0f
        r?.nightLimit = capture.ae != AeMode.MANUAL
        val evoAllowed = capture.ae == AeMode.AUTO || capture.ae == AeMode.LOCKED
        // The renderer takes these as one set per frame (Renderer.Exposure).
        when (capture.ae) {
            AeMode.AUTO, AeMode.LOCKED -> {
                val info = camera?.info
                val step = info?.evStep ?: (1f / 3)
                // Rounded apart: the user's 0.1 EV and EVO's whole steps mustn't swallow each other.
                // The user's EV past the range is digital gain: EVO's steps count from the end of
                // the range, not from there (they were swallowed, and the picture evened out against
                // a step the sensor never took).
                val lowest = info?.evRange?.lower ?: 0
                val highest = info?.evRange?.upper ?: 0
                val user = Math.round(exposureEv / step).coerceIn(lowest, highest)
                val steps = (user + Math.round(evoSensor / step)).coerceIn(lowest, highest)
                // The picture gets the user's EV; what the sensor got of it and of EVO's is evened
                // out digitally (the renderer adds EVO's view shift and the frames still on their way).
                r?.exposure = Renderer.Exposure(offsetEv = exposureEv - steps * step, sensorCompEv = steps * step, aeCompEv = steps * step,
                    evoGrantedEv = (steps - user) * step, evoUserEv = exposureEv, evoSensorAllowed = evoAllowed)
                if (steps != capture.evSteps) setCapture(capture.copy(evSteps = steps), updateUiNow = false)
            }
            // The loop's target carries the user's EV.
            AeMode.PRIORITY -> r?.exposure = Renderer.Exposure(sensorCompEv = exposureEv, evoUserEv = exposureEv, evoSensorAllowed = evoAllowed)
            AeMode.MANUAL -> r?.exposure = Renderer.Exposure(offsetEv = exposureEv, evoUserEv = exposureEv, evoSensorAllowed = evoAllowed)
        }
        applyEvo()
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
        syncResolution()
        applyEvo()
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

    internal fun setSimple(on: Boolean) = setMode(simple = on, photo = false)

    /** Photo mode (see [photo]); off goes back to Pro, whose controls it shares. */
    internal fun setPhoto(on: Boolean) = setMode(simple = false, photo = on)

    /** 0 Simple, 1 Pro, 2 Photo: the mode switches' choice. */
    internal val modeIndex get() = if (photo) 2 else if (simple) 0 else 1

    internal fun setModeIndex(i: Int) = when (i) {
        2 -> setPhoto(true)
        else -> setSimple(i == 0)
    }

    private fun setMode(simple: Boolean, photo: Boolean) {
        if (photoBusy || recorder != null || stopping) return
        this.simple = simple
        this.photo = photo
        var c = capture.copy(longExposure = photo)
        // Simple is fully automatic.
        if (simple) c = c.copy(ae = AeMode.AUTO, af = AfMode.CONTINUOUS, focusPoint = null, evSteps = 0, awbLock = false)
        // Back from Photo's long shutters: a video frame holds at most 1/fps.
        if (!photo) c = c.copy(exposureNs = c.exposureNs.coerceAtMost(1_000_000_000L / c.fps))
        setCapture(c)
        applyPhotoFinish()
        settingsChanged()
    }

    /**
     * Texture, Clarity and Dehaze (−100..100), part of the view like the look: the viewfinder shows
     * them, photos and recordings with the look baked in get them (owner: "could [they] be added to
     * video"); log recordings don't. Pro and Photo only: Simple stays plain.
     */
    internal fun setPhotoFinish(texture: Int = photoTexture, clarity: Int = photoClarity, dehaze: Int = photoDehaze) {
        photoTexture = texture.coerceIn(-100, 100)
        photoClarity = clarity.coerceIn(-100, 100)
        photoDehaze = dehaze.coerceIn(-100, 100)
        applyPhotoFinish()
        updateUi()
        saveSoon()
    }

    private fun applyPhotoFinish() {
        renderer?.photoMode = photo
        renderer?.dialsActive = !simple
        renderer?.photoTexture = photoTexture / 100f
        renderer?.photoClarity = photoClarity / 100f
        renderer?.photoDehaze = photoDehaze / 100f
    }

    /** The current lens has a flash unit the torch can use (the front cameras don't). */
    internal fun torchAvailable() = camera?.hasTorch == true

    /**
     * The torch (a steady light for video and photos), while the app has the camera; not kept
     * across launches. On/off only (owner): brightness is up to each phone's HAL, and the X14
     * refuses it while an app has the camera (one level in a session; the flashlight API's 100
     * fail on every id with "existing camera user" / "insufficient resources", tested 2026-11-01).
     */
    internal fun setTorch(on: Boolean) {
        if (on == capture.torch) return
        EventLog.log("Torch ${if (on) "on" else "off"} (lens ${lenses.getOrNull(lensIndex)?.key})")
        setCapture(capture.copy(torch = on))
    }

    internal fun setPhotoRaw(on: Boolean) {
        photoRaw = on
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
    internal fun cycleCodec() {
        val offered = VideoCodec.entries.filter { codecOffered(it) }
        setCodec(offered[(offered.indexOf(effectiveCodec()) + 1) % offered.size])
    }

    /** False once the GPU turned out unable to hand the encoder 10-bit frames ([Renderer.tenBitRecording]). */
    internal fun codecOffered(c: VideoCodec) = !c.tenBit || renderer?.tenBitRecording != false

    /** What a recording uses: the 8-bit HEVC where 10-bit isn't possible (the setting stays). */
    internal fun effectiveCodec() = if (codecOffered(codec)) codec else VideoCodec.HEVC_8
    internal fun setCodec(c: VideoCodec) {
        codec = c
        settingsChanged()
        // The renderer's GL config is made for the recording's bit depth (EglCore): rebuild it for
        // the other one, or recordings would need a second GL context (Adreno crashed with that).
        val wantTenBit = c.tenBit && recordingLimits?.tenBit != false
        if (wantTenBit != rendererTenBit && recorder == null && !stopping && renderer != null && !scanning) restartPipeline()
    }

    /** The bit depth the current renderer's GL config was made for. */
    private var rendererTenBit = false
    /** Bitrates offered for the current resolution: 2K needs far less (owner asked for 25 and 75). */
    internal fun bitrateOptions(): List<Int> {
        val all = if (effectiveSuperpixel()) BITRATES_2K else BITRATES_4K
        val cap = recordingLimits?.maxMbps ?: 0
        return if (cap <= 0) all else all.filter { it <= cap * 1.1 }.ifEmpty { listOf(all.first()) }
    }

    internal fun cycleBitrate() {
        val options = bitrateOptions()
        setBitrate(options[(options.indexOf(bitrateMbps) + 1) % options.size])
    }

    internal fun setBitrate(mbps: Int) { bitrateMbps = mbps; settingsChanged() }
    internal fun setBake(on: Boolean) { bakeLut = on; settingsChanged() }
    internal fun cycleCleanup() = setCleanup((cleanup + 1) % 4)
    internal fun setCleanup(level: Int) {
        cleanup = level.coerceIn(0, 3)
        renderer?.cleanup = cleanup
        settingsChanged()
    }
    internal fun setNrFast(on: Boolean) {
        nrFast = on
        renderer?.nrFast = on
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
        syncResolution()
        val cam = camera ?: return
        renderer?.frameCrop = frameSize(cam).third
    }

    /**
     * Recorded frame for the current resolution and [aspect]: width, height (multiples of 16, which
     * every encoder takes) and the share of the image's height it keeps.
     */
    internal fun frameSize(cam: RawCamera, twoK: Boolean = effectiveSuperpixel()): Triple<Int, Int, Float> {
        val w = if (twoK) cam.info.size.width / 2 else cam.info.size.width
        val h = if (twoK) cam.info.size.height / 2 else cam.info.size.height
        if (aspect.ratio <= w.toFloat() / h + 0.01f) return Triple(w, h, 1f)
        val cropH = (Math.round(w / aspect.ratio / 16f) * 16).coerceAtMost(h)
        return Triple(w, cropH, cropH.toFloat() / h)
    }
    /**
     * What a clip on [cam] records in 4K or 2K superpixel ([twoK]): the frame fitted to this phone's
     * encoder (its declared limits, then a box found when it put out nothing), as [startRecording] does.
     */
    private fun recordedSize(cam: RawCamera, twoK: Boolean): Pair<Int, Int> {
        val (fullW, fullH) = frameSize(cam, twoK)
        val (fitW, fitH) = Recorder.fitToEncoder(effectiveCodec().mime, fullW, fullH, capture.fps)
        if (encoderBox == null && prefs.getBoolean(encoderBoxKey(), false)) encoderBox = 4096 to 2160
        return encoderBox?.let { (bw, bh) -> Recorder.fitInside(fitW, fitH, bw, bh) } ?: (fitW to fitH)
    }

    private var fourKKey: List<Any?>? = null
    private var fourKValue = true

    /**
     * Whether 4K records more than 2K superpixel on this lens. Where the encoder takes no more than
     * the 2K frame, 4K would develop four times the pixels for the same clip: the POCO M6 Pro's takes
     * 2560 px at most, so 4K came out at 1920×1440 and its GPU managed 52–74 ms a frame for it
     * (diagnostics 2026-11-07). 2K superpixel records it there instead (see [effectiveSuperpixel]).
     */
    internal fun fourKOffered(): Boolean {
        val cam = camera ?: return true
        val key = listOf(cam.info.size.width, cam.info.size.height, aspect, capture.fps, effectiveCodec(), encoderBox)
        if (key != fourKKey) {
            val (w4, h4) = recordedSize(cam, twoK = false)
            val (w2, h2) = recordedSize(cam, twoK = true)
            fourKValue = w4.toLong() * h4 * 10 > w2.toLong() * h2 * 11
            fourKKey = key
        }
        return fourKValue
    }

    /** 2K superpixel as set, or where 4K wouldn't record more ([fourKOffered]); the setting stays. */
    internal fun effectiveSuperpixel() = superpixel || !fourKOffered()

    /**
     * The renderer and the bitrate follow [effectiveSuperpixel], which changes with the lens, frame
     * and frame rate; never mid-clip (the recorded size can't change).
     */
    private fun syncResolution() {
        if (recorder != null || stopping) return
        val twoK = effectiveSuperpixel()
        renderer?.let { if (it.superpixel != twoK) it.superpixel = twoK }
        // Keep the bitrate one this resolution offers (the nearest, e.g. 4K 150 → 2K 100).
        if (bitrateMbps !in bitrateOptions()) bitrateMbps = bitrateOptions().minBy { abs(it - bitrateMbps) }
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

    /** Whether exposure, white balance and focus are all automatic (nothing for [resetCapture] to do). */
    internal fun captureAtDefaults() = capture.ae == AeMode.AUTO && exposureEv == 0f && !capture.awbLock &&
        wbWarmth == 0 && wbTint == 0 && capture.af != AfMode.MANUAL && capture.focusPoint == null &&
        focusSquare.visibility != View.VISIBLE

    /**
     * Pro's Reset: exposure Auto at EV 0, white balance Auto without trim, focus continuous on the
     * whole scene. The look, eDR, frame rate and recording settings stay (owner, 2026-10-15: "a
     * reset button, mainly for things like EV, focus"; EV is remembered between launches).
     */
    internal fun resetCapture() {
        setAe(AeMode.AUTO)
        if (capture.awbLock) toggleAwbLock()
        setWbShift(0, 0)
        setExposureEv(0f)
        if (capture.af == AfMode.MANUAL) setCapture(capture.copy(af = AfMode.CONTINUOUS, focusPoint = null))
        resetFocusToAuto()
        updateUi()
    }

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
        if (modeChanged && settings.ae != AeMode.PRIORITY) {
            priorityExposure = Double.NaN
            renderer?.exposureTarget = Double.NaN
        }
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
     *
     * 10 updates/s in small steps (at most 0.3 EV), judging each measurement against the exposure
     * that produced it rather than the latest request (which takes a few frames to reach the
     * sensor; judging against it overshot). The renderer eases the picture between updates and
     * evens out each frame's actual exposure with digital gain ([Renderer.exposureTarget]): the
     * old loop's half-way jumps 5× a second showed as choppy brightness steps (owner).
     */
    private val priorityTick: Runnable = object : Runnable {
        override fun run() {
            info.removeCallbacks(this)
            if (capture.ae != AeMode.PRIORITY) return
            val r = renderer
            val measured = r?.rawBrightness ?: Float.NaN
            val measuredAt = r?.rawExposure ?: Double.NaN
            if (r != null && !measured.isNaN() && measured > 0f && !measuredAt.isNaN()) {
                val goal = priorityTarget * Math.pow(2.0, exposureEv.toDouble())
                var wanted = measuredAt * goal / measured
                if (r.rawClipFraction > 0.02f) wanted = minOf(wanted, measuredAt * 0.8)
                val (lo, hi) = exposureRange()
                val reachable = wanted.coerceIn(lo, hi)
                priorityAtLimit = reachable < wanted * 0.8
                val from = priorityExposure.takeIf { !it.isNaN() } ?: measuredAt
                priorityExposure = from * Math.pow(reachable / from, 0.35).coerceIn(PRIORITY_STEP_DOWN, PRIORITY_STEP_UP)
                r.exposureTarget = priorityExposure
                val requested = capture.iso.toDouble() * capture.exposureNs
                if (abs(priorityExposure / requested - 1) > 0.03) {
                    val (iso, ns) = splitExposure(priorityExposure)
                    setCapture(capture.copy(iso = iso, exposureNs = ns), updateUiNow = false)
                }
            }
            info.postDelayed(this, 100)
        }
    }

    /** The exposure Priority aims the picture at (ISO × ns), eased 10× a second; NaN = not started. */
    private var priorityExposure = Double.NaN

    /** Exposure (ISO × ns) Priority can reach: down to the sensor's minimum (no ND), up to the limits. */
    private fun exposureRange(): Pair<Double, Double> {
        val info = camera?.info ?: return 0.0 to Double.MAX_VALUE
        val frameNs = 1_000_000_000.0 / capture.fps
        val fast = maxOf(limits.fastestNs.toDouble(), info.exposureRangeNs.lower.toDouble())
        val slow = minOf(limits.slowestNs.toDouble(), frameNs).coerceAtLeast(fast)
        val isoLo = maxOf(limits.isoMin, info.isoRange.lower).toDouble()
        val isoHi = minOf(limits.isoMax, info.isoRange.upper).toDouble().coerceAtLeast(isoLo)
        return info.isoRange.lower.toDouble() * info.exposureRangeNs.lower to isoHi * slow
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

    /**
     * Shutter times that fit in one frame at the current fps, longest first; includes 180° (1/2fps).
     * Photo mode goes on to ½ s (RawCamera.PHOTO_LONGEST_NS), the sensor permitting.
     */
    internal fun shutterStops(): List<Long> {
        val frameNs = 1_000_000_000L / capture.fps
        val minNs = camera?.info?.exposureRangeNs?.lower ?: 100_000L
        val longest = if (photo) maxOf(frameNs, minOf(camera?.info?.exposureRangeNs?.upper ?: frameNs, RawCamera.PHOTO_LONGEST_NS)) else frameNs
        val denoms = (listOf(2, 4, 8, 15, 24, 25, 30, 48, 50, 60, 100, 120, 125, 250, 500, 1000, 2000, 4000, 8000) + 2 * capture.fps).distinct()
        return denoms.map { 1_000_000_000L / it }.filter { it in minNs..longest }.sortedDescending()
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
                layoutFailures = 0
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
        // No camera is there while the camera service restarts: that isn't this route's fault.
        if (!cameraThere(backup.openId)) return false
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
        if (!scanning && recorder == null) menu.menu.add(1, RECHECK_ITEM, order.size + 4, "Re-check recording")
        menu.menu.add(1, STYLE_ITEM, order.size + 3, if (classicUi) "Switch to the Glass layout" else "Switch to the Classic layout")
        menu.setOnMenuItemClickListener { item ->
            when {
                item.itemId == RESCAN_ITEM -> rescanLenses()
                item.itemId == DIAGNOSTICS_ITEM -> sendDiagnostics()
                item.itemId == BENCH_ITEM -> openBench()
                item.itemId == RECHECK_ITEM -> recheckRecording()
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

    // ---- Profiles ----

    /** The current settings as a profile called [name]. */
    private fun snapshotProfile(name: String) = Profile(
        name = name, look = currentView()?.label ?: ViewEntry.LogView.label,
        lutStrength = lutStrength, saturation = saturationPct, vibrance = vibrancePct,
        wbWarm = wbWarmth, wbTint = wbTint, edrOn = edrOn, edrHi = edrHighlights, edrLo = edrShadows,
        texture = photoTexture, clarity = photoClarity, dehaze = photoDehaze,
        aspect = aspect, superpixel = superpixel, fps = capture.fps, codec = codec, bitrate = bitrateMbps,
        recordLook = bakeLut, logProfile = profile,
    )

    /** Whether the current settings are [p]'s (the list marks it). */
    internal fun profileActive(p: Profile) = p.sameSettings(snapshotProfile(p.name))

    /** Profiles can't change the format mid-clip or mid-burst. */
    internal fun profilesUsable() = recorder == null && !stopping && !photoBusy

    internal fun promptSaveProfile() = askProfileName("Save as profile",
        "Keeps the look with its Adjust, eDR and Texture / Clarity / Dehaze, and the video format " +
            "(not exposure, the lens or noise reduction). A name you already use updates that profile.",
        profiles.firstOrNull { profileActive(it) }?.name ?: "", "Save") { saveProfile(it) }

    // No keyboard at first: in landscape it covers the dialog's buttons (Delete).
    internal fun promptEditProfile(p: Profile) = askProfileName("Rename \"${p.name}\"",
        "Or share it as a file (its look goes with it; whoever imports it keeps their own video format), or delete it.", p.name, "Rename",
        extras = listOf("Share" to { shareProfile(p) }, "Delete" to { deleteProfile(p) }),
        keyboard = false) { renameProfile(p, it) }

    /** A look made in the editor or an imported LUT: the user's to delete (Log and the built-in looks aren't). */
    internal fun deletable(v: ViewEntry) = v is ViewEntry.UserLook || v is ViewEntry.CubeFile

    private fun fileOf(v: ViewEntry): File? = when (v) {
        is ViewEntry.UserLook -> v.look.file ?: File(lutDir(), Look.safeName(v.look.name) + Look.SUFFIX)
        is ViewEntry.CubeFile -> v.file
        else -> null
    }

    /** A look's or LUT's name as the lists show it under their headings: no "★ " / "LUT ", no ".cube.txt". */
    internal fun plainName(v: ViewEntry) =
        v.label.removePrefix("★ ").removePrefix("LUT ").replace(Regex("""(\.cube)?(\.txt)?$""", RegexOption.IGNORE_CASE), "")

    /**
     * Asks, then deletes one of the user's looks or imported LUTs (owner, 2026-11-13: "make it so
     * that self created + imported LUTs are deletable"). Not mid-clip: the clip may be recording it.
     */
    internal fun promptDeleteView(v: ViewEntry) {
        if (!deletable(v)) return
        if (!profilesUsable()) return note("Looks can be deleted after the clip")
        val name = plainName(v)
        val users = profiles.filter { it.look == v.label }.map { "\"${it.name}\"" }
        val message = (if (v is ViewEntry.CubeFile) "The LUT is removed from AuthRec. The file you imported it from stays where it is."
            else "The look and its settings are removed.") + when (users.size) {
            0 -> ""
            1 -> " The profile ${users[0]} uses it and will open without a look."
            else -> " The profiles ${users.joinToString(", ")} use it and will open without a look."
        }
        if (kit.glass) {
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(20), dp(24), dp(4))
                addView(kit.label("Delete \"$name\"?", 18f).apply { typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) })
                addView(kit.label(message, 13f, dim = true), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
            }
            GlassDialog.show(this, kit, content, listOf(GlassDialog.Action("Cancel"), GlassDialog.Action("Delete", main = true, danger = true) { deleteView(v) }),
                turn = ui.heldTurn)
        } else {
            android.app.AlertDialog.Builder(this)
                .setTitle("Delete \"$name\"?")
                .setMessage(message)
                .setPositiveButton("Delete") { _, _ -> deleteView(v) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun deleteView(v: ViewEntry) {
        val file = fileOf(v) ?: return
        if (!profilesUsable()) return note("Looks can be deleted after the clip")
        val name = plainName(v)
        if (file.exists() && !file.delete()) return note("\"$name\" couldn't be deleted")
        if (v is ViewEntry.CubeFile) prefs.edit().remove("lutInput:${file.name}").apply()
        // The look in use stays; if it was this one, the first built-in look takes over.
        val current = views.getOrNull(viewIndex)?.label
        refreshViews(selectLabel = current.takeIf { it != v.label })
        applyView()
        note("\"$name\" deleted")
        settingsChanged()
    }

    /** Picks look or LUT [i] of [views]. */
    internal fun selectView(i: Int) {
        if (i !in views.indices || (simple && views[i] is ViewEntry.LogView)) return
        viewIndex = i
        applyView()
        settingsChanged()
    }

    /** What the imported LUT [file] gets converted to before the lookup (null: our log as is). */
    internal fun setLutInput(file: File, p: LogProfile?) {
        prefs.edit().putString("lutInput:${file.name}", p?.name ?: "NONE").apply()
        applyView()
        updateUi()
    }

    /** Sets everything [p] holds at once (one save, one redraw); its look is found by name. */
    internal fun applyProfile(p: Profile) {
        if (recorder != null || stopping || photoBusy) return
        val look = views.indexOfFirst { it.label == p.look }.takeIf { it >= 0 && !(simple && views[it] is ViewEntry.LogView) }
        look?.let { viewIndex = it }
        lutStrength = p.lutStrength.coerceIn(0, 100)
        saturationPct = p.saturation.coerceIn(0, 200)
        vibrancePct = p.vibrance.coerceIn(-100, 100)
        wbWarmth = p.wbWarm.coerceIn(-100, 100)
        wbTint = p.wbTint.coerceIn(-100, 100)
        edrOn = p.edrOn
        edrHighlights = p.edrHi.coerceIn(-100, 100)
        edrShadows = p.edrLo.coerceIn(-100, 100)
        photoTexture = p.texture.coerceIn(-100, 100)
        photoClarity = p.clarity.coerceIn(-100, 100)
        photoDehaze = p.dehaze.coerceIn(-100, 100)
        profile = p.logProfile
        renderer?.profile = profile
        aspect = p.aspect
        superpixel = p.superpixel
        bitrateMbps = p.bitrate
        if (bitrateMbps !in bitrateOptions()) bitrateMbps = bitrateOptions().minBy { abs(it - bitrateMbps) }
        bakeLut = p.recordLook
        applyView()
        applyLookAdjust()
        applyWbShift()
        applyTone()
        applyPhotoFinish()
        applyAspect()
        if (p.fps != capture.fps) setCapture(capture.copy(fps = p.fps))
        setCodec(p.codec) // saves and redraws; rebuilds the GL config for another bit depth
        note("Profile \"${p.name}\"" + if (look == null) " (its look \"${p.look}\" isn't here any more)" else "")
    }

    private fun saveProfile(typed: String) {
        val name = typed.ifEmpty { "Profile ${profiles.size + 1}" }
        val p = snapshotProfile(name)
        val i = profiles.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        profiles = if (i >= 0) profiles.toMutableList().also { it[i] = p } else profiles + p
        Profile.save(prefs, profiles)
        note("Profile \"$name\" ${if (i >= 0) "updated" else "saved"}")
        updateUi()
    }

    private fun renameProfile(p: Profile, name: String) {
        if (name.isEmpty() || name == p.name) return
        if (profiles.any { it !== p && it.name.equals(name, ignoreCase = true) }) return note("There's already a profile called \"$name\"")
        profiles = profiles.map { if (it === p) it.copy(name = name) else it }
        Profile.save(prefs, profiles)
        note("Profile renamed to \"$name\"")
    }

    private fun deleteProfile(p: Profile) {
        profiles = profiles.filter { it !== p }
        Profile.save(prefs, profiles)
        note("Profile \"${p.name}\" deleted")
    }

    /**
     * A name for a profile, in the layout's own dialog (Glass's panel, or the system's in Classic):
     * [action] gets the trimmed text; [extras] are more buttons (Share, Delete; the system's dialog
     * has room for the last only: Classic shares from the look menu).
     */
    private fun askProfileName(title: String, message: String, initial: String, action: String,
                               extras: List<Pair<String, () -> Unit>> = emptyList(), keyboard: Boolean = true, onName: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            setText(initial)
            setSelectAllOnFocus(true)
            isSingleLine = true
            hint = "e.g. B&W Dreamy"
            filters = arrayOf(android.text.InputFilter.LengthFilter(Profile.MAX_NAME))
            // The screen is landscape: a full-screen keyboard editor would hide the dialog.
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        val done = { onName(input.text.toString().trim()) }
        val dialog: android.app.Dialog = if (kit.glass) {
            input.setTextColor(Glass.TEXT)
            input.setHintTextColor(Glass.TEXT_DIM)
            input.backgroundTintList = android.content.res.ColorStateList.valueOf(Glass.TEXT_DIM)
            val explanation = kit.label(message, 13f, dim = true)
            val buttonsWhileTyping = android.widget.FrameLayout(this)
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(20), dp(24), dp(4))
                addView(kit.label(title, 18f).apply { typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) })
                addView(explanation, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
                addView(LinearLayout(this@CameraActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(buttonsWhileTyping, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                }, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
            }
            GlassDialog.show(this, kit, content, extras.map { (text, run) -> GlassDialog.Action(text, danger = text == "Delete") { run() } } + listOf(
                GlassDialog.Action("Cancel"),
                GlassDialog.Action(action, main = true) { done() },
            ), turn = ui.heldTurn, typing = GlassDialog.Typing(buttonsWhileTyping, listOf(explanation)))
        } else {
            android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setView(input)
                .setPositiveButton(action) { _, _ -> done() }
                .setNegativeButton("Cancel", null)
                .apply { extras.lastOrNull()?.let { (text, run) -> setNeutralButton(text) { _, _ -> run() } } }
                .show()
        }
        input.setOnEditorActionListener { _, id, _ ->
            if (id != android.view.inputmethod.EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            dialog.dismiss()
            done()
            true
        }
        if (keyboard) {
            // (Glass's dialog makes room for the keyboard itself: it needs "resize" to hear of it.)
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                if (kit.glass) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE else 0)
            input.requestFocus()
        }
    }

    /** Drop-down of every look and LUT. */
    internal fun showViewMenu(anchor: View = ui.lookAnchor) {
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.setGroupDividerEnabled(true)
        // Profiles first: a whole look (and format) in one tap. Not while recording or taking a photo.
        val free = profilesUsable()
        val now = snapshotProfile("")
        // A heading, not a choice: small and grey (disabled alone, it looked like any other item).
        if (profiles.isNotEmpty()) menu.menu.add(PROFILE_GROUP, PROFILE_HEADER, 0, android.text.SpannableString("PROFILES").apply {
            setSpan(android.text.style.RelativeSizeSpan(0.75f), 0, length, 0)
            setSpan(android.text.style.ForegroundColorSpan(Color.GRAY), 0, length, 0)
        }).isEnabled = false
        profiles.forEachIndexed { i, p ->
            menu.menu.add(PROFILE_GROUP, PROFILE_BASE + i, 1 + i, (if (p.sameSettings(now)) "● " else "") + p.name).isEnabled = free
        }
        menu.menu.add(PROFILE_GROUP, PROFILE_SAVE, 1 + profiles.size, "Save current as profile…")
        if (profiles.isNotEmpty()) {
            val sub = menu.menu.addSubMenu(PROFILE_GROUP, PROFILE_EDIT_MENU, 2 + profiles.size, "Rename or delete a profile ▸")
            profiles.forEachIndexed { i, p -> sub.add(PROFILE_EDIT_GROUP, PROFILE_EDIT_BASE + i, i, p.name) }
            val share = menu.menu.addSubMenu(PROFILE_GROUP, PROFILE_SHARE_MENU, 3 + profiles.size, "Share a profile as a file ▸")
            profiles.forEachIndexed { i, p -> share.add(PROFILE_EDIT_GROUP, PROFILE_SHARE_BASE + i, i, p.name) }
        }
        menu.menu.add(PROFILE_GROUP, PROFILE_IMPORT, 4 + profiles.size, "Import a preset file…")
        val first = 5 + profiles.size  // the looks' place in the menu, after the profiles
        views.forEachIndexed { i, v ->
            if (simple && v is ViewEntry.LogView) return@forEachIndexed
            val group = when (v) {
                ViewEntry.LogView -> 0
                is ViewEntry.BuiltIn -> 1
                is ViewEntry.UserLook -> 2
                is ViewEntry.CubeFile -> 3
            }
            menu.menu.add(group, i, first + i, (if (i == viewIndex) "● " else "") + v.label)
        }
        // An imported LUT is built for one log format; ours gets converted to it before the lookup.
        val cube = views.getOrNull(viewIndex) as? ViewEntry.CubeFile
        if (cube != null && !simple) {
            val current = lutInputOf(cube.file)
            val sub = menu.menu.addSubMenu(4, LUT_INPUT_MENU, first + views.size, "LUT expects: ${current?.label ?: "our log as is"} ▸")
            sub.add(5, LUT_INPUT_BASE, 0, (if (current == null) "● " else "") + "Our log as is (no conversion)")
            LogProfile.entries.forEach { p -> sub.add(5, LUT_INPUT_BASE + 1 + p.ordinal, p.ordinal + 1, (if (current == p) "● " else "") + p.label) }
        }
        if (!simple) menu.menu.add(6, IMPORT_ITEM, first + views.size + 1, "Import a .cube LUT…")
        // The user's own looks and imported LUTs can go again (Glass: hold one in the list).
        val own = views.filter { deletable(it) }
        if (own.isNotEmpty() && !simple) {
            val sub = menu.menu.addSubMenu(6, VIEW_DELETE_MENU, first + views.size + 2, "Delete a look or LUT ▸")
            own.forEachIndexed { i, v -> sub.add(PROFILE_EDIT_GROUP, VIEW_DELETE_BASE + i, i, v.label) }
        }
        menu.setOnMenuItemClickListener { item ->
            when {
                item.itemId == LUT_INPUT_MENU || item.itemId == PROFILE_EDIT_MENU || item.itemId == PROFILE_SHARE_MENU || item.itemId == VIEW_DELETE_MENU ->
                    return@setOnMenuItemClickListener false // opens the submenu
                item.itemId == PROFILE_SAVE -> promptSaveProfile()
                item.itemId == PROFILE_IMPORT -> pickLut()
                item.itemId in PROFILE_SHARE_BASE until PROFILE_SHARE_BASE + profiles.size -> shareProfile(profiles[item.itemId - PROFILE_SHARE_BASE])
                item.itemId in PROFILE_EDIT_BASE until PROFILE_EDIT_BASE + profiles.size -> promptEditProfile(profiles[item.itemId - PROFILE_EDIT_BASE])
                item.itemId in PROFILE_BASE until PROFILE_BASE + profiles.size -> applyProfile(profiles[item.itemId - PROFILE_BASE])
                item.itemId == IMPORT_ITEM -> pickLut()
                item.itemId in VIEW_DELETE_BASE until VIEW_DELETE_BASE + own.size -> promptDeleteView(own[item.itemId - VIEW_DELETE_BASE])
                item.itemId in LUT_INPUT_BASE..LUT_INPUT_BASE + LogProfile.entries.size -> cube?.let { setLutInput(it.file, LogProfile.entries.getOrNull(item.itemId - LUT_INPUT_BASE - 1)) }
                else -> selectView(item.itemId)
            }
            true
        }
        menu.show()
    }

    /**
     * The log format an imported LUT expects: what the user picked, else a guess from its file
     * name (most are named after their input, e.g. "…SLog3…"), else null (apply to our log as is).
     */
    internal fun lutInputOf(file: File): LogProfile? {
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
        if (photoBusy) return
        EventLog.log("Switching to ${l.label} (${l.key})")
        lensIndex = index
        if (remember) prefs.edit().putString("lens", l.key).apply()
        cameraFailures = 0
        sessionVariant = 0
        layoutFailures = 0
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
        rememberAutoGain()
        camera?.reader?.setOnImageAvailableListener(null, null)
        renderer?.release()
        camera?.release()
        camera = null
        renderer = null
    }

    /** The lens the current renderer works for (lensIndex already names the next one during a switch). */
    private var rendererLens: String? = null

    /**
     * Each lens's auto gain (how far the RAW sits under the ISP's rendering), per phone model: a lens
     * starts from it instead of a fixed default, and keeps it when EV is moved before it was measured.
     */
    private fun autoGainKey(lens: String) = "autoGain_${Build.MODEL}_$lens"

    private fun rememberAutoGain() {
        val r = renderer ?: return
        val lens = rendererLens ?: return
        if (r.autoGainTrusted && !r.autoGainEv.isNaN()) prefs.edit().putFloat(autoGainKey(lens), r.autoGainEv).apply()
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
            if (id != null && (id in unavailableCameras || !cameraThere(id)) && SystemClock.elapsedRealtime() - retryWaitStartMs < 10_000) {
                info.postDelayed(this, 300)
                return
            }
            retryPending = false
            if (resumed && !scanning) restartPipeline()
        }
    }

    /**
     * Whether the camera service knows camera [id] just now. While its HAL restarts after a crash
     * it knows none, hidden lenses included (which the list of cameras never names).
     */
    private fun cameraThere(id: String): Boolean = SystemClock.elapsedRealtime() >= debugGoneUntilMs && runCatching {
        getSystemService(android.hardware.camera2.CameraManager::class.java).getCameraCharacteristics(id)
    }.isSuccess

    /** Debug (adb camgone=ms): until then the app behaves as if the camera service had no cameras. */
    private var debugGoneUntilMs = 0L

    /** The lens and time of the last second go given to a zoom route that failed after working (see [onCameraFailure]). */
    private var secondGoKey: String? = null
    private var secondGoMs = 0L

    /**
     * The lens couldn't even be looked up ([reason]): the camera service doesn't have it just now.
     * Tried again like any failure, where it used to end on a black screen with the service's
     * error text until the app was reopened (X14, 2026-11-13: its camera service died 15 s into a
     * clip, and the backup lens the app turned to wasn't there yet).
     */
    private fun cameraNotThere(reason: String) {
        val lens = lenses.getOrNull(lensIndex)
        cameraFailures++
        if (resumed && cameraFailures <= MAX_CAMERA_RETRIES) {
            val delay = minOf(4000L, 600L shl (cameraFailures - 1))
            EventLog.log("Lens ${lens?.key}: not there; retry $cameraFailures/$MAX_CAMERA_RETRIES in $delay ms")
            showMessage("Camera not available. Retrying ($cameraFailures/$MAX_CAMERA_RETRIES)…")
            retryPending = true
            retryWaitStartMs = SystemClock.elapsedRealtime() + delay
            info.postDelayed(retryCamera, delay)
            return
        }
        EventLog.log("Giving up on lens ${lens?.key}: not there after $cameraFailures tries")
        showMessage("Camera unavailable: $reason. Lens button → Rescan lenses, or Send diagnostics")
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
        // A zoom route that had been delivering for seconds isn't a broken route: the failure is
        // more likely the camera service's (its HAL died 130 s into the X14's 2.6x, and the backup
        // lens wasn't there either until it had restarted). It gets one more go, once a minute;
        // failing again, or before it delivered, it goes to its backup as before.
        val key = lenses.getOrNull(lensIndex)?.key
        val now = SystemClock.elapsedRealtime()
        val secondGo = cam.resultsSeen >= 150 && (key != secondGoKey || now - secondGoMs > 60_000)
        if (secondGo) {
            secondGoKey = key
            secondGoMs = now
        } else if (fallBackToBackup(reason)) return
        val lens = lenses.getOrNull(lensIndex)
        val noFrames = (renderer?.frameCounter ?: 0L) == 0L
        cameraFailures++
        // Only failures where this layout was actually tried count against it: an open refused
        // while the camera service restarts (IN_USE, disconnected: realme GT ME after its HAL
        // crashed) stepped a working layout down.
        if (noFrames && cam.layoutTried) layoutFailures++
        if (noFrames && cam.layoutTried && layoutFailures % 2 == 0 && sessionVariant < cam.lastVariant) {
            sessionVariant++
            layoutFailures = 0
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
            if (SystemClock.elapsedRealtime() < debugGoneUntilMs) throw IllegalArgumentException("unknown device (camgone test)")
            RawCamera(this, cameraHandler, lenses.getOrNull(lensIndex), sessionVariant) { reason ->
                runOnUiThread { onCameraFailure(cam, reason) }
            }
        } catch (e: Exception) {
            EventLog.log("Camera unavailable for lens ${lenses.getOrNull(lensIndex)?.key}", e)
            cameraNotThere(e.message ?: e.javaClass.simpleName)
            return
        }
        // One GL context for preview and encoder needs the recording's bit depth up front (EglCore).
        rendererTenBit = codec.tenBit && recordingLimits?.tenBit != false
        val r = Renderer(cam.info, cam.reader, { ts -> cam.metaFor(ts) }, { ts -> cam.metaArrived(ts) }, { cam.ispLinearLogAverage },
            rendererTenBit) { stats ->
            runOnUiThread { showStats(stats) }
        }
        // A new renderer's EVO starts from nothing: so does what it had asked of the sensor.
        evoSensorEv = 0f
        r.onEvoSensor = { ev ->
            runOnUiThread {
                if (renderer === r && evoSensorEv != ev) {
                    evoSensorEv = ev
                    applyExposure()
                }
            }
        }
        r.onNrShed = { why ->
            EventLog.log("Colour clean-up off for this clip: $why")
            runOnUiThread { note("Colour clean-up off for this clip: the phone can't keep up") }
        }
        if (recordingLimits?.tenBit == false) r.tenBitRecording = false
        rendererLens = lenses.getOrNull(lensIndex)?.key
        r.seedAutoGain(rendererLens?.let { prefs.getFloat(autoGainKey(it), Float.NaN) } ?: Float.NaN)
        cam.reader.setOnImageAvailableListener({ r.onFrameAvailable() }, cameraHandler)
        cam.onResult = { r.onFrameAvailable() } // a frame waiting for its capture result
        r.profile = profile
        r.superpixel = superpixel // made effective once the camera is set (applyAspect)
        r.cleanup = cleanup
        r.nrFast = nrFast
        camera = cam
        renderer = r
        applyPhotoFinish()
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
                        layoutFailures = 0
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
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        if (capture.ae == AeMode.PRIORITY) priorityTick.run()
        refreshViews(selectLabel = views.getOrNull(viewIndex)?.label)
        applyView()
        updateUi()
        resumed = true
        if (pendingScan) findLensesThenStart(forceScan = true) else camera?.open(capture)
        // Once per phone and firmware, when the camera has settled (see RecordingCheck).
        if (recordingLimits == null) info.postDelayed({ startRecordingCheck() }, 5000)
    }

    /** What the recording check found on this phone (null: not checked yet). */
    internal var recordingLimits: RecordingCheck.Result? = null
        private set
    private var checkThread: Thread? = null

    private fun startRecordingCheck() {
        if (checkThread != null || recorder != null || stopping || scanning || !resumed || camera == null) return
        val largest = lenses.maxByOrNull { it.rawWidth.toLong() * it.rawHeight }?.let { it.rawWidth to it.rawHeight }
            ?: camera?.info?.size?.let { it.width to it.height } ?: return
        note("Checking what this phone records…")
        EventLog.log("Recording check: started, largest frame ${largest.first}x${largest.second}")
        checkThread = Thread({
            val r = runCatching { RecordingCheck.run(this, largest, BITRATES_4K.max()) { recorder != null || !resumed } }
                .onFailure { EventLog.log("Recording check failed", it) }.getOrNull()
            runOnUiThread {
                checkThread = null
                if (r == null) {
                    EventLog.log("Recording check: stopped (runs again later)")
                    return@runOnUiThread
                }
                EventLog.log("Recording check: box ${r.box?.let { "${it.first}x${it.second}" } ?: "none"}, " +
                    "10-bit ${r.tenBit}, max ${if (r.maxMbps > 0) "${r.maxMbps} Mbps" else "as offered"} (${r.summary})")
                applyRecordingLimits(r)
                val found = listOfNotNull(
                    if (!r.tenBit) "8-bit" else null,
                    r.box?.let { "4K up to ${it.first}×${it.second}" },
                    if (r.maxMbps > 0) "up to ${r.maxMbps} Mbps" else null)
                note(if (found.isEmpty()) "Recording check: everything works" else "This phone records " + found.joinToString(", "))
            }
        }, "recording-check").apply { start() }
    }

    private fun applyRecordingLimits(r: RecordingCheck.Result) {
        recordingLimits = r
        r.box?.let { encoderBox = it }
        if (!r.tenBit) renderer?.tenBitRecording = false
        if (bitrateMbps !in bitrateOptions()) setBitrate(bitrateOptions().last())
        updateUi()
    }

    /** Forgets what the check (and failed recordings) found and checks again. */
    internal fun recheckRecording() {
        RecordingCheck.clear(this)
        prefs.edit().remove(encoderBoxKey()).apply()
        recordingLimits = null
        encoderBox = null
        EventLog.log("Recording re-check requested")
        restartPipeline() // a fresh renderer finds its own 10-bit support again
        info.postDelayed({ startRecordingCheck() }, 3000)
    }

    /** The 4K frame of the current lens and aspect as recorded here, if smaller than the image (for Settings). */
    internal fun fourKLimit(): Pair<Int, Int>? {
        val cam = camera ?: return null
        val (w, _) = frameSize(cam, twoK = false)
        return recordedSize(cam, twoK = false).takeIf { it.first != w }
    }

    override fun onPause() {
        resumed = false
        rememberAutoGain()
        runCatching { unregisterReceiver(batteryReceiver) }
        getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalListener)
        orientationListener.disable()
        stopRecording() // also cancels a warm-up
        // A burst under way is finished with the frames it has; saving goes on in the background.
        if (photoBusy) renderer?.finishPhotoNow()
        photoQueued = false
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
        applyAspect() // the renderer and bitrate follow (syncResolution)
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
     * Other makers do report a thermal status, some early: the realme GT Master Edition reached
     * SEVERE (heavy throttling) at 37.5–40 °C battery, which stopped every longer recording. A
     * throttled phone only records fewer frames (the renderer skips what it can't keep up with),
     * so recording stops at CRITICAL, where the system starts shutting things down.
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
        if (recorder != null && (tempC >= HOT_STOP_C || status >= PowerManager.THERMAL_STATUS_CRITICAL)) {
            EventLog.log("Stopping recording: battery %.1f °C, thermal status $status".format(tempC))
            stopRecording(if (tempC >= HOT_STOP_C) "Stopped: phone too hot (%.1f °C; the system closes apps at about 48 °C)".format(tempC)
                else "Stopped: phone too hot (the system reports critical heat)")
        }
    }

    /** One line about heat for the info text, or null while the phone is cool. */
    private fun heatNote(): String? = when {
        batteryTempC >= HOT_STOP_C -> "🌡 %.1f °C: too hot to record, let the phone cool".format(batteryTempC)
        batteryTempC >= HOT_WARN_C -> "🌡 %.1f °C: recording stops at %.0f °C".format(batteryTempC, HOT_STOP_C)
        batteryTempC >= WARM_C -> "🌡 %.1f °C".format(batteryTempC)
        thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> "🌡 critical heat: too hot to record, let the phone cool"
        thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> "🌡 phone is throttling hard: frames will be skipped"
        thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> "🌡 phone is throttling (thermal status $thermalStatus)"
        else -> null
    }

    // ---- Recording ----

    internal fun toggleRecording() = when {
        photo -> takePhoto()
        recorder == null -> startRecording()
        else -> stopRecording()
    }

    // ---- Photo ----

    /** adb photoframes: burst length (debug; 0 = [PHOTO_FRAMES]). */
    private var debugPhotoFrames = 0

    /**
     * Photo mode's shutter: a short burst from the running RAW stream, merged (aligned) in RAW
     * and developed off the realtime path (full size, Quality colour NR on the merged frame:
     * Renderer.takePhoto), saved as a JPEG of the view and optionally the first frame's DNG
     * ([PhotoSaver]). The viewfinder keeps running, and the shutter is free again once the burst
     * is in: saving runs behind ([photoSaver]), and a press during a burst takes the next one
     * right after it (owner: "take another one almost instantly").
     */
    internal fun takePhoto() {
        val r = renderer ?: return
        val cam = camera ?: return
        if (recorder != null || stopping || scanning) return
        if (photoBusy || photosSaving.get() >= MAX_PHOTOS_SAVING) {
            photoQueued = true
            return
        }
        photoBusy = true
        photoQueued = false
        val (fullW, _, keep) = frameSize(cam)
        val turn = recordingRotation()
        // The RAW is the sensor's image: never mirrored, and the front camera's half turn not applied.
        val rawTurn = ((if (cam.info.front) (360 - poseTurn()) % 360 else poseTurn()) + if (displayQuarterTurns == 2) 180 else 0) % 360
        val lens = lenses.getOrNull(lensIndex)?.key
        // About PHOTO_BURST_NS of frames, at most PHOTO_FRAMES: Manual's long shutters get fewer.
        val frameNs = maxOf(1e9 / capture.fps, if (capture.ae == AeMode.MANUAL) capture.exposureNs.toDouble() else 0.0)
        val frames = debugPhotoFrames.takeIf { it > 0 } ?: (PHOTO_BURST_NS / frameNs).toInt().coerceIn(3, PHOTO_FRAMES)
        val battery = batteryTempC
        val raw = photoRaw
        info.postDelayed(photoTimeout, PHOTO_TIMEOUT_MS)
        updateUi()
        r.takePhoto(frames, keepRaw = raw, cropHeight = keep, onReference = { if (raw) cam.keepResult(it) }) { shot ->
            // GL thread. The tap only now, the burst taken: the motor shook a ¼ s frame when it came with the press.
            if (shot != null) tap(shutter = true)
            if (shot == null) {
                EventLog.log("Photo not taken (the camera stopped or switched)")
                runOnUiThread { photoDone("Photo not taken") }
                return@takePhoto
            }
            val dng = if (raw) cam.dngSource(shot.timestamp) else null
            savePhoto(shot, turn, dng, rawTurn, "on $lens, " + (if (turn != 0) "turned $turn°, " else "") + "battery %.1f °C".format(battery))
            runOnUiThread { photoDone(null) }
        }
    }

    /** [shot] into the save queue: JPEG (+ DNG from [dng]), the event log line, a note when saved. */
    private fun savePhoto(shot: Renderer.Photo, turn: Int, dng: Pair<android.hardware.camera2.CameraCharacteristics, android.hardware.camera2.CaptureResult>?,
                          rawTurn: Int, where: String) {
        photosSaving.incrementAndGet()
        photoSaver.execute {
            val t0 = SystemClock.elapsedRealtime()
            val saved = runCatching { PhotoSaver.save(this, shot, turn, dng, rawTurn) }
                .getOrElse { PhotoSaver.Result(null, 0, null, it.message ?: it.javaClass.simpleName) }
            val waiting = photosSaving.decrementAndGet()
            EventLog.log("${shot.summary} $where; saved in ${SystemClock.elapsedRealtime() - t0} ms" +
                (if (waiting > 0) " ($waiting more waiting)" else "") + ": " +
                (saved.jpeg?.let { "JPEG ${saved.jpegBytes / 1000} KB → $it" } ?: "no JPEG") +
                (saved.dng?.let { ", DNG → $it" } ?: "") + (saved.error?.let { " ($it)" } ?: ""))
            val msg = when {
                saved.jpeg == null -> "Photo not saved: ${saved.error}"
                saved.error != null -> "Photo saved (${saved.error})"
                saved.dng != null -> "Photo saved (JPEG + DNG)"
                else -> "Photo saved"
            }
            runOnUiThread {
                note(msg)
                if (photoQueued && !photoBusy && photo) takePhoto()
                updateUi()
            }
        }
    }

    private val photoTimeout = Runnable {
        if (!photoBusy) return@Runnable
        EventLog.log("Photo timed out")
        renderer?.cancelPhoto()
        photoDone("Photo not taken (timed out)")
    }

    /** The burst is over ([msg]: why there's no photo, else null: it's saving). */
    private fun photoDone(msg: String?) {
        info.removeCallbacks(photoTimeout)
        photoBusy = false
        msg?.let { note(it) }
        updateUi()
        applyPose()
        if (photoQueued && resumed && photo) takePhoto()
    }

    /**
     * A still while recording (asked for by someone the owner showed the app): the frame being
     * recorded, at its size, with the look (Renderer.takeSnap). No haptic tap: it would shake the clip.
     */
    internal fun takeSnap() {
        val r = renderer ?: return
        val cam = camera ?: return
        if (recorder == null || snapBusy || photosSaving.get() >= MAX_PHOTOS_SAVING) return
        snapBusy = true
        updateUi()
        val turn = recordingRotation()
        val lens = lenses.getOrNull(lensIndex)?.key
        r.takeSnap(frameSize(cam).third) { shot ->
            if (shot == null) {
                runOnUiThread {
                    snapBusy = false
                    updateUi()
                }
                return@takeSnap
            }
            savePhoto(shot, turn, null, 0, "on $lens" + if (turn != 0) ", turned $turn°" else "")
            runOnUiThread {
                snapBusy = false
                updateUi()
            }
        }
    }

    /**
     * Pre-roll: between pressing REC and the clip's first frame. The encoder already runs (its
     * frames are thrown away) while the GPU clocks up at full resolution and the encoder allocates
     * its buffers; see Recorder. Jumping straight in lost frames in some clips' first seconds.
     */
    internal var warmingUp = false
        private set
    /** A recording that failed only for want of 10-bit starts again (in 8-bit) once it's stopped. */
    private var retryAfterStop = false
    private val commitRecording: Runnable = object : Runnable {
        override fun run() {
            val rec = recorder
            if (!warmingUp || rec == null) return
            if (!rec.producedOutput) {
                // Nothing from the encoder in the whole pre-roll: give it a second more, then take it
                // as a size it doesn't take (Xiaomi 12 at 4096×3072: "Failed to configure w/h/f",
                // frames swallowed, nothing out, while Android said the size was fine).
                if (!preRollExtended) {
                    preRollExtended = true
                    info.postDelayed(this, 1000)
                    return
                }
                if (encoderBox == null) {
                    encoderBox = 4096 to 2160
                    prefs.edit().putBoolean(encoderBoxKey(), true).apply()
                    EventLog.log("Encoder put out nothing at ${rec.width}x${rec.height}: retrying within 4096x2160")
                    retryAfterStop = true
                    stopRecording()
                    return
                }
            }
            commitNow()
        }
    }

    /** adb recscale=N: record at N % of the size (debug). */
    private var debugRecScale = 0

    /** One extra second of pre-roll for an encoder slow to start (see [commitRecording]). */
    private var preRollExtended = false
    /**
     * Set once an encoder silently failed on a larger frame: recordings fit inside it from then on,
     * on this phone model for good (each new session rediscovered it, ~4 s lost on the first clip).
     */
    private var encoderBox: Pair<Int, Int>? = null

    private fun encoderBoxKey() = "encoderBox_${Build.MODEL}_${effectiveCodec().name}"

    private fun commitNow() {
        warmingUp = false
        renderer?.commitRecording()
        recordStartMs = SystemClock.elapsedRealtime()
        pausedMs = 0L
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
        if (thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL) {
            showMessage("Phone too hot to record (the system reports critical heat); let it cool")
            return
        }
        syncResolution()
        val (fullW, _, keep) = frameSize(cam)
        val recCodec = effectiveCodec()
        // Scaled down where this phone's encoder doesn't take the full frame (Xiaomi 12: 4096×2176 at most).
        val (boxW, boxH) = recordedSize(cam, effectiveSuperpixel())
        // adb recscale: smaller on purpose, to exercise and time the scaled develop path.
        val (w, h) = if (debugRecScale in 30..99) (boxW * debugRecScale / 100 / 16 * 16) to (boxH * debugRecScale / 100 / 16 * 16) else boxW to boxH
        preRollExtended = false
        val turn = recordingRotation()
        val config = RecordConfig(recCodec, bitrateMbps, capture.fps, audioOn, cam.info.timestampsAreBoottime, turn)
        val rec = try {
            Recorder(this, config, w, h)
        } catch (e: Exception) {
            EventLog.log("Recorder setup failed", e)
            lastResult = "Can't record: ${e.message}"
            updateUi()
            return
        }
        rec.onFailed = { err ->
            runOnUiThread {
                if (recorder !== rec) return@runOnUiThread
                if (recCodec.tenBit && warmingUp) {
                    // The encoder took the 10-bit surface but failed on its frames: 8-bit from now on.
                    r.tenBitRecording = false
                    EventLog.log("10-bit encoding failed at the start ($err): recording in 8-bit")
                    retryAfterStop = true
                    stopRecording()
                } else {
                    stopRecording("Stopped: the encoder failed ($err)")
                }
            }
        }
        recorder = rec
        warmingUp = true
        tap()
        recordStartMs = SystemClock.elapsedRealtime()
        lastResult = null
        EventLog.log("Recording ${w}x$h (${aspect.label}) ${recCodec.name} $bitrateMbps Mbps ${capture.fps} fps on ${lenses.getOrNull(lensIndex)?.key}, " +
            "${profile.name}, ${if (bakeLut || simple) "look baked" else "log"}" +
            (if (bakeLut && !simple && (photoTexture != 0 || photoClarity != 0 || photoDehaze != 0))
                " (texture %+d clarity %+d dehaze %+d)".format(photoTexture, photoClarity, photoDehaze) else "") + ", clean $cleanup, " +
            "eDR ${if (edrOn) "$edrHighlights/$edrShadows" else "off"}, " +
            "AE ${capture.ae.name.lowercase()} EV %+.1f".format(exposureEv) +
            (camera?.latestMeta?.let { ", ISO ${it.iso} at 1/%.0f".format(1e9 / it.exposureNs.coerceAtLeast(1)) } ?: "") + ", " +
            (if (evoOn) "EVO %+.1f EV (sensor %+.1f), ".format(r.evoViewEv, r.evoSensorEv) else "") + "WB shift $wbWarmth/$wbTint, " +
            (if (mirrored()) "mirrored, " else "") +
            (if (turn != 0) "turned $turn° (phone held ${if (turn % 180 == 0) "upside down" else "upright"}), " else "") +
            "battery %.1f °C".format(batteryTempC))
        // In simple mode the recording is what you see, so bake the look in.
        r.startRecording(rec, capture.fps, bakeLut || simple, w, h, keep) { err ->
            runOnUiThread {
                if (recCodec.tenBit && !r.tenBitRecording) {
                    // Found out only now (no 10-bit surface the encoder takes): straight again in 8-bit.
                    retryAfterStop = true
                    stopRecording()
                } else {
                    lastResult = "Encoder surface failed: $err"
                    stopRecording()
                }
            }
        }
        val limits = listOfNotNull(if (recCodec != codec) "8-bit" else null, if (w != fullW) "${w}×$h" else null)
        if (limits.isNotEmpty()) note("This phone records ${limits.joinToString(", ")}")
        // The preview runs at half resolution between recordings to save power; the pre-roll runs
        // at the recorded size so the GPU has clocked up before the clip starts.
        info.postDelayed(commitRecording, if (effectiveSuperpixel()) PRE_ROLL_2K_MS else PRE_ROLL_MS)
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
        tap()
        // Measured now: recordedMs() leaves pauses out, and needs the recorder for an open one.
        val secs = recordedMs() / 1000.0
        val pauseCount = rec.pauses.count()
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
                        applyPose()
                        if (retryAfterStop) {
                            retryAfterStop = false
                            startRecording()
                        }
                    }
                    return@stop
                }
                val msg = (why?.let { "$it. " } ?: "") + "Saved ${result.frames} frames, ${result.bytes / 1_000_000} MB, " +
                    "≈%.0f Mbps".format(result.bytes * 8 / secs / 1e6) +
                    (if (result.audio) " + audio" else "") +
                    (if (pauseCount > 0) " ($pauseCount ${if (pauseCount == 1) "pause" else "pauses"})" else "") +
                    (result.error?.let { " — $it" } ?: "")
                EventLog.log("Recording: $msg → ${result.uri}")
                runOnUiThread {
                    stopping = false
                    if (why != null || result.error != null) lastResult = msg else note(msg)
                    updateUi()
                    applyPose()
                }
            }
        }
    }

    // ---- Looks and LUTs ----

    private fun lutDir() = File(getExternalFilesDir(null), "luts").apply { mkdirs() }

    /**
     * Copies the .cube LUTs and .look.json looks bundled in assets/luts into the LUT folder: once
     * each (deleting one there is respected), and never over a file of the same name (the user's
     * own copy, maybe edited since).
     */
    private fun installBundledLuts() {
        val installed = prefs.getStringSet("bundledLuts", emptySet())!!
        val names = assets.list("luts").orEmpty().filter {
            (it.endsWith(".cube", ignoreCase = true) || it.endsWith(Look.SUFFIX)) && it !in installed
        }
        names.forEach { name ->
            val target = File(lutDir(), name)
            if (target.exists()) return@forEach
            runCatching { assets.open("luts/$name").use { input -> target.outputStream().use { input.copyTo(it) } } }
        }
        if (names.isNotEmpty()) prefs.edit().putStringSet("bundledLuts", installed + names).apply()
    }

    /**
     * Adds the presets bundled in assets/presets (`<name>.authrec.json`, as Share writes them: the
     * owner's own, made on the phone) to the profiles, each with its look: once each, so one the
     * user deleted or renamed stays that way, and with this phone's video format like any import.
     */
    private fun installBundledPresets() {
        val installed = prefs.getStringSet("bundledPresets", emptySet())!!
        val names = assets.list("presets").orEmpty().filter { it.endsWith(Presets.SUFFIX) && it !in installed }.sorted()
        names.forEach { name ->
            runCatching {
                val text = assets.open("presets/$name").use { it.readBytes().toString(Charsets.UTF_8) }
                val result = Presets.import(text, lutDir(), profiles, here = {
                    it.copy(aspect = aspect, superpixel = superpixel, fps = capture.fps, codec = codec, bitrate = bitrateMbps,
                        recordLook = bakeLut, logProfile = profile)
                }) { file, expects -> prefs.edit().putString("lutInput:${file.name}", expects?.name ?: "NONE").apply() }
                if (profiles.none { it === result.profile }) profiles = profiles + result.profile
                EventLog.log("Bundled preset $name: ${result.note}")
            }.onFailure { EventLog.log("Bundled preset $name couldn't be installed", it) }
        }
        if (names.isNotEmpty()) {
            Profile.save(prefs, profiles)
            prefs.edit().putStringSet("bundledPresets", installed + names).apply()
        }
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

    /** Picks a file to import: a .cube LUT or a preset ([importFile] tells them apart). */
    internal fun pickLut() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // .cube has no registered MIME type (and a preset may arrive as anything)
        }, REQ_PICK_LUT)
    }

    internal fun openRecordings() {
        startActivity(Intent(this, RecordingsActivity::class.java))
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
            REQ_PICK_LUT -> data.data?.let { importFile(it) }
            REQ_EDIT_LOOK -> data.getStringExtra(LookEditorActivity.EXTRA_LOOK)?.let { path ->
                // onResume follows and re-applies; point the selection at the saved look first.
                val saved = runCatching { Look.load(File(path)) }.getOrNull() ?: return
                refreshViews(selectLabel = ViewEntry.UserLook(saved).label)
                savePrefs()
            }
        }
    }

    // ---- Presets: a profile and its look as one file (Presets) ----

    /** Writes [p] with its look to Download/AuthRec and opens the share sheet on it. */
    internal fun shareProfile(p: Profile) {
        val source = when (val v = views.firstOrNull { it.label == p.look }) {
            null -> return note("Can't share \"${p.name}\": its look \"${p.look}\" isn't here any more")
            ViewEntry.LogView -> Presets.LookSource.Log
            is ViewEntry.BuiltIn -> Presets.LookSource.BuiltIn(v.look.name)
            is ViewEntry.UserLook -> Presets.LookSource.Made(v.look)
            is ViewEntry.CubeFile -> Presets.LookSource.Cube(v.file, lutInputOf(v.file))
        }
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        Thread {
            runCatching {
                val name = Look.safeName(p.name) + Presets.SUFFIX
                // An earlier export of the same name goes: MediaStore would call this one "….authrec (2).json".
                runCatching {
                    contentResolver.delete(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.Downloads.RELATIVE_PATH} = ?",
                        arrayOf(name, android.os.Environment.DIRECTORY_DOWNLOADS + "/AuthRec/"))
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, Presets.MIME)
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/AuthRec")
                }) ?: error("can't create $name")
                contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(Presets.encode(p, source, version)) }
                EventLog.log("Preset \"${p.name}\" written to Download/AuthRec/$name")
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = Presets.MIME
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "AuthRec preset: ${p.name}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Share \"${p.name}\""))
            }.onFailure { e ->
                EventLog.log("Sharing preset failed", e)
                runOnUiThread { note("Couldn't share \"${p.name}\": ${e.message}") }
            }
        }.start()
    }

    /** A file picked in Import or opened from another app: a preset, or else a .cube LUT. */
    private fun importFile(uri: Uri) {
        val head = runCatching {
            contentResolver.openInputStream(uri)!!.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (out.size() <= Presets.MAX_FILE_BYTES) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        }.getOrElse { return note("Couldn't read that file: ${it.message}") }
        // Presets are JSON; a .cube starts with a title, a comment or numbers.
        if (head.firstOrNull { !it.toInt().toChar().isWhitespace() }?.toInt()?.toChar() != '{') return importLut(uri)
        if (head.size > Presets.MAX_FILE_BYTES) return note("Can't import: the file is too large")
        val result = runCatching {
            Presets.import(String(head, Charsets.UTF_8), lutDir(), profiles, here = {
                it.copy(aspect = aspect, superpixel = superpixel, fps = capture.fps, codec = codec, bitrate = bitrateMbps,
                    recordLook = bakeLut, logProfile = profile)
            }) { file, expects ->
                prefs.edit().putString("lutInput:${file.name}", expects?.name ?: "NONE").apply()
            }
        }.getOrElse { e ->
            EventLog.log("Preset import failed", e)
            return note("Can't import: ${e.message ?: "not an AuthRec preset"}")
        }
        val p = result.profile
        if (profiles.none { it === p }) {
            profiles = profiles + p
            Profile.save(prefs, profiles)
        }
        EventLog.log("Preset: ${result.note} (look \"${p.look}\")")
        refreshViews(selectLabel = views.getOrNull(viewIndex)?.label)
        // In use at once, unless a clip or a burst is under way: then it waits in the list.
        val usable = profilesUsable()
        if (usable) applyProfile(p)
        note(result.note + if (usable) "" else ": it's in Looks for after the clip")
        updateUi()
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
        viewLut = lut
        applyEvo()
    }

    private fun showStats(stats: Renderer.Stats) {
        val cam = camera ?: return
        val m = stats.meta
        val shutter = if (m != null && m.exposureNs > 0) "1/${(1e9 / m.exposureNs).toInt()}" else "-"
        val (fw, fh) = frameSize(cam)
        val out = if (photo) "Photo ${cam.info.size.width}×${(cam.info.size.height * frameSize(cam).third).roundToInt()}" +
            (if (aspect != FrameAspect.OPEN_GATE) " ${aspect.label}" else "")
        else "$fw×$fh" + (if (aspect != FrameAspect.OPEN_GATE) " ${aspect.label}" else "") + if (effectiveSuperpixel()) " superpixel" else ""
        // Sensor ISO times the digital gain before the log curve: what the noise looks like.
        val gainEv = renderer?.totalGainEv ?: 0f
        val effectiveIso = m?.iso?.let { (it * Math.pow(2.0, gainEv.toDouble())).roundToInt() }
        exposureSlider.warn = (effectiveIso ?: 0) >= NOISY_ISO
        droppedFrames = stats.droppedWhileRecording
        lastEffectiveIso = effectiveIso
        maybeSuggestEdr()
        ui.onStats()
        info.text = unbroken(buildString {
            heatNote()?.let { append(it) }
            if (!simple) {
                if (isNotEmpty()) append("\n")
                // Pro records the look only with Settings → Record on Look (Simple always): said here,
                // or Log clips come as a surprise (owner: "Pro only records Log").
                append("$out · ${profile.label}" + (if (photo) "" else " · records " + if (bakeLut) "the look" else "Log") + "\n")
                append("ISO ${m?.iso ?: "-"} (≈${effectiveIso?.let { isoText(it) } ?: "-"} with gain) · $shutter · gain %+.1f EV · %.1f fps · frame %.1f ms".format(
                    gainEv, stats.fps, stats.gpuMs))
                // A viewfinder drawing fewer frames than are recorded looks choppy (the GL thread is behind).
                if (stats.viewFps < stats.fps - 2) append(" · view %.0f fps".format(stats.viewFps))
                renderer?.lutInput?.takeIf { it != profile && views.getOrNull(viewIndex) is ViewEntry.CubeFile }
                    ?.let { append("\nLUT expects ${it.label}: converting from ${profile.label}") }
                if (capture.ae == AeMode.PRIORITY && priorityAtLimit) append("\n⚠ priority limits reached: image darker than target")
                renderer?.takeIf { evoOn }?.let { r ->
                    append("\nEVO " + when (r.evoMode) {
                        Evo.Mode.OFF -> "off in Manual"
                        Evo.Mode.HOLD -> "held %+.1f EV".format(r.evoViewEv)
                        Evo.Mode.RUN -> "%+.1f EV".format(r.evoViewEv)
                    })
                    if (abs(r.evoSensorEv) > 0.01f) append(" (sensor %+.1f)".format(r.evoSensorEv))
                    if (r.evoBlownWithout > 0.005f) append(" · blown %.0f%% → %.0f%%".format(r.evoBlownWithout * 100, r.evoBlown * 100))
                    if (!r.sensorRangeStops.isNaN()) append(" · sensor range ≈%.1f stops".format(r.sensorRangeStops))
                }
                renderer?.recoverableClipFraction?.takeIf { it > 0.01f && !edrOn && edrAvailable() }
                    ?.let { append("\n%.0f%% of the view blown out; eDR can bring it back".format(it * 100)) }
                if (m != null && m.shading == null) append("\nno lens shading map")
            }
            recorder?.takeIf { !warmingUp && (classicUi || !simple) }?.let {
                val secs = recordedMs() / 1000
                if (isNotEmpty()) append("\n")
                append("%s %d:%02d · %s".format(if (paused) "❚❚ PAUSED" else "● REC", secs / 60, secs % 60, if (bakeLut || simple) "look baked in" else "clean log"))
                if (!simple) append(" · %d frames · %d MB · dropped %d".format(it.framesWritten, it.bytesWritten / 1_000_000, stats.droppedWhileRecording))
            }
            lastResult?.let { if (isNotEmpty()) append("\n"); append(it) }
        })
    }

    /**
     * A stats line wraps between its " · " items, not inside one: upright, "frame 8.9 ms" left its
     * "ms" alone on the next line (QA, 2026-11-13). Long items (a file's name) still wrap.
     */
    private fun unbroken(text: String) = text.lines().joinToString("\n") { line ->
        if (" · " !in line) line
        else line.split(" · ").joinToString("\u00A0· ") { if (it.length <= 30) it.replace(' ', '\u00A0') else it }
    }

    /** An ISO to read: in k / M past 100 000 (the EV slider's gain on a dark scene gave "≈2026213"). */
    internal fun isoText(iso: Int) = when {
        iso >= 1_000_000 -> "%.1fM".format(iso / 1e6)
        iso >= 100_000 -> "${(iso / 1000.0).roundToInt()}k"
        else -> "$iso"
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
     * tonehi / tonelo = -100..100 (the eDR curve; switches eDR on), edr = true|false, evo = true|false (`--ez`), evostrength = 0..3 (`--ei`),
     * evoverbose = true (`--ez`: log EVO's state twice a second), evodim = weight (`--ef`: EVO's midtone guard, 0 = off),
     * evodump = true (`--ez`: log EVO's measure of the scene once, 3 s later, for tools/evosim),
     * explog = frames (`--ei`: log every frame's exposure and each part of the gain for that many frames),
     * evostep = EV (`--ef`, with explog: EVO asks this of the sensor and takes it back every 75 logged frames),
     * camgone = ms (`--ei`: 2.5 s later the camera fails and the camera service has no cameras for that long),
     * wbwarm / wbtint = -100..100 (white balance trim), mirror = true (front camera as a mirror image),
     * ui = glass | classic (rebuilds the screen),
     * edrhint = reset (the one-time eDR suggestion may come again) | show (show it now),
     * aspect = 4:3 | 16:9 | 2:1 | 2.39:1 (recorded frame), glasstiming = true (log the backdrop's GPU time),
     * pipetiming = true (log each pipeline stage's time, with glFinish between them),
     * nrdebug = true (log colour NR's frame-wide motion measure every 30 frames),
     * bakedview = false (view math per pixel instead of the baked view LUT, to compare),
     * recdebug = split | dropkey | bars | off (encoder output handling: deliver frames in pieces /
     * lose the first key frame; bars: colour bars in the recording; see Recorder),
     * open = settings | adjust | ae | iso | shutter | wb | fps | recordings (open that panel or
     * screen, for screenshots on phones that refuse injected taps),
     * recscale = 30..99 (`--ei`: record at that % of the size, to time the scaled develop path; 0 = off),
     * slowframe = ms (`--ei`: each recorded frame takes that much longer, a hot phone's backlog; 0 = off),
     * nightiso = effective ISO where the night limit starts (`--ei`, until the next lens switch; 0 = off),
     * nrfast = true|false (`--ez`: colour NR's Fast variant instead of Quality),
     * photo = true|false (`--ez`: Photo mode), cmd = photo (take one, in Photo mode), photoraw =
     * true|false (`--ez`: a DNG too), photoframes = burst length (`--ei`, 0 = default),
     * photomerge = false (`--ez`: no burst merge, to compare), photoalign = false (`--ez`: merged
     * without aligning), photoshake = quads (`--ei`: each burst frame read that far off, 0 = off),
     * texture / clarity / dehaze = −100..100 (`--ei`: Photo mode's dials), cmd = snap (a still while recording),
     * presetsave / preset / presetdelete = name (save the current settings as a profile, apply one, delete one),
     * presetshare = name (write it as a file and open the share sheet), presetimport = uri (import that file),
     * turn = 0 | 90 | 180 | 270 (`--ei`: lay the controls out for that pose until the phone's next real turn),
     * torch = true|false (`--ez`).
     */
    private fun handleCommands(intent: Intent?) {
        // Launchers add their own extras (Xiaomi's sends e.g. "profile"); only adb-style intents
        // without the launcher category are commands.
        if (intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true) return
        val extras = intent?.extras ?: return
        if (recorder == null) {
            if (extras.containsKey("simple")) setSimple(extras.getBoolean("simple"))
            if (extras.containsKey("photo")) setPhoto(extras.getBoolean("photo"))
            if (extras.containsKey("photoraw")) setPhotoRaw(extras.getBoolean("photoraw"))
            extras.getString("codec")?.let { setCodec(VideoCodec.valueOf(it)) }
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
            if (extras.containsKey("torch")) c = c.copy(torch = extras.getBoolean("torch"))
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
        if (extras.containsKey("nrfast")) setNrFast(extras.getBoolean("nrfast"))
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
        if (extras.containsKey("evo")) setEvo(extras.getBoolean("evo"))
        if (extras.containsKey("evostrength")) setEvoStrength(extras.getInt("evostrength"))
        if (extras.containsKey("evoverbose")) renderer?.evoVerbose = extras.getBoolean("evoverbose")
        if (extras.containsKey("evostep")) renderer?.evoTestStep = extras.getFloat("evostep")
        if (extras.containsKey("camgone")) info.postDelayed({
            debugGoneUntilMs = SystemClock.elapsedRealtime() + extras.getInt("camgone")
            camera?.let { onCameraFailure(it, "camera error DEVICE (camgone test)") }
        }, 2500)
        if (extras.containsKey("explog")) renderer?.expLogFrames = extras.getInt("explog")
        if (extras.containsKey("evodim")) renderer?.evoDimWeight = extras.getFloat("evodim")
        if (extras.getBoolean("evodump")) info.postDelayed({ renderer?.evoDump = true }, 3000)
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
        if (extras.containsKey("nrdebug")) renderer?.nrDebug = extras.getBoolean("nrdebug")
        if (extras.containsKey("nightiso")) renderer?.nightKneeIso = extras.getInt("nightiso").toFloat()
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
        if (extras.containsKey("recscale")) debugRecScale = extras.getInt("recscale")
        if (extras.containsKey("slowframe")) Renderer.debugSlowFrameMs = extras.getInt("slowframe")
        if (extras.containsKey("photoframes")) debugPhotoFrames = extras.getInt("photoframes")
        if (extras.containsKey("photomerge")) Renderer.debugPhotoMerge = extras.getBoolean("photomerge")
        if (extras.containsKey("photoalign")) Renderer.debugPhotoAlign = extras.getBoolean("photoalign")
        if (extras.containsKey("photoshake")) Renderer.debugPhotoShake = extras.getInt("photoshake")
        if (extras.containsKey("texture") || extras.containsKey("clarity") || extras.containsKey("dehaze")) {
            setPhotoFinish(extras.getInt("texture", photoTexture), extras.getInt("clarity", photoClarity), extras.getInt("dehaze", photoDehaze))
        }
        if (extras.containsKey("turn")) info.postDelayed({ ui.setTurn(extras.getInt("turn")) }, 600)
        extras.getString("presetsave")?.let { saveProfile(it.trim()) }
        extras.getString("preset")?.let { name -> profiles.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { applyProfile(it) } ?: note("No profile \"$name\"") }
        extras.getString("presetshare")?.let { name -> profiles.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { shareProfile(it) } }
        extras.getString("presetimport")?.let { importFile(Uri.parse(it)) }
        extras.getString("presetdelete")?.let { name -> profiles.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { deleteProfile(it) } }
        extras.getString("open")?.let { name ->
            info.postDelayed({ if (name == "recordings") openRecordings() else ui.openForTest(name) }, 1200)
        }
        when (extras.getString("cmd")) {
            // Give the camera a moment to start when launched and told to record in one go.
            "rec" -> info.postDelayed({ if (recorder == null) startRecording() }, 1500)
            // Photo mode's shutter (switches to Photo mode first); after 2.5 s, so AE and the auto gain have settled.
            "photo" -> {
                if (!photo) setPhoto(true)
                info.postDelayed({ takePhoto() }, 2500)
            }
            "stop" -> stopRecording()
            "snap" -> takeSnap()
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
        val BITRATES_2K = listOf(25, 50, 75, 100)
        val BITRATES_4K = listOf(50, 100, 150)
        /** Largest change of Priority's exposure per update (0.3 EV). */
        private val PRIORITY_STEP_UP = Math.pow(2.0, 0.3)
        private val PRIORITY_STEP_DOWN = Math.pow(2.0, -0.3)
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
        /** Photo mode: frames in a burst (~0.3–0.5 s), and how long a photo may take before it's given up. */
        private const val PHOTO_FRAMES = 8
        private const val PHOTO_BURST_NS = 2e9
        private const val PHOTO_TIMEOUT_MS = 10_000L
        /** Photos waiting to be saved before the shutter waits too (~50 MB of picture each). */
        private const val MAX_PHOTOS_SAVING = 3
        /** The shutter's two taps, this far apart. */
        private const val SHUTTER_GAP_MS = 22L
        /** Pre-roll before a clip starts (see [warmingUp]): 4K, and 2K superpixel. */
        private const val PRE_ROLL_MS = 1000L
        private const val PRE_ROLL_2K_MS = 500L
        private const val LUT_INPUT_MENU = 9_000
        private const val LUT_INPUT_BASE = 9_001
        private const val RESCAN_ITEM = 10_000
        private const val RECHECK_ITEM = 10_004
        private const val DIAGNOSTICS_ITEM = 10_001
        private const val BENCH_ITEM = 10_002
        private const val STYLE_ITEM = 10_003
        private const val IMPORT_ITEM = 10_004
        private const val PROFILE_GROUP = 7
        private const val PROFILE_EDIT_GROUP = 8
        private const val PROFILE_HEADER = 11_000
        private const val PROFILE_SAVE = 11_001
        private const val PROFILE_EDIT_MENU = 11_002
        private const val PROFILE_BASE = 12_000
        private const val PROFILE_EDIT_BASE = 13_000
        private const val PROFILE_SHARE_MENU = 11_003
        private const val PROFILE_IMPORT = 11_004
        private const val PROFILE_SHARE_BASE = 14_000
        private const val VIEW_DELETE_MENU = 11_005
        private const val VIEW_DELETE_BASE = 15_000
    }
}
