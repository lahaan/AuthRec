package com.authrec.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** A camera we can stream RAW from: opened as [openId], optionally routed to physical lens [physicalId]. */
data class Lens(
    val openId: String,
    val physicalId: String?,
    val front: Boolean,
    /** 35 mm-equivalent focal length. */
    val equivFocalMm: Float,
    val rawWidth: Int,
    val rawHeight: Int,
    var label: String = "",
    /** For a physical lens behind a logical camera: zoom ratio that makes the logical camera switch to it (1 = don't set). */
    val zoomRatio: Float = 1f,
) {
    /** The id whose characteristics describe this sensor. */
    val sensorId get() = physicalId ?: openId
    val key get() = if (physicalId != null) "$openId/$physicalId" else openId

    fun toJson() = JSONObject().put("openId", openId).put("physicalId", physicalId ?: JSONObject.NULL)
        .put("front", front).put("equiv", equivFocalMm.toDouble()).put("w", rawWidth).put("h", rawHeight).put("label", label)
        .put("zoom", zoomRatio.toDouble())

    companion object {
        fun fromJson(j: JSONObject) = Lens(
            j.getString("openId"), j.optString("physicalId").takeIf { !j.isNull("physicalId") && it.isNotEmpty() },
            j.getBoolean("front"), j.getDouble("equiv").toFloat(), j.getInt("w"), j.getInt("h"), j.getString("label"),
            j.optDouble("zoom", 1.0).toFloat(),
        )
    }
}

/**
 * Finds every lens that really streams RAW to us. Phones (Xiaomi especially) hide lenses from
 * cameraIdList or expose them only as physical sub-cameras, and some advertised ones don't
 * actually deliver frames, so each candidate is opened briefly and its frames checked: do they
 * arrive, and do they contain an image rather than black or a frozen constant?
 *
 * Results are cached per firmware. An id that takes the app down mid-test is remembered and
 * skipped from then on.
 */
class LensProbe(private val context: Context) {

    private val cm = context.getSystemService(CameraManager::class.java)
    private val prefs = context.getSharedPreferences("lenses", Context.MODE_PRIVATE)

    fun cached(): List<Lens>? {
        if (prefs.getString("fingerprint", null) != Build.FINGERPRINT || prefs.getInt("version", 0) != VERSION) return null
        val arr = JSONArray(prefs.getString("lenses", null) ?: return null)
        return List(arr.length()) { Lens.fromJson(arr.getJSONObject(it)) }.takeIf { it.isNotEmpty() }
    }

    fun clearCache() = prefs.edit().remove("lenses").remove("fingerprint").apply()

    /** Blocking; call off the main thread. [progress] gets human-readable status lines. */
    fun scan(progress: (String) -> Unit): List<Lens> {
        // A test that never finished means it killed the app last time: don't try it again.
        val crashed = prefs.getStringSet("crashed", emptySet())!!.toMutableSet()
        prefs.getString("probing", null)?.let {
            crashed += it
            prefs.edit().putStringSet("crashed", crashed).remove("probing").apply()
        }

        val candidates = candidates().filter { it.key !in crashed }
        progress("Finding lenses: ${candidates.size} candidates")
        val thread = HandlerThread("lens-probe").apply { start() }
        val handler = Handler(thread.looper)
        val working = mutableListOf<Lens>()
        val seenSensors = mutableSetOf<String>()
        try {
            candidates.forEachIndexed { i, lens ->
                // The same sensor often appears twice (as a camera id and as a physical lens); keep the first that works.
                val sig = signature(lens)
                if (sig in seenSensors) return@forEachIndexed
                progress("Testing lens ${i + 1}/${candidates.size} (${lens.key})")
                prefs.edit().putString("probing", lens.key).commit()
                val result = runCatching { test(lens, handler) }.getOrElse { "error: ${it.message}" }
                prefs.edit().remove("probing").commit()
                Log.i(TAG, "lens ${lens.key} (${"%.0f".format(lens.equivFocalMm)} mm eq, ${lens.rawWidth}x${lens.rawHeight}): $result")
                if (result == OK) {
                    working += lens
                    seenSensors += sig
                }
            }
        } finally {
            thread.quitSafely()
        }
        label(working)
        prefs.edit()
            .putString("fingerprint", Build.FINGERPRINT)
            .putInt("version", VERSION)
            .putString("lenses", JSONArray(working.map { it.toJson() }).toString())
            .apply()
        return working
    }

