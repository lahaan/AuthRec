package com.authrec.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.authrec.EventLog
import com.authrec.color.ColorCalibration
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Static facts about the RAW stream the pipeline needs to interpret frames. */
data class SensorInfo(
    val cameraId: String,
    val size: Size,
    /** Where red sits in the 2×2 CFA block, as (x, y). */
    val redOffset: Pair<Int, Int>,
    val sensorOrientation: Int,
    val front: Boolean,
    /** True if frame timestamps use the boot-time clock (elapsedRealtimeNanos), else CLOCK_MONOTONIC. */
    val timestampsAreBoottime: Boolean,
    /** Exposure compensation range in steps of [evStep] EV. */
    val evRange: Range<Int>,
    val evStep: Float,
    val isoRange: Range<Int>,
    val exposureRangeNs: Range<Long>,
    /** Sensor area that metering regions are expressed in. */
    val activeArray: Rect,
    /** Closest focus in diopters (1/m); 0 means fixed focus. */
    val minFocusDiopters: Float,
    val maxAfRegions: Int,
    val maxAeRegions: Int,
)

/**
 * CONTINUOUS: the ISP's own continuous AF (it ignores AF regions on Xiaomi). TAP: the ISP's
 * one-shot AF at [CaptureSettings.focusPoint], held until triggered again (the activity
 * re-triggers it to follow a tapped spot). SOFTWARE: our own contrast-detect AF drives the lens
 * like MANUAL (for lenses whose ISP AF doesn't run for third-party apps, e.g. Xiaomi's hidden
 * telephoto, or ignores spots and triggers, e.g. zoom routes).
 */
enum class AfMode { CONTINUOUS, TAP, MANUAL, SOFTWARE }

/**
 * AUTO / LOCKED: the ISP's auto exposure. MANUAL: user ISO and shutter. PRIORITY: our own loop
 * that keeps shutter and ISO inside user limits; it drives [CaptureSettings.iso] /
 * [CaptureSettings.exposureNs] like MANUAL does.
 */
enum class AeMode { AUTO, LOCKED, MANUAL, PRIORITY }

/** Everything the repeating capture request is built from. */
data class CaptureSettings(
    val fps: Int = 30,
    val ae: AeMode = AeMode.AUTO,
    /** Exposure compensation in [SensorInfo.evStep] units (AUTO and LOCKED). */
    val evSteps: Int = 0,
    /** Manual mode only. */
    val iso: Int = 400,
    val exposureNs: Long = 1_000_000_000L / 60,
    val awbLock: Boolean = false,
    val af: AfMode = AfMode.CONTINUOUS,
    /** Tapped spot in the image (0..1 on both axes) that AF and AE are weighted to; null = whole scene. */
    val focusPoint: PointF? = null,
    /** MANUAL only: 0 = infinity, [SensorInfo.minFocusDiopters] = closest. */
    val focusDiopters: Float = 0f,
)

/** Per-frame metadata from the ISP's 3A, applied by our own pipeline instead of the ISP. */
class FrameMeta(
    /** White balance gains in colour order R, G_even, G_odd, B. */
    val wbGains: FloatArray,
    /** Row-major 3×3: white-balanced camera RGB → linear sRGB/Rec.709. */
    val ccm: DoubleArray,
    /** Black level per 2×2 position, row-major ((0,0), (1,0), (0,1), (1,1)). */
    val blackLevel: FloatArray,
    val whiteLevel: Float,
    /** Lens shading gains, 4 per cell (R, G_even, G_odd, B), row-major; null if not reported. */
    val shading: FloatArray?,
    val shadingCols: Int,
    val shadingRows: Int,
    val iso: Int,
    val exposureNs: Long,
    /** Where the lens is focused, in diopters (0 = infinity). */
    val focusDiopters: Float,
    /** CaptureResult.CONTROL_AF_STATE (0 = inactive). */
    val afState: Int,
    /** The lens reports it's still travelling to the requested focus position. */
    val lensMoving: Boolean,
)

