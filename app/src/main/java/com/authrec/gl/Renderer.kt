package com.authrec.gl

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.authrec.camera.FrameMeta
import com.authrec.camera.SensorInfo
import com.authrec.color.ColorMath
import com.authrec.color.CubeLut
import com.authrec.color.LogProfile
import com.authrec.color.Primaries
import com.authrec.record.Recorder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Owns the GL thread: runs the compute pipeline on each RAW frame at full sensor resolution,
 * draws the LUT view to the preview surface and, while recording, the log (or baked LUT) image
 * into the encoder's input surface.
 *
 * Preview-only: frames that arrive while the GPU is busy are skipped so the preview stays live.
 * Recording: every frame is processed in order; a frame is only lost if the camera itself has
 * to drop it, which shows up as a timestamp gap and is counted.
 */
class Renderer(
    private val sensor: SensorInfo,
    private val reader: ImageReader,
    private val metaSource: () -> FrameMeta?,
    /** Log-average linear brightness of the ISP's rendering (NaN if unknown); drives auto gain. */
    private val ispBrightness: () -> Float,
    private val onStats: (Stats) -> Unit,
) {
    /** [gpuMs]: thread time per frame (submission + any blocking on the GPU or encoder). */
    data class Stats(val gpuMs: Double, val fps: Double, val meta: FrameMeta?, val droppedWhileRecording: Int)

    /** Settings read on the GL thread each frame; written from the UI thread. */
    @Volatile var profile: LogProfile = LogProfile.APPLE_LOG
    /**
     * Gain before the log curve, automatic per lens. Phone ISPs expose RAW very differently per
     * lens (Xiaomi 14: main ~2.2 EV under its own rendering, telephoto/front ~0.6 EV), so the
     * auto part matches our brightness to the ISP's rendering of the same moment (+[AUTO_BIAS_EV],
     * fitted against ISP JPEGs in daylight). The log curves keep the sensor's clip point either way.
     */
    @Volatile var exposureOffsetEv: Float = 0f
    /** Holds the auto gain where it is (manual exposure mode). */
    @Volatile var autoGainFrozen: Boolean = false
    /** Log-average linear brightness of the latest RAW frame (green), and the share of near-clipped samples. */
    @Volatile var rawBrightness: Float = Float.NaN
        private set
    @Volatile var rawClipFraction: Float = 0f
        private set
    /**
     * Share of the image the view shows blown out although the RAW still holds it (more than
     * ~3.5 stops over middle grey after the gain, sensor not clipped): what eDR brings back.
     */
    @Volatile var recoverableClipFraction: Float = 0f
        private set
    /** Region (0..1 image coords: left, top, right, bottom) whose sharpness is measured, or null. */
    @Volatile var sharpnessRegion: FloatArray? = null
    /** Contrast of [sharpnessRegion] in the latest frame (brightness-normalised gradient energy). */
    @Volatile var sharpness: Float = 0f
        private set
    /** Sensor timestamp of the frame [sharpness] was measured on. */
    @Volatile var sharpnessTimestamp: Long = 0L
        private set

    /** Frames processed so far; lets callers wait for frames taken after a lens move. */
    @Volatile var frameCounter: Long = 0L
        private set

    /** When the last RAW frame was processed (elapsedRealtime ms); for stall detection. */
    @Volatile var lastFrameMs: Long = 0L
        private set

    /** Current auto gain in EV, for display. */
    @Volatile var autoGainEv: Float = Float.NaN
        private set

    /** All digital gain before the log curve, in EV (auto gain or its default, plus the user's). */
    val totalGainEv get() = (if (autoGainEv.isNaN()) DEFAULT_GAIN_EV else autoGainEv) + exposureOffsetEv
    @Volatile var lutStrength: Float = 1f
    /**
     * Tone balance of the view, -1..1 each (see PipelineShaders.finish `balanceTone`): pulls the
     * highlights down / lifts the shadows before the look or LUT. The recorded log never gets it.
     */
    @Volatile var toneHighlights: Float = 0f
    @Volatile var toneShadows: Float = 0f
    /**
     * The user's white balance trim on top of the ISP's (gains for R, G, G, B; see
     * CameraActivity.setWbShift). Part of development like the ISP's own gains, so the log gets it too.
     */
    @Volatile var wbShift: FloatArray = floatArrayOf(1f, 1f, 1f, 1f)
    @Volatile var saturation: Float = 1f
    @Volatile var vibrance: Float = 0f
    /** 2×2 superpixel output at half resolution instead of demosaiced full resolution. */
    @Volatile var superpixel: Boolean = false
    /**
     * Full-resolution processing for the preview too. Off by default: the preview area is ~1600 px
     * wide, so between recordings the cheap superpixel path (about a quarter of the GPU work) looks
     * the same and keeps the phone cooler; recording always runs at the recorded resolution.
     */
    @Volatile var previewFullRes: Boolean = false
    /**
     * Full resolution ahead of a recording: jumping from the light preview straight into 4K
     * recording dropped frames for ~1 s while the GPU clocked up, so the activity sets this,
     * waits a moment, then starts recording.
     */
    @Volatile var warmingUp: Boolean = false
    /** 0 off, 1 fix defect pixels, 2 + chroma NR low, 3 + chroma NR high. */
    @Volatile var cleanup: Int = 2

    /** Red focus-peaking overlay on the preview (never recorded). */
    @Volatile var peaking: Boolean = false
    /**
     * The log encoding the current LUT expects, when it isn't ours: the view converts to it before
     * the lookup (the recorded log stays in [profile]). null = apply the LUT to our log as is.
     */
    @Volatile var lutInput: LogProfile? = null
    @Volatile private var pendingLut: CubeLut? = null
    @Volatile private var lutEnabled = false

    private val thread = HandlerThread("gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val frameQueued = AtomicBoolean(false)

    private lateinit var egl: EglCore
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var viewW = 0
    private var viewH = 0
    private var rotation = 0

    private val w = sensor.size.width
    private val h = sensor.size.height
    private var rawTex = 0
    private var linearTex = 0
    private var viewTex = 0
    private var logTex = 0
    /** Develop output before the finish pass (chroma NR, LUT). */
    private var logRawTex = 0
    private var spLogRawTex = 0
    private var finishProgram = 0
    private var spViewTex = 0
    private var spLogTex = 0
    private var shadingTex = 0
    private var lutTex = 0
    private var lutSize = 0
    private var shadingDims = 0 to 0
    private var prepProgram = 0
    private var developProgram = 0
    private var superpixelProgram = 0
    private var displayProgram = 0

    private var recording: Recording? = null

    private class Recording(
        val recorder: Recorder,
        val bakeLut: Boolean,
        val superpixel: Boolean,
        val quarterTurns: Int,
        val context: EGLContext,
        val surface: EGLSurface,
        val frameNs: Long,
    ) {
        var firstTimestamp = -1L
        var lastTimestamp = -1L
        var dropped = 0
    }

    private var frameCount = 0
    private var fpsWindowStart = 0L
    private var gpuMsAvg = 0.0

    init {
        handler.post { setupGl() }
    }

    fun attachSurface(surface: Surface, width: Int, height: Int, rotationQuarterTurns: Int) = handler.post {
        releaseWindowSurface()
        windowSurface = egl.createWindowSurface(surface)
        viewW = width
        viewH = height
        rotation = rotationQuarterTurns
    }

    fun detachSurface() = handler.post { releaseWindowSurface() }

    /** Called from the camera thread whenever a RAW frame lands. */
    fun onFrameAvailable() {
        if (frameQueued.compareAndSet(false, true)) handler.post { renderFrames() }
    }

    /** null shows the plain log image. */
    fun setLut(lut: CubeLut?) {
        pendingLut = lut
        lutEnabled = lut != null
    }

    /** Starts feeding [recorder]; [onFailed] runs on the GL thread if the encoder surface can't be set up. */
    fun startRecording(recorder: Recorder, fps: Int, bakeLut: Boolean, onFailed: (String) -> Unit) = handler.post {
        try {
            val tenBit = recorder.config.codec.tenBit
            val config = egl.chooseConfig(tenBit = tenBit, recordable = true)
                ?: error("no ${if (tenBit) "10" else "8"}-bit recordable EGL config")
            val ctx = egl.createContext(config, egl.mainContext)
            val surface = egl.createWindowSurface(recorder.inputSurface, config)
            // Half turns can be baked in at the same frame size (front camera is upside down in landscape).
            recording = Recording(recorder, bakeLut, superpixel, if (rotation == 2) 2 else 0, ctx, surface, 1_000_000_000L / fps)
        } catch (e: Exception) {
            onFailed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Stops feeding the encoder; [onStopped] runs on the GL thread once no more frames will be drawn. */
    fun stopRecording(onStopped: () -> Unit) = handler.post {
        recording?.let {
            egl.makeCurrent(EGL14.EGL_NO_SURFACE)
            egl.destroySurface(it.surface)
            egl.destroyContext(it.context)
        }
        recording = null
        onStopped()
    }

    /** Blocks (briefly) until GL is torn down, so a new renderer can take over the same Surface. */
    fun release() {
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            recording?.let {
                egl.destroySurface(it.surface)
                egl.destroyContext(it.context)
            }
            recording = null
            releaseWindowSurface()
            egl.release()
            thread.quitSafely()
            done.countDown()
        }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun setupGl() {
        egl = EglCore()
        prepProgram = Gl.computeProgram(PipelineShaders.prep)
        developProgram = Gl.computeProgram(PipelineShaders.develop)
        superpixelProgram = Gl.computeProgram(PipelineShaders.developSuperpixel)
        finishProgram = Gl.computeProgram(PipelineShaders.finish)
        displayProgram = Gl.renderProgram(PipelineShaders.displayVertex, PipelineShaders.displayFragment)

        rawTex = Gl.texture2D(GLES30.GL_R16UI, w, h, linear = false)
        linearTex = Gl.texture2D(GLES30.GL_R32F, w, h, linear = false)
        viewTex = Gl.texture2D(GLES30.GL_RGBA16F, w, h, linear = true)
        logTex = Gl.texture2D(GLES30.GL_RGBA16F, w, h, linear = true)
        logRawTex = Gl.texture2D(GLES30.GL_RGBA16F, w, h, linear = false)
        spLogRawTex = Gl.texture2D(GLES30.GL_RGBA16F, w / 2, h / 2, linear = false)
        spViewTex = Gl.texture2D(GLES30.GL_RGBA16F, w / 2, h / 2, linear = true)
        spLogTex = Gl.texture2D(GLES30.GL_RGBA16F, w / 2, h / 2, linear = true)
        uploadShading(floatArrayOf(1f, 1f, 1f, 1f), 1, 1)
        uploadLut(CubeLut("identity", 2, floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 1f, 0f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)))
        Gl.check("setup")
    }

    private fun renderFrames() {
        frameQueued.set(false)
        val t0 = SystemClock.elapsedRealtimeNanos()
        var meta: FrameMeta? = null
        var processed = 0
        if (recording != null) {
            // Every frame goes to the encoder, oldest first.
            while (true) {
                val image = acquireNext() ?: break
                meta = processFrame(image)
                processed++
            }
        } else {
            reader.acquireLatestImage()?.let {
                meta = processFrame(it)
                processed++
            }
        }
        if (processed == 0) return
        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            drawToScreen()
            egl.swapWindow()
        }
        updateStats((SystemClock.elapsedRealtimeNanos() - t0) / 1e6 / processed, meta, processed)
    }

    /** acquireNextImage throws instead of returning null when all buffers are held; treat both as "none". */
    private fun acquireNext(): Image? = try {
        reader.acquireNextImage()
    } catch (e: IllegalStateException) {
        null
    }

    private fun processFrame(image: Image): FrameMeta? {
        egl.makeCurrent(windowSurface)
        val timestamp = image.timestamp
        lastFrameMs = SystemClock.elapsedRealtime()
        val meta = metaSource()
        rawLogAverage(image, meta)?.let { raw -> updateAutoGain(raw) }
        sharpnessRegion?.let {
            sharpness = measureSharpness(image, it)
            sharpnessTimestamp = timestamp
        }
        frameCounter++
        image.use { uploadRaw(it) } // glTexSubImage2D copies the data before returning
        pendingLut?.let {
            uploadLut(it)
            pendingLut = null
        }
        runPipeline(meta)
        recording?.let { encodeFrame(it, timestamp) }
        pendingLogCapture?.let { (width, onFrame) ->
            pendingLogCapture = null
            deliverLogCapture(width, onFrame)
        }
        return meta
    }

    private fun encodeFrame(rec: Recording, timestamp: Long) {
        if (rec.firstTimestamp < 0) {
            rec.firstTimestamp = timestamp
            rec.recorder.onFirstVideoFrame(timestamp)
        }
        if (rec.lastTimestamp >= 0) {
            val gap = (timestamp - rec.lastTimestamp).toDouble() / rec.frameNs
            rec.dropped += (gap.roundToInt() - 1).coerceAtLeast(0)
        }
        rec.lastTimestamp = timestamp

        // The encoder context shares our textures; wait on the GPU (not the CPU) for the compute passes.
        val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        GLES30.glFlush()
        egl.makeCurrent(rec.surface, rec.context)
        GLES30.glWaitSync(fence, 0, GLES30.GL_TIMEOUT_IGNORED)
        GLES30.glDeleteSync(fence)

        val sp = rec.superpixel
        GLES30.glViewport(0, 0, if (sp) w / 2 else w, if (sp) h / 2 else h)
        drawTexture(
            when {
                rec.bakeLut -> if (sp) spViewTex else viewTex
                else -> if (sp) spLogTex else logTex
            },
            rotationQuarterTurns = rec.quarterTurns,
        )
        EGLExt.eglPresentationTimeANDROID(egl.display, rec.surface, timestamp - rec.firstTimestamp)
        EGL14.eglSwapBuffers(egl.display, rec.surface)
        egl.makeCurrent(windowSurface)
    }

    /** Log-average of normalised green RAW samples on a coarse grid (green: same CFA colour everywhere). */
    private fun rawLogAverage(image: Image, meta: FrameMeta?): Float? {
        meta ?: return null
        val plane = image.planes[0]
        val buf = plane.buffer
        val (rx, ry) = sensor.redOffset
        val black = meta.blackLevel[0]
        val range = meta.whiteLevel - black
        var sum = 0.0
        var n = 0
        var clipped = 0
        var recoverable = 0
        val gain = currentGain()
        for (gy in 1 until 24) for (gx in 1 until 32) {
            // Green on a red row: red's row, the other column parity.
            val x = ((w * gx / 32) and 1.inv()) + (1 - rx)
            val y = ((h * gy / 24) and 1.inv()) + ry
            val v = ((buf.getShort(y * plane.rowStride + x * 2).toInt() and 0xFFFF) - black) / range
            sum += Math.log(v.coerceAtLeast(0f) + 1e-4)
            if (v > 0.9f) clipped++ else if (v * gain > 2f) recoverable++
            n++
        }
        rawClipFraction = clipped.toFloat() / n
        recoverableClipFraction = recoverable.toFloat() / n
        return Math.exp(sum / n).toFloat().also { rawBrightness = it }
    }

    /**
     * Focus measure for contrast AF: squared gradients (Tenengrad) of the green channel inside
     * [region], on 2×2-block averages of both greens to halve sensor noise, divided by the
     * mean, which cancels small exposure changes without amplifying noise in dark areas the way
     * dividing by the squared mean does. At most ~200×200 blocks.
     *
     * Runs on the GL thread for every frame while AF or a tapped spot is being watched, so the
     * rows are copied out in bulk: reading pixel by pixel through the ByteBuffer cost ~20 ms a frame.
     */
    private fun measureSharpness(image: Image, region: FloatArray): Float {
        val plane = image.planes[0]
        val pixels = plane.buffer.asShortBuffer() // keeps the buffer's (native) byte order
        val strideShorts = plane.rowStride / 2
        val (rx, ry) = sensor.redOffset
        val bx0 = ((region[0] * w).toInt() / 2).coerceIn(0, w / 2 - 3)
        val bx1 = ((region[2] * w).toInt() / 2).coerceIn(bx0 + 2, w / 2 - 2)
        val by0 = ((region[1] * h).toInt() / 2).coerceIn(0, h / 2 - 3)
        val by1 = ((region[3] * h).toInt() / 2).coerceIn(by0 + 2, h / 2 - 2)
        val step = ((bx1 - bx0) / 200).coerceAtLeast(1)
        // Sensor rows 2·by .. 2·by+3 (this block row and the next), columns 2·bx0 .. 2·(bx1+1)+1.
        val x0 = bx0 * 2
        val len = (bx1 - bx0 + 2) * 2
        if (rows.size != 4 || rows[0].size < len) rows = Array(4) { ShortArray(len) }
        var energy = 0.0
        var level = 0.0
        var n = 0
        var by = by0
        while (by < by1) {
            for (k in 0 until 4) {
                pixels.position((by * 2 + k) * strideShorts + x0)
                pixels.get(rows[k], 0, len)
            }
            // Mean of the two greens of block column i (relative to bx0) in block row j (0 or 1).
            fun g(i: Int, j: Int): Float {
                val ge = rows[j * 2 + ry][i * 2 + 1 - rx].toInt() and 0xFFFF
                val go = rows[j * 2 + 1 - ry][i * 2 + rx].toInt() and 0xFFFF
                return (ge + go) * 0.5f
            }
            var i = 0
            while (i < bx1 - bx0) {
                val c = g(i, 0)
                val dx = g(i + 1, 0) - c
                val dy = g(i, 1) - c
                energy += dx * dx + dy * dy
                level += c
                n++
                i += step
            }
            by += step
        }
        val mean = level / n
        return if (mean > 0) (energy / n / mean).toFloat() else 0f
    }

    private var rows = arrayOf<ShortArray>()

    private fun updateAutoGain(raw: Float) {
        if (autoGainFrozen) return
        val isp = ispBrightness()
        if (isp.isNaN() || raw <= 0f) return
        val target = (log2(isp / raw) + AUTO_BIAS_EV).coerceIn(0f, 5f)
        // ~1 s to settle at 30 fps, so exposure follows the scene without visible pumping.
        autoGainEv = if (autoGainEv.isNaN()) target else autoGainEv + (target - autoGainEv) * 0.08f
    }

    private fun currentGain(): Float = 2f.pow(totalGainEv)

    private fun uploadRaw(image: Image) {
        val plane = image.planes[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTex)
        // Camera rows can be padded; tell GL the real row length so no copy is needed.
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, plane.rowStride / plane.pixelStride)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, plane.buffer)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
    }

    private fun runPipeline(meta: FrameMeta?) {
        if (meta?.shading != null && (meta.shadingCols to meta.shadingRows) != shadingDims) {
            uploadShading(meta.shading, meta.shadingCols, meta.shadingRows)
        } else if (meta?.shading != null) {
            updateShading(meta.shading)
        }

        val groupsX = (w + 15) / 16
        val groupsY = (h + 15) / 16
        val sp = activeSuperpixel()

        // Pass 1: normalise RAW.
        GLES30.glUseProgram(prepProgram)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, rawTex)
        Gl.bindTexture(1, GLES30.GL_TEXTURE_2D, shadingTex)
        GLES31.glBindImageTexture(0, linearTex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_R32F)
        GLES30.glUniform4fv(Gl.uniform(prepProgram, "uBlack"), 1, meta?.blackLevel ?: floatArrayOf(0f, 0f, 0f, 0f), 0)
        GLES30.glUniform1f(Gl.uniform(prepProgram, "uWhite"), meta?.whiteLevel ?: 1023f)
        val shift = wbShift
        val wb = meta?.wbGains ?: floatArrayOf(1f, 1f, 1f, 1f)
        GLES30.glUniform4fv(Gl.uniform(prepProgram, "uWb"), 1, FloatArray(4) { wb[it] * shift[it] }, 0)
        GLES30.glUniform2i(Gl.uniform(prepProgram, "uRedOffset"), sensor.redOffset.first, sensor.redOffset.second)
        GLES30.glUniform1i(Gl.uniform(prepProgram, "uFixDefects"), if (cleanup >= 1) 1 else 0)
        GLES31.glDispatchCompute(groupsX, groupsY, 1)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)

        // Pass 2: demosaic, colour, log, LUT.
        val p = profile
        val ccm = meta?.ccm ?: doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val camToTarget = ColorMath.multiply(ColorMath.convert(Primaries.REC709, p.primaries), ccm)
        val prog = if (sp) superpixelProgram else developProgram
        GLES30.glUseProgram(prog)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, linearTex)
        GLES31.glBindImageTexture(0, if (sp) spLogRawTex else logRawTex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
        GLES30.glUniform2i(Gl.uniform(prog, "uRedOffset"), sensor.redOffset.first, sensor.redOffset.second)
        GLES30.glUniformMatrix3fv(Gl.uniform(prog, "uCamToTarget"), 1, false, ColorMath.toGlColumnMajor(camToTarget), 0)
        GLES30.glUniform1f(Gl.uniform(prog, "uExposure"), currentGain())
        GLES30.glUniform1i(Gl.uniform(prog, "uCurve"), p.shaderId)
        val outGroupsX = if (sp) (w / 2 + 15) / 16 else groupsX
        val outGroupsY = if (sp) (h / 2 + 15) / 16 else groupsY
        GLES31.glDispatchCompute(outGroupsX, outGroupsY, 1)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)

        // Pass 3: chroma NR, then the clean log and the LUT/look view.
        GLES30.glUseProgram(finishProgram)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, if (sp) spLogRawTex else logRawTex)
        Gl.bindTexture(1, GLES30.GL_TEXTURE_3D, lutTex)
        GLES31.glBindImageTexture(0, if (sp) spViewTex else viewTex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
        GLES31.glBindImageTexture(1, if (sp) spLogTex else logTex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
        GLES30.glUniform1i(Gl.uniform(finishProgram, "uChromaNr"), (cleanup - 1).coerceIn(0, 2))
        // Writing the full-size log image costs ~100 MB of memory traffic a frame; skip it unless used.
        GLES30.glUniform1i(Gl.uniform(finishProgram, "uWriteLog"), if (recording?.bakeLut == false || pendingLogCapture != null) 1 else 0)
        GLES30.glUniform1i(Gl.uniform(finishProgram, "uUseLut"), if (lutEnabled) 1 else 0)
        val lutIn = lutInput?.takeIf { it != p }
        GLES30.glUniform1i(Gl.uniform(finishProgram, "uLutConvert"), if (lutIn != null) 1 else 0)
        GLES30.glUniform1i(Gl.uniform(finishProgram, "uCurve"), p.shaderId)
        if (lutIn != null) {
            GLES30.glUniform1i(Gl.uniform(finishProgram, "uLutCurve"), lutIn.shaderId)
            GLES30.glUniformMatrix3fv(Gl.uniform(finishProgram, "uLutGamut"), 1, false,
                ColorMath.toGlColumnMajor(ColorMath.convert(p.primaries, lutIn.primaries)), 0)
        }
        val toneOn = abs(toneHighlights) > 0.001f || abs(toneShadows) > 0.001f
        GLES30.glUniform1i(Gl.uniform(finishProgram, "uTone"), if (toneOn) 1 else 0)
        if (toneOn) {
            GLES30.glUniform1f(Gl.uniform(finishProgram, "uToneHi"), toneHighlights)
            GLES30.glUniform1f(Gl.uniform(finishProgram, "uToneLo"), toneShadows)
            val toXyz = ColorMath.rgbToXyz(p.primaries)
            GLES30.glUniform3f(Gl.uniform(finishProgram, "uLumaW"), toXyz[3].toFloat(), toXyz[4].toFloat(), toXyz[5].toFloat())
        }
        GLES30.glUniform1f(Gl.uniform(finishProgram, "uLutSize"), lutSize.toFloat())
        GLES30.glUniform1f(Gl.uniform(finishProgram, "uLutStrength"), lutStrength)
        GLES30.glUniform1f(Gl.uniform(finishProgram, "uSaturation"), saturation)
        GLES30.glUniform1f(Gl.uniform(finishProgram, "uVibrance"), vibrance)
        GLES31.glDispatchCompute(outGroupsX, outGroupsY, 1)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
    }

    private fun drawToScreen() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        // Letterbox the (possibly rotated) image into the view.
        val quarter = rotation % 2 == 1
        val imgAspect = if (quarter) h.toFloat() / w else w.toFloat() / h
        val viewAspect = viewW.toFloat() / viewH
        val (vw, vh) = if (viewAspect > imgAspect) (viewH * imgAspect).toInt() to viewH else viewW to (viewW / imgAspect).toInt()
        GLES30.glViewport((viewW - vw) / 2, (viewH - vh) / 2, vw, vh)
        val sp = activeSuperpixel()
        drawTexture(if (sp) spViewTex else viewTex, rotation, peakingTexel = if (peaking) (if (sp) 2f else 1f) else 0f)
    }

    /** Mid-recording the mode is fixed by what the encoder was set up for; between recordings see [previewFullRes]. */
    private fun activeSuperpixel() = recording?.superpixel ?: (superpixel || !(previewFullRes || warmingUp))

    /** [peakingTexel] > 0 enables focus peaking, sampling that many full-res pixels apart. */
    private fun drawTexture(tex: Int, rotationQuarterTurns: Int, peakingTexel: Float = 0f) {
        GLES30.glUseProgram(displayProgram)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(Gl.uniform(displayProgram, "uImage"), 0)
        GLES30.glUniform1i(Gl.uniform(displayProgram, "uRotation"), rotationQuarterTurns)
        GLES30.glUniform1i(Gl.uniform(displayProgram, "uPeaking"), if (peakingTexel > 0) 1 else 0)
        GLES30.glUniform2f(Gl.uniform(displayProgram, "uTexel"), peakingTexel / w, peakingTexel / h)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    /**
     * Grabs the clean log image of the next frame at [width] px wide (8-bit is plenty for
     * previewing a look). [onFrame] runs on the GL thread.
     */
    fun captureLogFrame(width: Int, onFrame: (Bitmap) -> Unit) = handler.post { pendingLogCapture = width to onFrame }

    /** Set on the GL thread; the next processed frame writes the log image and hands it over. */
    private var pendingLogCapture: Pair<Int, (Bitmap) -> Unit>? = null

    private fun deliverLogCapture(width: Int, onFrame: (Bitmap) -> Unit) {
        val sp = activeSuperpixel()
        val height = width * h / w
        val tex = Gl.texture2D(GLES30.GL_RGBA8, width, height, linear = true)
        val fbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        GLES30.glViewport(0, 0, width, height)
        drawTexture(if (sp) spLogTex else logTex, rotationQuarterTurns = 0)
        val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES30.glDeleteTextures(1, intArrayOf(tex), 0)

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        pixels.rewind()
        bmp.copyPixelsFromBuffer(pixels)
        // Our draw puts texture row 0 at the top of the viewport, which glReadPixels returns last.
        val upright = Bitmap.createBitmap(bmp, 0, 0, width, height, Matrix().apply { preScale(1f, -1f) }, false)
        bmp.recycle()
        onFrame(upright)
    }

    private fun uploadShading(gains: FloatArray, cols: Int, rows: Int) {
        if (shadingTex != 0) GLES30.glDeleteTextures(1, intArrayOf(shadingTex), 0)
        shadingTex = Gl.texture2D(GLES30.GL_RGBA16F, cols, rows, linear = true)
        shadingDims = cols to rows
        updateShading(gains)
    }

    private fun updateShading(gains: FloatArray) {
        val (cols, rows) = shadingDims
        val buf = ByteBuffer.allocateDirect(gains.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(gains)
        buf.rewind()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadingTex)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, cols, rows, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)
    }

    private fun uploadLut(lut: CubeLut) {
        if (lutTex != 0) GLES30.glDeleteTextures(1, intArrayOf(lutTex), 0)
        val n = lut.size
        val rgba = FloatArray(n * n * n * 4)
        for (i in 0 until n * n * n) {
            rgba[i * 4] = lut.rgb[i * 3]
            rgba[i * 4 + 1] = lut.rgb[i * 3 + 1]
            rgba[i * 4 + 2] = lut.rgb[i * 3 + 2]
            rgba[i * 4 + 3] = 1f
        }
        val buf = ByteBuffer.allocateDirect(rgba.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(rgba)
        buf.rewind()
        lutTex = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_3D, 1, GLES30.GL_RGBA16F, n, n, n)
        GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_3D, 0, 0, 0, 0, n, n, n, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)
        for (param in intArrayOf(GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_TEXTURE_MAG_FILTER)) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, param, GLES30.GL_LINEAR)
        }
        for (param in intArrayOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R)) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, param, GLES30.GL_CLAMP_TO_EDGE)
        }
        lutSize = n
    }

    private fun updateStats(msPerFrame: Double, meta: FrameMeta?, frames: Int) {
        gpuMsAvg = if (gpuMsAvg == 0.0) msPerFrame else gpuMsAvg * 0.9 + msPerFrame * 0.1
        frameCount += frames
        val now = SystemClock.elapsedRealtime()
        if (fpsWindowStart == 0L) fpsWindowStart = now
        if (now - fpsWindowStart >= 1000) {
            val fps = frameCount * 1000.0 / (now - fpsWindowStart)
            frameCount = 0
            fpsWindowStart = now
            onStats(Stats(gpuMsAvg, fps, meta, recording?.dropped ?: 0))
        }
    }

    private fun releaseWindowSurface() {
        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            egl.makeCurrent(EGL14.EGL_NO_SURFACE)
            egl.destroySurface(windowSurface)
            windowSurface = EGL14.EGL_NO_SURFACE
        }
    }

    companion object {
        /** Extra over "match the ISP's brightness", from daylight fits against ISP JPEGs. */
        const val AUTO_BIAS_EV = 0.3f
        /** Until the first measurement arrives. */
        const val DEFAULT_GAIN_EV = 2f
    }

    private fun EglCore.swapWindow() = EGL14.eglSwapBuffers(display, windowSurface)
}
