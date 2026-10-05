package com.authrec.bench

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Opens the chosen RAW stream for real and measures delivered fps, dropped frames and the
 * cost of copying each frame out of the camera buffer (a stand-in for the GPU upload).
 * Phones often advertise more than they deliver, so this is the number that matters.
 */
object RawStreamTest {

    private const val WARMUP_MS = 1000L
    private const val MEASURE_MS = 4000L

    fun run(ctx: Context, r: Report, target: RawTarget?) {
        r.section("RAW stream")
        if (target == null) {
            r.line("  skipped: no RAW target")
            return
        }
        // Test the target rates the stream claims to reach, plus the next one up to confirm the ceiling.
        val claimed = Targets.FPS.filter { it <= target.maxFps + 0.5 }
        val toTest = (claimed + Targets.FPS.firstOrNull { it > target.maxFps + 0.5 }).filterNotNull()
        if (toTest.isEmpty()) {
            r.verdict(Report.Verdict.FAIL, "RAW ${target.size} can't reach 24 fps (advertised %.1f)".format(target.maxFps))
            return
        }

        val thread = HandlerThread("raw-test").apply { start() }
        val handler = Handler(thread.looper)
        try {
            val device = openCamera(ctx, target, handler)
            try {
                for (fps in toTest) measure(r, device, target, fps, handler)
            } finally {
                device.close()
            }
        } finally {
            thread.quitSafely()
        }
    }

    @SuppressLint("MissingPermission") // BenchActivity checks CAMERA before starting
    private fun openCamera(ctx: Context, target: RawTarget, handler: Handler): CameraDevice {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val latch = CountDownLatch(1)
        var device: CameraDevice? = null
        var error: String? = null
        cm.openCamera(target.logicalParentId ?: target.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { device = d; latch.countDown() }
            override fun onDisconnected(d: CameraDevice) { error = "disconnected"; d.close(); latch.countDown() }
            override fun onError(d: CameraDevice, e: Int) { error = "error $e"; d.close(); latch.countDown() }
        }, handler)
        check(latch.await(5, TimeUnit.SECONDS)) { "camera open timed out" }
        return device ?: error("camera open failed: $error")
    }

    private fun measure(r: Report, device: CameraDevice, target: RawTarget, fps: Int, handler: Handler) {
        val w = target.size.width
        val h = target.size.height
        val reader = ImageReader.newInstance(w, h, target.format, 6)

        val timestamps = ArrayList<Long>(600)
        var copyNanos = 0L
        var copied = 0
        var scratch: ByteBuffer? = null
        val measuring = AtomicBoolean(false)

        reader.setOnImageAvailableListener({ rd ->
            val img = rd.acquireNextImage() ?: return@setOnImageAvailableListener
            if (measuring.get()) {
                timestamps += img.timestamp
                val plane = img.planes[0].buffer
                val dst = scratch?.takeIf { it.capacity() >= plane.remaining() }
                    ?: ByteBuffer.allocateDirect(plane.remaining()).also { scratch = it }
                dst.clear()
                val t0 = SystemClock.elapsedRealtimeNanos()
                dst.put(plane)
                copyNanos += SystemClock.elapsedRealtimeNanos() - t0
                copied++
            }
            img.close()
        }, handler)

        val output = OutputConfiguration(reader.surface).apply {
            if (target.logicalParentId != null) setPhysicalCameraId(target.cameraId)
        }
        val sessionLatch = CountDownLatch(1)
        var session: CameraCaptureSession? = null
        device.createCaptureSession(SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, listOf(output), { handler.post(it) },
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; sessionLatch.countDown() }
                override fun onConfigureFailed(s: CameraCaptureSession) { sessionLatch.countDown() }
            },
        ))
        sessionLatch.await(5, TimeUnit.SECONDS)
        val s = session
        if (s == null) {
            r.verdict(Report.Verdict.FAIL, "RAW $w×$h @ $fps: session configuration rejected")
            reader.close()
            return
        }

        val frameNs = 1_000_000_000L / fps
        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(reader.surface)
            if (target.manualSensor) {
                // Manual timing shows the sensor's real ceiling, independent of the vendor's AE fps tables.
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, frameNs / 2) // 180° shutter
                set(CaptureRequest.SENSOR_SENSITIVITY, 400)
            } else {
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
            }
        }.build()

        s.setRepeatingRequest(req, null, handler)
        SystemClock.sleep(WARMUP_MS)
        measuring.set(true)
        SystemClock.sleep(MEASURE_MS)
        measuring.set(false)
        s.stopRepeating()
        s.close()
        SystemClock.sleep(200)
        reader.close()

        report(r, target, fps, timestamps, copyNanos, copied)
    }

    private fun report(r: Report, target: RawTarget, fps: Int, ts: List<Long>, copyNanos: Long, copied: Int) {
        val label = "RAW ${target.size.width}×${target.size.height} @ $fps"
        if (ts.size < 2) {
            r.verdict(Report.Verdict.FAIL, "$label: no frames delivered")
            return
        }
        val expectedNs = 1e9 / fps
        val intervals = ts.zipWithNext { a, b -> b - a }
        val delivered = (ts.size - 1) * 1e9 / (ts.last() - ts.first())
        // An interval ~2× the expected one means one frame was skipped, ~3× two, etc.
        val dropped = intervals.sumOf { ((it / expectedNs).roundToInt() - 1).coerceAtLeast(0) }
        val copyMs = if (copied > 0) copyNanos / copied / 1e6 else 0.0

        r.kv("$label delivered", "%.2f fps, %d dropped of %d, frame copy %.1f ms".format(delivered, dropped, ts.size + dropped, copyMs))
        val ok = delivered >= fps * 0.97 && dropped <= 1
        val close = delivered >= fps * 0.85
        r.verdict(
            when { ok -> Report.Verdict.PASS; close -> Report.Verdict.LIMITED; else -> Report.Verdict.FAIL },
            "$label: %.1f fps delivered, $dropped dropped".format(delivered),
        )
    }
}