    /** Every id that answers, plus physical sub-lenses of logical cameras, that offers RAW. */
    private fun candidates(): List<Lens> {
        val listed = cm.cameraIdList.toList()
        val ids = (listed + (0..31).map { it.toString() }).distinct()
        val out = mutableListOf<Lens>()
        val zoomRoutes = mutableListOf<Lens>()
        for (id in ids) {
            val c = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val logical = describe(id, null, c)
            logical?.let { out += it }
            for (pid in c.physicalCameraIds) {
                val pc = runCatching { cm.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                val phys = describe(id, pid, pc) ?: continue
                out += phys
                // Telephoto-type lenses with AF: also try reaching them through the logical camera's
                // zoom, which makes the phone's own (fast, phase-detect + laser) AF run on them.
                val zoomRange = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                val hasAf = (pc.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f
                if (logical != null && zoomRange != null && hasAf) {
                    val zoom = phys.equivFocalMm / logical.equivFocalMm
                    if (zoom > 1.2f && zoom <= zoomRange.upper) zoomRoutes += phys.copy(zoomRatio = zoom)
                }
            }
        }
        // One zoom route per physical lens is enough.
        out += zoomRoutes.distinctBy { it.physicalId }
        // Prefer opening a sensor directly: a lens reached through a logical camera stops streaming
        // when the logical camera decides to switch sensors (Xiaomi does this in low light).
        return out.sortedBy { if (it.physicalId == null) 0 else 1 }
    }

    private fun describe(openId: String, physicalId: String?, c: CameraCharacteristics): Lens? {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW !in caps) return null
        val size = rawSize(c) ?: return null
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val equiv = focal * 43.27f / hypot(sensor.width, sensor.height)
        val front = c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        return Lens(openId, physicalId, front, equiv, size.width, size.height)
    }

    /** Zoom routes are kept alongside the direct route to the same sensor: they behave differently. */
    private fun signature(l: Lens) = "${l.front}/${"%.0f".format(l.equivFocalMm)}/${l.rawWidth}x${l.rawHeight}/${l.zoomRatio != 1f}"

    /** Opens the lens, streams RAW for up to ~2.5 s and checks the frames. Returns [OK] or why not. */
    @SuppressLint("MissingPermission")
    private fun test(lens: Lens, handler: Handler): String {
        val opened = CountDownLatch(1)
        var device: CameraDevice? = null
        var error: String? = null
        cm.openCamera(lens.openId, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { device = d; opened.countDown() }
            override fun onDisconnected(d: CameraDevice) { error = "disconnected"; d.close(); opened.countDown() }
            override fun onError(d: CameraDevice, e: Int) { error = "open error $e"; d.close(); opened.countDown() }
        }, handler)
        if (!opened.await(3, TimeUnit.SECONDS)) return "open timed out"
        val d = device ?: return error ?: "open failed"

        val c = cm.getCameraCharacteristics(lens.sensorId)
        val black = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.getOffsetForIndex(0, 0) ?: 0
        val white = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        val reader = ImageReader.newInstance(lens.rawWidth, lens.rawHeight, ImageFormat.RAW_SENSOR, 3)
        // Zoom routes: the HAL wants a stream on the logical camera too (RAW-only physical fails).
        val logicalYuv = if (lens.zoomRatio != 1f) ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
        } else null
        var frames = 0 // only touched on the probe handler thread until the test is over
        var firstFrameMs = 0L
        val stats = AtomicReference<Pair<Double, Double>?>(null)
        val t0 = SystemClock.elapsedRealtime()
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            frames++
            if (frames == 1) firstFrameMs = SystemClock.elapsedRealtime() - t0
            // Sample a coarse grid of pixels once auto exposure has had a moment.
            if (frames >= 8) stats.set(sample(img, black, white))
            img.close()
        }, handler)

        try {
            val configured = CountDownLatch(1)
            var session: CameraCaptureSession? = null
            d.createCaptureSession(SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOfNotNull(
                    OutputConfiguration(reader.surface).apply { lens.physicalId?.let { setPhysicalCameraId(it) } },
                    logicalYuv?.let { OutputConfiguration(it.surface) },
                ),
                { handler.post(it) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { session = s; configured.countDown() }
                    override fun onConfigureFailed(s: CameraCaptureSession) { configured.countDown() }
                },
            ))
            if (!configured.await(3, TimeUnit.SECONDS)) return "session timed out"
            val s = session ?: return "RAW session rejected"
            s.setRepeatingRequest(d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                logicalYuv?.let { addTarget(it.surface) }
                if (lens.zoomRatio != 1f) set(android.hardware.camera2.CaptureRequest.CONTROL_ZOOM_RATIO, lens.zoomRatio)
            }.build(), null, handler)
            val deadline = SystemClock.elapsedRealtime() + 2500
            while (SystemClock.elapsedRealtime() < deadline && stats.get() == null) SystemClock.sleep(50)
            runCatching { s.stopRepeating() }
            s.close()
        } finally {
            d.close()
            SystemClock.sleep(150)
            reader.close()
            logicalYuv?.close()
        }

        if (frames == 0) return "no frames"
        val (mean, std) = stats.get() ?: return "only $frames frames in 2.5 s"
        // Real images have texture; a black or frozen/constant buffer doesn't. Kept loose so a
        // dim room still passes: the bar is "pixels light up", not "looks good".
        if (mean < 0.0005) return "black frames (mean %.5f)".format(mean)
        if (std < 0.0003) return "flat frames (std %.5f)".format(std)
        Log.i(TAG, "lens ${lens.key}: $frames frames, first after $firstFrameMs ms, mean %.4f std %.4f".format(mean, std))
        return OK
    }

    private fun sample(img: android.media.Image, black: Int, white: Int): Pair<Double, Double> {
        val plane = img.planes[0]
        val buf = plane.buffer
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (gy in 1 until 48) for (gx in 1 until 64) {
            // Even coordinates: always the same CFA colour, so colour doesn't masquerade as texture.
            val x = (img.width * gx / 64) and 1.inv()
            val y = (img.height * gy / 48) and 1.inv()
            val v = (buf.getShort(y * plane.rowStride + x * 2).toInt() and 0xFFFF)
            val f = (v - black).toDouble() / (white - black)
            sum += f
            sumSq += f * f
            n++
        }
        val mean = sum / n
        return mean to sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
    }

    /** Back lenses as zoom factors relative to the main (≈24 mm eq) lens; front is "Front". */
    private fun label(lenses: List<Lens>) {
        val back = lenses.filter { !it.front }
        val main = back.minByOrNull { abs(it.equivFocalMm - 24f) }?.equivFocalMm ?: 24f
        lenses.forEach { l ->
            l.label = if (l.front) "Front" else "%.1fx".format(l.equivFocalMm / main).replace(".0x", "x")
            // A direct route to a sensor that also has a zoom route is the backup (contrast AF).
            if (l.zoomRatio == 1f && l.physicalId == null && lenses.any { it.zoomRatio != 1f && it.physicalId == l.openId }) {
                l.label += " (backup AF)"
            }
        }
    }

    companion object {
        private const val TAG = "AuthRec"
        private const val OK = "ok"
        /** Bump to force a rescan after changing how lenses are found. */
        private const val VERSION = 4

        /** RAW size closest to 4096×3072 (binned open gate). */
        fun rawSize(c: CameraCharacteristics): Size? =
            c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.RAW_SENSOR)
                ?.minByOrNull { abs(it.width - 4096) + abs(it.height - 3072) }
    }
}
