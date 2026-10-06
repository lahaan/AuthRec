package com.authrec.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import com.authrec.EventLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
    /** The HAL only streams this physical lens's RAW with another stream on the logical camera. */
    val logicalStream: Boolean = false,
) {
    /** The id whose characteristics describe this sensor. */
    val sensorId get() = physicalId ?: openId
    val key get() = if (physicalId != null) "$openId/$physicalId" else openId

    fun toJson() = JSONObject().put("openId", openId).put("physicalId", physicalId ?: JSONObject.NULL)
        .put("front", front).put("equiv", equivFocalMm.toDouble()).put("w", rawWidth).put("h", rawHeight).put("label", label)
        .put("zoom", zoomRatio.toDouble()).put("logicalStream", logicalStream)

    companion object {
        fun fromJson(j: JSONObject) = Lens(
            j.getString("openId"), j.optString("physicalId").takeIf { !j.isNull("physicalId") && it.isNotEmpty() },
            j.getBoolean("front"), j.getDouble("equiv").toFloat(), j.getInt("w"), j.getInt("h"), j.getString("label"),
            j.optDouble("zoom", 1.0).toFloat(),
            j.optBoolean("logicalStream", false),
        )
    }
}

/**
 * Finds every lens that really streams RAW to us. Phones (Xiaomi especially) hide lenses from
 * cameraIdList or expose them only as physical sub-cameras, and some advertised ones don't
 * actually deliver frames, so each candidate is opened briefly and its frames checked: do they
 * arrive, and do they contain an image rather than a frozen constant?
 *
 * Routes, tried in this order (safest first; later ones are skipped once a sensor works):
 * 1. listed camera ids; 2. hidden ids opened directly; 3. zoom routes: the logical camera with
 * CONTROL_ZOOM_RATIO set so it switches to the lens, its RAW taken from the physical stream (how
 * the phone's own app reaches them; on the Xiaomi 14 telephoto this also gets native AF);
 * 4. physical streams without zoom, only where no zoom route exists.
 *
 * Probing can take the camera HAL down (the Xiaomi 15 Ultra's did, and every later test then
 * failed with "unknown device"), so every test waits for the camera service to report the cameras
 * first; a route that leaves it down isn't retried, one that failed for a passing reason (busy,
 * disconnected) is, once. Listed cameras are never dropped because of a failed test: losing the
 * main camera to a bad moment would leave the app useless.
 *
 * Results are cached per firmware. A route that takes the app itself down twice is skipped.
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

    private enum class Kind { LISTED, HIDDEN, ZOOM_NATIVE_AF, ZOOM_FALLBACK, PHYSICAL }

    private class Candidate(val lens: Lens, val kind: Kind)

    /** [transient]: the camera service lost, refused or was still busy with the device, so a retry may work. */
    private class Outcome(val ok: Boolean, val message: String, val zoom: Float = 1f, val transient: Boolean = false)

    /**
     * Blocking; call off the main thread. [progress] gets human-readable status lines. Returns null
     * and saves nothing if [keepGoing] turns false mid-scan: cameras can't be opened from the
     * background, so a scan the user walked away from would record every lens as broken.
     */
    fun scan(progress: (String) -> Unit, keepGoing: () -> Boolean): List<Lens>? {
        val t0 = SystemClock.elapsedRealtime()
        fun stamp() = "+%.1f s".format((SystemClock.elapsedRealtime() - t0) / 1000.0)
        // A test that never finished probably took the app down; a second strike skips that route.
        val strikes = JSONObject(prefs.getString("strikes", null) ?: "{}")
        prefs.getString("probing", null)?.let {
            strikes.put(it, strikes.optInt(it) + 1)
            prefs.edit().putString("strikes", strikes.toString()).remove("probing").commit()
            EventLog.log("Lens scan: the test of $it never finished last time (strike ${strikes.optInt(it)})")
        }

        val thread = HandlerThread("lens-probe").apply { start() }
        val handler = Handler(thread.looper)
        val availability = Availability(handler)
        try {
            val listed = runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList())
            // A camera we were just using (rescan) may still be closing.
            availability.waitFor(listed, 5000).takeIf { it > 300 }?.let { EventLog.log("Lens scan: waited $it ms for the cameras to be free") }

            val all = candidates(listed)
            val candidates = all.filter { it.kind == Kind.LISTED || strikes.optInt(it.lens.key) < 2 }
            EventLog.log("Lens scan: ${candidates.size} candidates: " + candidates.joinToString { "${it.lens.key}${zoomNote(it.lens)} ${it.kind}" })
            progress("Finding lenses: ${candidates.size} candidates")
            val working = mutableListOf<Lens>()
            val seen = mutableSetOf<String>()
            val workingSensors = mutableSetOf<String>()
            // Every candidate's outcome, kept for Send diagnostics (the scan's log may be gone by then).
            val results = JSONArray()
            all.filter { it !in candidates }.forEach { results.put("${it.lens.key}: skipped (the app died during its test twice)") }

            for ((i, c) in candidates.withIndex()) {
                if (!keepGoing()) {
                    prefs.edit().remove("probing").commit()
                    EventLog.log("Lens scan stopped at ${stamp()}: the app went to the background")
                    return null
                }
                val lens = c.lens
                // The same sensor often shows up several times (as an id, a physical lens, a clone); keep the first that works.
                if (signature(lens) in seen) continue
                if ((c.kind == Kind.ZOOM_FALLBACK || c.kind == Kind.PHYSICAL) && sensorKey(lens) in workingSensors) continue
                progress("Testing lens ${i + 1}/${candidates.size} (${lens.key})")
                val ids = (listed + lens.openId).distinct()
                // Every test starts on a healthy camera service (a previous one may have taken it down).
                availability.waitFor(ids, 10_000).takeIf { it !in 0..300 }?.let {
                    EventLog.log("Lens scan: camera service ${if (it < 0) "still not ready after 10 s" else "ready after $it ms"} before testing ${lens.key}")
                }
                prefs.edit().putString("probing", lens.key).commit()
                val logicalStream = c.kind == Kind.PHYSICAL
                var outcome = test(lens, handler, logicalStream)
                if (!outcome.ok) {
                    val waited = availability.waitFor(ids, 10_000)
                    if (waited !in 0..500) {
                        // The cameras vanished after this test: the route itself took the HAL down, and
                        // trying it again would only do that again.
                        outcome = Outcome(false, "${outcome.message} (camera service restarted after this test, " +
                            (if (waited < 0) "not back after 10 s" else "back after $waited ms") + ")")
                    } else if (outcome.transient) {
                        // Busy or briefly unavailable: worth one more go.
                        EventLog.log("Lens ${lens.key} failed (${outcome.message}); retrying")
                        val retry = test(lens, handler, logicalStream)
                        outcome = if (retry.ok) retry else Outcome(false, "${outcome.message}; retry: ${retry.message}")
                        if (!retry.ok) availability.waitFor(ids, 10_000)
                    }
                }
                prefs.edit().remove("probing").commit()
                val line = "${stamp()} ${lens.key} (${"%.0f".format(lens.equivFocalMm)} mm eq, ${lens.rawWidth}x${lens.rawHeight}" +
                    "${zoomNote(lens)}, ${c.kind.name.lowercase()}): ${outcome.message}"
                results.put(line)
                EventLog.log("Lens scan: $line")
                if (outcome.ok) {
                    working += lens.copy(zoomRatio = outcome.zoom, logicalStream = logicalStream)
                    seen += signature(lens)
                    workingSensors += sensorKey(lens)
                }
            }

            // Listed cameras are what the phone officially offers: if one failed only because of a bad
            // moment, losing it would leave e.g. just the front camera until the next rescan.
            for (front in listOf(false, true)) {
                if (working.any { it.front == front }) continue
                val keep = all.firstOrNull { it.kind == Kind.LISTED && it.lens.front == front }?.lens ?: continue
                working += keep
                results.put("${keep.key}: kept although its test failed (listed camera)")
                EventLog.log("Lens scan: keeping listed camera ${keep.key} although its test failed")
            }
            label(working)
            results.put("scan took ${stamp()}")
            EventLog.log("Lens scan done in ${stamp()}: " + working.joinToString { "${it.label} (${it.key}${zoomNote(it)})" })
            prefs.edit()
                .putString("fingerprint", Build.FINGERPRINT)
                .putInt("version", VERSION)
                .putString("probeResults", results.toString(1))
                .putString("lenses", JSONArray(working.map { it.toJson() }).toString())
                .apply()
            return working
        } finally {
            availability.close()
            thread.quitSafely()
        }
    }

    private fun zoomNote(l: Lens) = if (l.zoomRatio != 1f) " @%.2fx".format(l.zoomRatio) else ""

    /** Every id that answers, plus physical sub-lenses of logical cameras, that offers RAW; in test order. */
    private fun candidates(listed: List<String>): List<Candidate> {
        val ids = (listed + (0..31).map { it.toString() }).distinct()
        val direct = mutableListOf<Candidate>()
        val routes = mutableListOf<Candidate>()
        for (id in ids) {
            val c = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val logical = describe(id, null, c)
            logical?.let { direct += Candidate(it, if (id in listed) Kind.LISTED else Kind.HIDDEN) }
            if (logical == null) continue
            val zoomRange = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            for (pid in c.physicalCameraIds) {
                val pc = runCatching { cm.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                val phys = describe(id, pid, pc) ?: continue
                val zoom = phys.equivFocalMm / logical.equivFocalMm
                // The logical camera's own main sensor: opening the logical camera already is that route.
                if (abs(zoom - 1f) < 0.15f) continue
                if (zoomRange != null && zoom >= zoomRange.lower * 0.95f && zoom <= zoomRange.upper) {
                    val hasAf = (pc.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f
                    // Telephotos with AF get the phone's own (fast, phase-detect + laser) AF this way, so
                    // they're worth having next to a direct route; other zoom routes are only a fallback.
                    val kind = if (hasAf && zoom > 1.2f) Kind.ZOOM_NATIVE_AF else Kind.ZOOM_FALLBACK
                    routes += Candidate(phys.copy(zoomRatio = zoom.coerceAtLeast(zoomRange.lower)), kind)
                } else {
                    routes += Candidate(phys, Kind.PHYSICAL)
                }
            }
        }
        // One route of each kind per physical lens (listed logical cameras come first, so they win).
        val zoomRoutes = routes.filter { it.kind != Kind.PHYSICAL }.distinctBy { it.lens.physicalId }
        val physicalRoutes = routes.filter { it.kind == Kind.PHYSICAL && zoomRoutes.none { z -> z.lens.physicalId == it.lens.physicalId } }
            .distinctBy { it.lens.physicalId }
        return direct.sortedBy { if (it.kind == Kind.LISTED) 0 else 1 } + zoomRoutes.sortedBy { it.lens.physicalId } + physicalRoutes
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
    private fun signature(l: Lens) = "${sensorKey(l)}/${l.zoomRatio != 1f}"

    /** Identifies the sensor regardless of route. */
    private fun sensorKey(l: Lens) = "${l.front}/${"%.0f".format(l.equivFocalMm)}/${l.rawWidth}x${l.rawHeight}"

    /**
     * Opens the lens, streams RAW for up to ~2.5 s and checks the frames. On a zoom route whose
     * RAW stays silent while the logical camera reports another lens active, the zoom is stepped
     * up a little (the switch-over point isn't always the focal-length ratio).
     */
    @SuppressLint("MissingPermission")
    private fun test(lens: Lens, handler: Handler, logicalStream: Boolean): Outcome {
        val c = try {
            cm.getCameraCharacteristics(lens.sensorId)
        } catch (e: Exception) {
            return Outcome(false, "error: ${e.message}", transient = isTransient(e))
        }
        val black = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.getOffsetForIndex(0, 0) ?: 0
        val white = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val deviceRef = AtomicReference<CameraDevice?>(null)
        val openError = AtomicReference<Outcome?>(null)
        val abandoned = AtomicBoolean(false)
        try {
            cm.openCamera(lens.openId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (abandoned.get()) { d.close(); return }
                    deviceRef.set(d)
                    opened.countDown()
                }
                override fun onDisconnected(d: CameraDevice) {
                    openError.compareAndSet(null, Outcome(false, "disconnected", transient = true))
                    d.close()
                    opened.countDown()
                }
                override fun onError(d: CameraDevice, e: Int) {
                    openError.compareAndSet(null, Outcome(false, "camera error $e", transient = e != ERROR_CAMERA_DISABLED))
                    d.close()
                    opened.countDown()
                }
                override fun onClosed(d: CameraDevice) = closed.countDown()
            }, handler)
        } catch (e: Exception) {
            return Outcome(false, "error: ${e.message}", transient = isTransient(e))
        }
        if (!opened.await(4, TimeUnit.SECONDS)) {
            abandoned.set(true)
            return Outcome(false, "open timed out", transient = true)
        }
        val d = deviceRef.get() ?: return openError.get() ?: Outcome(false, "open failed", transient = true)

        val reader = ImageReader.newInstance(lens.rawWidth, lens.rawHeight, ImageFormat.RAW_SENSOR, 3)
        // Physical RAW needs a stream on the logical camera too (a RAW-only physical session took
        // the 15 Ultra's camera HAL down).
        val logicalYuv = if (lens.zoomRatio != 1f || logicalStream) ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
        } else null
        val frames = AtomicInteger(0)
        val firstFrameMs = AtomicLong(0L)
        val stats = AtomicReference<Pair<Double, Double>?>(null)
        val activeId = AtomicReference<String?>(null)
        val t0 = SystemClock.elapsedRealtime()
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val n = frames.incrementAndGet()
            if (n == 1) firstFrameMs.set(SystemClock.elapsedRealtime() - t0)
            // Sample a coarse grid of pixels once auto exposure has had a moment.
            if (n >= 8) stats.set(sample(img, black, white))
            img.close()
        }, handler)

        var zoom = lens.zoomRatio
        var session: CameraCaptureSession? = null
        val outcome = try {
            val configured = CountDownLatch(1)
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
            if (!configured.await(4, TimeUnit.SECONDS)) {
                Outcome(false, "session timed out", transient = true)
            } else {
                val s = session
                if (s == null) {
                    Outcome(false, "RAW session rejected")
                } else {
                    val callback = object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(cs: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                            result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)?.let { activeId.set(it) }
                        }
                    }
                    fun stream() = s.setRepeatingRequest(d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(reader.surface)
                        logicalYuv?.let { addTarget(it.surface) }
                        if (zoom != 1f) set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                    }.build(), callback, handler)
                    stream()
                    val maxZoom = cm.getCameraCharacteristics(lens.openId).get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: zoom
                    var bumps = 0
                    var deadline = SystemClock.elapsedRealtime() + 2500
                    while (SystemClock.elapsedRealtime() < deadline && stats.get() == null) {
                        SystemClock.sleep(50)
                        val active = activeId.get()
                        if (frames.get() == 0 && zoom > 1f && lens.physicalId != null && active != null && active != lens.physicalId &&
                            SystemClock.elapsedRealtime() - t0 > 1200L * (bumps + 1) && bumps < 3 && zoom < maxZoom) {
                            zoom = (zoom * 1.08f).coerceAtMost(maxZoom)
                            bumps++
                            EventLog.log("Lens ${lens.key}: lens $active still active, trying zoom %.2f".format(zoom))
                            stream()
                            deadline = SystemClock.elapsedRealtime() + 2500
                        }
                    }
                    runCatching { s.stopRepeating() }
                    val note = activeId.get()?.let { ", active lens $it" } ?: ""
                    if (frames.get() == 0) {
                        Outcome(false, "no frames$note")
                    } else {
                        val st = stats.get()
                        when {
                            st == null -> Outcome(false, "only ${frames.get()} frames in 2.5 s$note")
                            // A broken route delivers constant buffers (zero or frozen): no variation at all.
                            // A dark scene still has sensor noise, so judge by variation only, never by
                            // brightness (a dim room at night once made a working telephoto look "black").
                            st.second < 0.0003 -> Outcome(false, "flat frames (mean %.5f, std %.5f)".format(st.first, st.second))
                            else -> Outcome(true, "ok (${frames.get()} frames, first after ${firstFrameMs.get()} ms" +
                                (if (zoom != lens.zoomRatio) ", zoom %.2f".format(zoom) else "") + "$note)", zoom = zoom)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Outcome(false, "error: ${e.message}", transient = isTransient(e))
        } finally {
            runCatching { session?.close() }
            runCatching { d.close() }
            // The next test (or the real session) opens a camera right away; let this one shut down first.
            closed.await(3, TimeUnit.SECONDS)
            reader.close()
            logicalYuv?.close()
        }
        return outcome
    }

    /** Errors that say more about the camera service's state at that moment than about the lens. */
    private fun isTransient(e: Throwable): Boolean {
        val reason = (e as? CameraAccessException)?.reason
        val msg = e.message.orEmpty()
        return reason == CameraAccessException.CAMERA_DISCONNECTED || reason == CameraAccessException.CAMERA_IN_USE ||
            reason == CameraAccessException.MAX_CAMERAS_IN_USE || "unknown device" in msg || "No such file" in msg
    }

    /**
     * Which cameras the camera service reports busy or gone (listed ids only: Xiaomi's framework
     * drops status updates for hidden ones).
     */
    private inner class Availability(handler: Handler) : CameraManager.AvailabilityCallback() {
        private val unavailable = Collections.synchronizedSet(mutableSetOf<String>())

        init {
            cm.registerAvailabilityCallback(this, handler)
            // The current status of every camera arrives right after registering.
            SystemClock.sleep(150)
        }

        override fun onCameraAvailable(id: String) { unavailable -= id }
        override fun onCameraUnavailable(id: String) { unavailable += id }

        fun close() = cm.unregisterAvailabilityCallback(this)

        /**
         * Waits until every id in [ids] is known to the camera service again (a crashed HAL takes
         * seconds to come back) and none is busy. Returns the time waited, or -1 on timeout.
         */
        fun waitFor(ids: Collection<String>, timeoutMs: Long): Long {
            val start = SystemClock.elapsedRealtime()
            while (true) {
                val waited = SystemClock.elapsedRealtime() - start
                val known = ids.all { runCatching { cm.getCameraCharacteristics(it) }.isSuccess }
                if (known && ids.none { it in unavailable }) return waited
                if (waited > timeoutMs) return -1
                SystemClock.sleep(100)
            }
        }
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
        /** Bump to force a rescan after changing how lenses are found. */
        private const val VERSION = 7
        private const val ERROR_CAMERA_DISABLED = CameraDevice.StateCallback.ERROR_CAMERA_DISABLED

        /** RAW size closest to 4096×3072 (binned open gate). */
        fun rawSize(c: CameraCharacteristics): Size? =
            c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.RAW_SENSOR)
                ?.minByOrNull { abs(it.width - 4096) + abs(it.height - 3072) }
    }
}