/**
 * Opens the main back camera's 16-bit RAW stream with the ISP's auto exposure / white balance
 * still running, so we get its 3A decisions as metadata while doing all image processing ourselves.
 *
 * Everything that touches the device or session runs on [handler]'s thread, including close and
 * release, so a lens switch can't pull the session out from under a request being built.
 */
class RawCamera(
    private val context: Context,
    private val handler: Handler,
    /** Which lens to stream; null = first back camera with RAW. */
    private val lens: Lens?,
    /**
     * Session layout, most complete first. The activity steps down when a lens fails before its
     * first frame (some HALs reject or fall over on a combination others take):
     * 0 = RAW + small metering stream, video template; 1 = RAW + 640 px metering stream, preview
     * template; 2 = RAW only (no ISP brightness for the auto gain, and lenses whose 3A needs
     * processed output report placeholder exposure; never used on zoom routes, which need it).
     */
    val variant: Int = 0,
    /** Called on the camera thread when the camera fails or disconnects; the device is closed by then. */
    private val onFailure: (String) -> Unit,
) {
    val info: SensorInfo
    val reader: ImageReader
    private val metering: ImageReader?

    /** Most conservative [variant] this lens can use. */
    val lastVariant get() = if (zoomRouted || lens?.logicalStream == true) 1 else 2

    /** Capture results in the current session, and when the current open was requested (elapsedRealtime). */
    @Volatile var resultsSeen = 0
        private set
    @Volatile var openRequestedMs = 0L
        private set

    @Volatile var latestMeta: FrameMeta? = null
        private set

    /**
     * Log-average linear brightness of the ISP's own rendering of the scene (from the small
     * processed stream), or NaN until the first frame. The renderer matches our brightness to it.
     */
    @Volatile var ispLinearLogAverage: Float = Float.NaN
        private set

    private val characteristics: CameraCharacteristics
    private val staticBlack: FloatArray
    private val staticWhite: Float
    // Camera thread only.
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    /** An open is in flight or done; makes repeated open() calls harmless. */
    private var opened = false
    /** Bumped by every open and close: callbacks from an older open are ignored. */
    private var generation = 0
    private var capturesFailed = 0

    init {
        val cm = context.getSystemService(CameraManager::class.java)
        val (id, chars) = if (lens != null) {
            lens.openId to cm.getCameraCharacteristics(lens.sensorId)
        } else {
            cm.cameraIdList.asSequence()
                .map { it to cm.getCameraCharacteristics(it) }
                .firstOrNull { (_, c) ->
                    c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK &&
                        c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
                } ?: error("No back camera with RAW support")
        }

        characteristics = chars
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        // RAW_SENSOR keeps the sensor's full bit depth (14-bit on the Xiaomi 14 main camera).
        val size = map.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.minByOrNull { abs(it.width - OPEN_GATE_W) + abs(it.height - OPEN_GATE_H) }
            ?: error("Camera $id has no RAW_SENSOR stream")

        val redOffset = when (chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> 1 to 0
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> 0 to 1
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> 1 to 1
            else -> 0 to 0
        }
        info = SensorInfo(
            cameraId = id,
            size = size,
            redOffset = redOffset,
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            front = chars.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT,
            timestampsAreBoottime = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
            evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
            evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 1f,
            isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200),
            exposureRangeNs = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 100_000_000L),
            activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, size.width, size.height),
            minFocusDiopters = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
            maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
        )

        val pattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        staticBlack = FloatArray(4) { i -> pattern?.getOffsetForIndex(i % 2, i / 2)?.toFloat() ?: 0f }
        staticWhite = (chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()

        reader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 4)

        // A small processed stream nobody looks at: on some lenses (Xiaomi's front and hidden
        // telephoto) the ISP only runs auto exposure / white balance while it has processed
        // output to produce, and with RAW alone it reports placeholder values.
        val aspect = size.width.toFloat() / size.height
        val minWidth = if (variant == 0) 320 else 640
        metering = if (variant.coerceAtMost(lastVariant) >= 2) null else {
            val yuvSize = map.getOutputSizes(ImageFormat.YUV_420_888)
                .filter { it.width >= minWidth && abs(it.width.toFloat() / it.height - aspect) < 0.02f }
                .minByOrNull { it.width * it.height } ?: Size(640, 480)
            ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ r ->
                    r.acquireLatestImage()?.use { ispLinearLogAverage = yLogAverage(it) }
                }, handler)
            }
        }
    }

    @SuppressLint("MissingPermission") // CameraActivity checks CAMERA before opening
    fun open(settings: CaptureSettings) {
        this.settings = settings
        // Set here too, so the watchdog never compares against an older open while this one queues.
        openRequestedMs = SystemClock.elapsedRealtime()
        handler.post { if (!opened) openNow() }
    }

    private val name get() = "lens ${lens?.key ?: info.cameraId}${lens?.zoomRatio?.takeIf { it != 1f }?.let { " @%.2fx".format(it) } ?: ""}"

    private fun sinceOpenMs() = SystemClock.elapsedRealtime() - openRequestedMs

    @SuppressLint("MissingPermission")
    private fun openNow() {
        val gen = ++generation
        opened = true
        resultsSeen = 0
        capturesFailed = 0
        openRequestedMs = SystemClock.elapsedRealtime()
        EventLog.log("Opening $name, session layout $variant")
        try {
            context.getSystemService(CameraManager::class.java).openCamera(info.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (gen != generation) { d.close(); return }
                    device = d
                    EventLog.log("Camera ${info.cameraId} opened after ${sinceOpenMs()} ms")
                    if (debugFailOpens > 0) {
                        debugFailOpens--
                        fail("simulated failure before the first frame")
                        return
                    }
                    createSession(d)
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    if (gen == generation) fail("camera disconnected (another app took it, or the camera service restarted)")
                }

                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    if (gen == generation) fail("camera error ${errorName(error)}")
                }
            }, handler)
        } catch (e: Exception) {
            fail("can't open camera ${info.cameraId}: ${e.message}", e)
        }
    }

    /** Closes everything and tells the activity, which decides whether to retry. Camera thread. */
    private fun fail(reason: String, e: Throwable? = null) {
        EventLog.log("$name failed ${sinceOpenMs()} ms after opening ($resultsSeen results, layout $variant): $reason", e)
        closeNow()
        onFailure(reason)
    }

    private fun errorName(code: Int) = when (code) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "IN_USE"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "DISABLED"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "DEVICE"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "SERVICE"
        else -> "$code"
    }

    private fun createSession(d: CameraDevice) {
        val gen = generation
        // With zoom routing, the metering stream stays on the logical camera: that's where its
        // 3A (and the active lens's native AF) runs, and HALs want a logical stream anyway.
        val outputs = listOfNotNull(
            output(reader.surface),
            metering?.let { if (zoomRouted || lens?.logicalStream == true) OutputConfiguration(it.surface) else output(it.surface) },
        )
        val layout = "RAW ${info.size.width}x${info.size.height}${lens?.physicalId?.let { " (physical $it)" } ?: ""}" +
            (metering?.let { " + YUV ${it.width}x${it.height}" } ?: " only")
        // Some lens routes are rejected by the HAL with an exception rather than onConfigureFailed.
        try {
            d.createCaptureSession(SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                { handler.post(it) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (gen != generation) { s.close(); return }
                        session = s
                        EventLog.log("Session configured after ${sinceOpenMs()} ms: $layout")
                        applySettings(triggerAf = false)
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (gen == generation) fail("session rejected: $layout")
                    }
                },
            ))
        } catch (e: Exception) {
            fail("session setup failed ($layout): ${e.message}", e)
        }
    }

    /** Applies [settings] to the running stream (or remembers them until the session is up). */
    fun update(settings: CaptureSettings) {
        this.settings = settings
        handler.post { applySettings(triggerAf = false) }
    }

    /** Focuses once at [CaptureSettings.focusPoint] (TAP) and holds; call again to refocus there. */
    fun tapToFocus(settings: CaptureSettings) {
        this.settings = settings
        handler.post { applySettings(triggerAf = true) }
    }

    private fun applySettings(triggerAf: Boolean) {
        val d = device ?: return
        val s = session ?: return
        val st = settings
        try {
            val builder = buildRequest(d, listOfNotNull(reader.surface, metering?.surface))
            s.setRepeatingRequest(builder.build(), captureCallback, handler)
            if (triggerAf && st.af == AfMode.TAP && info.minFocusDiopters > 0f && !zoomRouted) {
                // One-shot scan; AF_MODE_AUTO then holds focus until the next trigger.
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                s.capture(builder.build(), captureCallback, handler)
            }
        } catch (e: Exception) {
            // A session that just closed or errored; its own callback reports the failure.
            Log.w(TAG, "$name: request not applied: ${e.message}")
        }
    }

    /** Log-average of the Y plane on a coarse grid, decoded to linear (sRGB-ish transfer). */
    private fun yLogAverage(img: android.media.Image): Float {
        val plane = img.planes[0]
        val buf = plane.buffer
        var sum = 0.0
        var n = 0
        for (gy in 1 until 24) for (gx in 1 until 32) {
            val x = img.width * gx / 32
            val y = img.height * gy / 24
            val v = (buf.get(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xFF) / 255.0
            val lin = Math.pow((v + 0.055) / 1.055, 2.4)
            sum += Math.log(lin + 1e-4)
            n++
        }
        return Math.exp(sum / n).toFloat()
    }

    /** Output targeting our lens (routed to the physical sub-camera when there is one). */
    private fun output(surface: Surface) = OutputConfiguration(surface).apply { lens?.physicalId?.let { setPhysicalCameraId(it) } }

    /** Repeating-request settings (exposure, focus, white balance) for [targets]. */
    private fun buildRequest(d: CameraDevice, targets: List<Surface>): CaptureRequest.Builder {
        val st = settings
        val frameNs = 1_000_000_000L / st.fps
        val region = st.focusPoint?.let { meteringRegion(it) }
        return d.createCaptureRequest(if (variant == 0) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW).apply {
            targets.forEach { addTarget(it) }
            set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_LOCK, st.awbLock)
            // Logical multi-cameras pick their active lens (and run that lens's native AF) from the zoom ratio.
            lens?.zoomRatio?.takeIf { it != 1f }?.let { set(CaptureRequest.CONTROL_ZOOM_RATIO, it) }
            when (st.ae) {
                AeMode.AUTO, AeMode.LOCKED -> {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(st.fps, st.fps))
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, st.evSteps.coerceIn(info.evRange.lower, info.evRange.upper))
                    set(CaptureRequest.CONTROL_AE_LOCK, st.ae == AeMode.LOCKED)
                }
                AeMode.MANUAL, AeMode.PRIORITY -> {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, st.iso.coerceIn(info.isoRange.lower, info.isoRange.upper))
                    // Exposure can't be longer than the frame.
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, st.exposureNs.coerceIn(info.exposureRangeNs.lower, frameNs))
                }
            }
            when {
                // Fixed-focus lenses (ultrawide, front) only accept AF off.
                info.minFocusDiopters <= 0f -> set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                // Continuous AF follows the tapped spot, if any; AUTO focuses there once and holds (a lock).
                st.af == AfMode.CONTINUOUS || st.af == AfMode.TAP -> {
                    set(CaptureRequest.CONTROL_AF_MODE,
                        if (st.af == AfMode.TAP) CaptureRequest.CONTROL_AF_MODE_AUTO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    if (region != null && info.maxAfRegions > 0) set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                }
                else -> {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, st.focusDiopters.coerceIn(0f, info.minFocusDiopters))
                }
            }
            // A tapped spot also weights auto exposure, unless exposure is ours (manual / priority).
            if (st.ae in listOf(AeMode.AUTO, AeMode.LOCKED) && region != null && info.maxAeRegions > 0) {
                set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            }
        }
    }

    /** True when the lens is reached through a logical camera's zoom (its native 3A/AF runs there). */
    val zoomRouted get() = lens?.zoomRatio?.let { it != 1f } == true

    /** Active array of the camera we open; for zoom routes that's the logical camera's. */
    private val requestArray: Rect by lazy {
        if (zoomRouted) context.getSystemService(CameraManager::class.java).getCameraCharacteristics(lens!!.openId)
            .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: info.activeArray
        else info.activeArray
    }

    /**
     * ~10% of the frame around [p] (0..1 in our image), in the opened camera's active-array
     * coordinates. With CONTROL_ZOOM_RATIO those coordinates already describe the zoomed view
     * (the full array = what's on screen), so no extra zoom mapping is needed.
     */
    private fun meteringRegion(p: PointF): MeteringRectangle {
        val a = requestArray
        val half = a.width() / 20
        val cx = (a.left + p.x * a.width()).toInt()
        val cy = (a.top + p.y * a.height()).toInt()
        val x = (cx - half).coerceIn(a.left, a.right - 2 * half)
        val y = (cy - half).coerceIn(a.top, a.bottom - 2 * half)
        return MeteringRectangle(x, y, 2 * half, 2 * half, MeteringRectangle.METERING_WEIGHT_MAX)
    }

    @Volatile private var settings = CaptureSettings()

    /**
     * Debug/calibration: briefly swaps the session for RAW + JPEG, lets 3A settle, then captures
     * one frame of both at the same instant. Writes into [dir]: raw.bin (tightly packed 16-bit
     * Bayer), meta.json (everything needed to process it the way our pipeline does, plus the
     * sensor's calibration matrices) and isp.jpg (the phone's own processing of that frame).
     * The preview pauses for a few seconds, then the normal session comes back.
     */
    fun referenceCapture(dir: File, onDone: (String) -> Unit) = handler.post {
        val d = device ?: return@post onDone("camera not open")
        dir.mkdirs()
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val aspect = info.size.width.toFloat() / info.size.height
        val jpegSize = map.getOutputSizes(ImageFormat.JPEG)
            .filter { abs(it.width.toFloat() / it.height - aspect) < 0.01f }
            .maxBy { it.width * it.height }
        val raw = ImageReader.newInstance(info.size.width, info.size.height, ImageFormat.RAW_SENSOR, 3)
        val jpeg = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
        var capturing = false
        var pending = 2
        fun done(msg: String) {
            onDone(msg)
            raw.close()
            jpeg.close()
            session?.close()
            session = null
            createSession(d)
        }
        raw.setOnImageAvailableListener({ r ->
            val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
            if (capturing && File(dir, "raw.bin").length() == 0L) {
                val plane = img.planes[0]
                val rowBytes = img.width * 2
                val row = ByteArray(rowBytes)
                File(dir, "raw.bin").outputStream().buffered().use { out ->
                    for (y in 0 until img.height) {
                        plane.buffer.position(y * plane.rowStride)
                        plane.buffer.get(row)
                        out.write(row)
                    }
                }
                if (--pending == 0) done("reference saved to $dir")
            }
            img.close()
        }, handler)
        jpeg.setOnImageAvailableListener({ r ->
            val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
            val buf = img.planes[0].buffer
            File(dir, "isp.jpg").outputStream().use { out -> ByteArray(buf.remaining()).also { buf.get(it); out.write(it) } }
            img.close()
            if (--pending == 0) done("reference saved to $dir")
        }, handler)

        File(dir, "raw.bin").delete()
        session?.close()
        session = null
        d.createCaptureSession(SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(output(raw.surface), output(jpeg.surface)),
            { handler.post(it) },
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val preview = buildRequest(d, listOf(raw.surface))
                    s.setRepeatingRequest(preview.build(), captureCallback, handler)
                    // Let auto exposure / white balance settle in the new session, then stop the
                    // stream and let in-flight frames drain so the only RAW frame saved is the still.
                    handler.postDelayed({ s.stopRepeating() }, 2500)
                    handler.postDelayed({
                        val still = buildRequest(d, listOf(raw.surface, jpeg.surface)).apply {
                            set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                        }
                        capturing = true
                        s.capture(still.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(cs: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                File(dir, "meta.json").writeText(referenceMeta(result, jpegSize).toString(2))
                            }
                        }, handler)
                    }, 3000)
                }

                override fun onConfigureFailed(s: CameraCaptureSession) = done("RAW+JPEG session rejected")
            },
        ))
    }

    private fun referenceMeta(result: CaptureResult, jpegSize: Size): JSONObject {
        val m = toMeta(result)
        fun arr(f: FloatArray) = JSONArray().apply { f.forEach { put(it.toDouble()) } }
        fun mat(t: android.hardware.camera2.params.ColorSpaceTransform?) =
            t?.let { JSONArray().apply { for (i in 0 until 9) put(it.getElement(i % 3, i / 3).toDouble()) } }
        val c = characteristics
        return JSONObject().apply {
            put("width", info.size.width)
            put("height", info.size.height)
            put("redOffset", JSONArray(listOf(info.redOffset.first, info.redOffset.second)))
            put("black", arr(m.blackLevel))
            put("white", m.whiteLevel.toDouble())
            put("wbGains", arr(m.wbGains))
            put("ccm", JSONArray().apply { m.ccm.forEach { put(it) } })
            put("iso", m.iso)
            put("exposureNs", m.exposureNs)
            m.shading?.let {
                put("shadingCols", m.shadingCols)
                put("shadingRows", m.shadingRows)
                put("shading", arr(it))
            }
            result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { n -> put("neutral", JSONArray(n.map { it.toDouble() })) }
            put("colorMatrix1", mat(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)))
            put("colorMatrix2", mat(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)))
            put("forwardMatrix1", mat(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)))
            put("forwardMatrix2", mat(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)))
            put("calibration1", mat(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)))
            put("calibration2", mat(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)))
            put("illuminant1", c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1))
            put("illuminant2", c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt())
            put("jpegWidth", jpegSize.width)
            put("jpegHeight", jpegSize.height)
        }
    }

    /** Debug: behaves as if the HAL had reported a device error. */
    fun simulateFailure() = handler.post { if (opened) fail("simulated failure") }

    /** Closes the device (asynchronously, on the camera thread); [open] can follow at once. */
    fun close() = handler.post { closeNow() }

    /** Closes the device and the readers; the camera object can't be used after this. */
    fun release() = handler.post {
        closeNow()
        reader.close()
        metering?.close()
    }

    private fun closeNow() {
        generation++
        opened = false
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
    }

    private var afLogged = 0L
    /** Debug: log AF state on every frame. */
    @Volatile var afVerbose = false

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
            // Single failed frames happen; only a run of them is worth a line in the event log.
            capturesFailed++
            if (capturesFailed == 1 || capturesFailed == 10 || capturesFailed == 100) {
                EventLog.log("$name: capture failed (reason ${failure.reason}, $capturesFailed so far, $resultsSeen ok)")
            }
        }

        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (++resultsSeen == 1) {
                EventLog.log("$name: first frame ${sinceOpenMs()} ms after opening" +
                    (result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)?.let { ", active lens $it" } ?: ""))
            }
            val now = SystemClock.elapsedRealtime()
            if (now - afLogged > (if (afVerbose) 0 else 2000)) {
                afLogged = now
                Log.d(TAG, "AF lens=${lens?.key ?: info.cameraId} mode=${result.get(CaptureResult.CONTROL_AF_MODE)} " +
                    "state=${result.get(CaptureResult.CONTROL_AF_STATE)} lensState=${result.get(CaptureResult.LENS_STATE)} " +
                    "focus=${result.get(CaptureResult.LENS_FOCUS_DISTANCE)} active=${result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)}")
            }
            // A physical lens's own white balance, matrix and shading arrive in its physical result.
            val physical = lens?.physicalId?.let { result.physicalCameraTotalResults[it] }
            // Odd metadata from an unusual lens must never take the app down; keep the last good frame's.
            runCatching { toMeta(physical ?: result) }
                .onSuccess { latestMeta = it }
                .onFailure { Log.w(TAG, "bad metadata from lens ${lens?.key}", it) }
        }
    }

    private fun toMeta(result: CaptureResult): FrameMeta {
        // Not every lens reports the ISP's colour decisions (Xiaomi's hidden/front lenses may not),
        // so fall back step by step: ISP gains → scene neutral → daylight; ISP matrix → sensor calibration.
        val neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.map { it.toDouble() }?.toDoubleArray()
        val ispGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            ?.let { floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue) }
            ?.takeIf { g -> g.any { it != 1f } } // all-ones means "not actually provided"
        val sceneNeutral = neutral ?: calibration?.daylightNeutral()
        val gains = ispGains ?: sceneNeutral?.let { n ->
            floatArrayOf((n[1] / n[0]).toFloat(), 1f, 1f, (n[1] / n[2]).toFloat())
        } ?: floatArrayOf(1f, 1f, 1f, 1f)
        val ispCcm = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
            ?.let { t -> DoubleArray(9) { i -> t.getElement(i % 3, i / 3).toDouble() } }
            ?.takeIf { m -> m.indices.any { abs(m[it] - IDENTITY[it]) > 1e-3 } }
        val ccm = ispCcm
            ?: sceneNeutral?.let { n -> runCatching { calibration?.camToRec709(n) }.getOrNull() }
            ?: IDENTITY
        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL) ?: staticBlack
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)?.toFloat() ?: staticWhite

        val shadingMap = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        val shading = shadingMap?.let { m ->
            FloatArray(m.gainFactorCount).also { m.copyGainFactors(it, 0) }
        }
        if (!loggedSources) {
            loggedSources = true
            Log.i(TAG, "Lens ${lens?.key ?: info.cameraId} colour sources: " +
                "white balance=${if (ispGains != null) "ISP" else if (neutral != null) "scene neutral" else if (sceneNeutral != null) "daylight default" else "none"}, " +
                "matrix=${if (ispCcm != null) "ISP" else if (sceneNeutral != null && calibration != null) "sensor calibration" else "none"}, " +
                "lens shading=${if (shadingMap != null) "yes" else "no"}")
        }

        return FrameMeta(
            wbGains = gains,
            ccm = ccm,
            blackLevel = black,
            whiteLevel = white,
            shading = shading,
            shadingCols = shadingMap?.columnCount ?: 0,
            shadingRows = shadingMap?.rowCount ?: 0,
            iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
            exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0,
            focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
            afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: 0,
            lensMoving = result.get(CaptureResult.LENS_STATE) == CaptureResult.LENS_STATE_MOVING,
        )
    }

    private var loggedSources = false
    private val calibration = ColorCalibration.from(characteristics)

    companion object {
        private const val TAG = "AuthRec"
        /** Debug: this many upcoming opens fail right after opening (exercises the retry ladder). */
        @Volatile var debugFailOpens = 0
        const val OPEN_GATE_W = 4096
        const val OPEN_GATE_H = 3072
        private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    }
}
