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
import com.authrec.color.ViewLut
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
    /** 0 off, 1 fix defect pixels, 2 + temporal colour NR, 3 + stronger temporal colour NR. */
    @Volatile var cleanup: Int = 2

    /** Red focus-peaking overlay on the preview (never recorded). */
    @Volatile var peaking: Boolean = false
    /**
     * Share of the image's height a cropped frame keeps (16:9 from 4:3 = 0.75; 1 = open gate): the
     * preview darkens the rest so the framing shows while what's coming in stays visible.
     */
    @Volatile var frameCrop: Float = 1f
    /** Glass controls over the preview: drawn with a blurred copy of it behind them (see [GlassBackdrop]). */
    @Volatile var glassRects: List<GlassRect> = emptyList()
    /**
     * Debug (adb pipetiming=true): glFinish after every stage of [processFrame] and log the
     * average time of each, so the GPU's share of each pass shows. Slows everything down a little.
     */
    @Volatile var pipeTiming = false
    private val stageNs = LongArray(6)
    private var stageFrames = 0
    private var stageMark = 0L

    private fun stage(i: Int) {
        if (!pipeTiming) return
        GLES30.glFinish()
        val now = SystemClock.elapsedRealtimeNanos()
        if (i >= 0) stageNs[i] += now - stageMark
        stageMark = now
    }

    private fun logStages() {
        if (!pipeTiming || ++stageFrames < 60) return
        android.util.Log.i("AuthRec", "pipeline %s%s%s: CPU reads %.1f, upload %.1f, prep %.1f, develop %.1f, encode %.1f ms".format(
            if (activeSuperpixel()) "superpixel" else "full size", if (recording != null) " recording" else "",
            if (cleanup >= 2) " colour NR" else "",
            *stageNs.filterIndexed { i, _ -> i != 4 }.map { it / 60 / 1e6 }.toTypedArray()))
        stageNs.fill(0)
        stageFrames = 0
    }

    /** Debug: log what the glass backdrop costs (adb glasstiming=true). */
    @Volatile var glassTiming = false
    private var backdrop: GlassBackdrop? = null
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
    /** Show the image left-right mirrored (selfie style); recordings keep what was set at their start. */
    @Volatile var mirror = false
    /**
     * Set on the GL thread by [release]. The camera keeps delivering frames until it's closed after
     * us, and a frame queued behind the teardown crashed on the destroyed context (EGL_BAD_CONTEXT
     * on a 2.6x → 1x switch, which replaces the renderer: another RAW size).
     */
    private var released = false

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
    private var spViewTex = 0
    private var spLogTex = 0
    private var shadingTex = 0
    private var lutTex = 0
    private var lutSize = 0
    private var shadingDims = 0 to 0
    private var prepProgram = 0
    private var developProgram = 0
    private var coarseProgram = 0
    private var superpixelProgram = 0
    private var displayProgram = 0

    private var recording: Recording? = null

    private class Recording(
        val recorder: Recorder,
        val bakeLut: Boolean,
        val superpixel: Boolean,
        val quarterTurns: Int,
        /** Encoder frame size and the part of the image (texture x, y, width, height) it shows. */
        val outW: Int,
        val outH: Int,
        val crop: FloatArray,
        val context: EGLContext,
        val surface: EGLSurface,
        val frameNs: Long,
        val mirror: Boolean,
    ) {
        /** Frames go to the encoder but don't count yet (see Recorder.commit). */
        @Volatile var preroll = true
        var commitPending = false
        var prerollSlowestMs = 0.0
        /** Sensor time of the first frame given to the encoder (its pts 0, also the audio's origin). */
        var originNs = -1L
        /** Sensor time of the clip's first frame (the commit); lost frames and timing count from it. */
        var firstTimestamp = -1L
        var lastTimestamp = -1L
        var dropped = 0
        // Where time went, for the drops some clips start with (see timingSummary).
        var slowestFrameMs = 0.0
        var slowestFrameAt = 0.0
        var slowestSwapMs = 0.0
        val gaps = StringBuilder()

        /** One line for the event log: when frames were lost and what the GL thread was doing. */
        fun timingSummary() = "Recording timing: $dropped frames lost" +
            (if (gaps.isNotEmpty()) " (at$gaps)" else "") +
            ", slowest frame %.0f ms at %.2f s, slowest encoder hand-over %.0f ms, slowest pre-roll frame %.0f ms"
                .format(slowestFrameMs, slowestFrameAt, slowestSwapMs, prerollSlowestMs)
    }

    private var frameCount = 0
    private var fpsWindowStart = 0L
    private var gpuMsAvg = 0.0

    init {
        handler.post { setupGl() }
    }

    /** Runs [block] on the GL thread unless [release] got there first. */
    private fun post(block: () -> Unit) = handler.post { if (!released) block() }

    fun attachSurface(surface: Surface, width: Int, height: Int, rotationQuarterTurns: Int) = post {
        releaseWindowSurface()
        windowSurface = egl.createWindowSurface(surface)
        viewW = width
        viewH = height
        rotation = rotationQuarterTurns
    }

    fun detachSurface() = post { releaseWindowSurface() }

    /** Called from the camera thread whenever a RAW frame lands. */
    fun onFrameAvailable() {
        if (frameQueued.compareAndSet(false, true)) post { renderFrames() }
    }

    /** null shows the plain log image. */
    fun setLut(lut: CubeLut?) {
        sourceLut = lut
        pendingLut = lut
        lutEnabled = lut != null
    }

    // ---- The view LUT with eDR, the LUT's input conversion and strength folded in (ViewLut) ----

    /** What a baked view LUT was made from; it's used only while all of this still holds. */
    private data class ViewKey(val source: CubeLut, val profile: LogProfile, val lutInput: LogProfile?,
                               val hi: Float, val lo: Float, val strength: Float)

    @Volatile private var sourceLut: CubeLut? = null
    /** Debug (adb bakedview=false): always compute the view per pixel, to compare. */
    @Volatile var bakeViewLut = true
    private val bakeThread = HandlerThread("view-lut").apply { start() }
    private val bakeHandler = Handler(bakeThread.looper)
    @Volatile private var bakeWanted: ViewKey? = null
    @Volatile private var pendingBaked: Pair<ViewKey, CubeLut>? = null
    private var bakedKey: ViewKey? = null
    private var bakedTex = 0
    private var bakedSize = 0

    private fun currentViewKey(): ViewKey? = sourceLut?.let {
        ViewKey(it, profile, lutInput, toneHighlights, toneShadows, lutStrength)
    }

    /** Bakes [key] on the bake thread (only the newest request is worked on). */
    private fun requestBake(key: ViewKey) {
        if (bakeWanted == key) return
        bakeWanted = key
        bakeHandler.post {
            if (bakeWanted != key) return@post // a newer request follows
            val lut = runCatching { ViewLut.bake(key.source, key.profile, key.lutInput, key.hi, key.lo, key.strength) }.getOrNull()
                ?: return@post
            pendingBaked = key to lut
        }
    }

    /**
     * Starts feeding [recorder] with frames of [outW]×[outH], the image's centre band of [cropHeight]
     * (share of its height; 1 = open gate), as pre-roll until [commitRecording]. [onFailed] runs on
     * the GL thread if the encoder surface can't be set up.
     */
    fun startRecording(recorder: Recorder, fps: Int, bakeLut: Boolean, outW: Int, outH: Int, cropHeight: Float,
                       onFailed: (String) -> Unit) = handler.post {
        if (released) return@post onFailed("camera closed")
        try {
            val tenBit = recorder.config.codec.tenBit
            val config = egl.chooseConfig(tenBit = tenBit, recordable = true)
                ?: error("no ${if (tenBit) "10" else "8"}-bit recordable EGL config")
            val ctx = egl.createContext(config, egl.mainContext)
            val surface = egl.createWindowSurface(recorder.inputSurface, config)
            // Half turns can be baked in at the same frame size (front camera is upside down in landscape).
            recording = Recording(recorder, bakeLut, superpixel, if (rotation == 2) 2 else 0, outW, outH,
                floatArrayOf(0f, (1f - cropHeight) / 2f, 1f, cropHeight), ctx, surface, 1_000_000_000L / fps, mirror)
        } catch (e: Exception) {
            onFailed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Ends the pre-roll: the next frame is where the clip starts (its key frame follows soon after). */
    fun commitRecording() = post { recording?.commitPending = true }

    /**
     * Stops feeding the encoder; [onStopped] runs on the GL thread once no more frames will be drawn,
     * with a line about the recording's frame timing (see [Recording.timingSummary]).
     */
    fun stopRecording(onStopped: (String?) -> Unit) = handler.post {
        if (released) return@post onStopped(null)
        val summary = recording?.let {
            egl.makeCurrent(EGL14.EGL_NO_SURFACE)
            egl.destroySurface(it.surface)
            egl.destroyContext(it.context)
            it.timingSummary()
        }
        recording = null
        onStopped(summary)
    }

    /** Blocks (briefly) until GL is torn down, so a new renderer can take over the same Surface. */
    fun release() {
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            released = true
            recording?.let {
                egl.destroySurface(it.surface)
                egl.destroyContext(it.context)
            }
            recording = null
            backdrop?.release()
            backdrop = null
            releaseHistory()
            releaseWindowSurface()
            egl.release()
            thread.quitSafely()
            bakeThread.quitSafely()
            done.countDown()
        }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun setupGl() {
        egl = EglCore()
        prepProgram = Gl.computeProgram(PipelineShaders.prep)
        developProgram = Gl.computeProgram(PipelineShaders.develop)
        coarseProgram = Gl.computeProgram(PipelineShaders.coarseColour)
        superpixelProgram = Gl.computeProgram(PipelineShaders.developSuperpixel)
        displayProgram = Gl.renderProgram(PipelineShaders.displayVertex, PipelineShaders.displayFragment)

        rawTex = Gl.texture2D(GLES30.GL_R16UI, w, h, linear = false)
        linearTex = Gl.texture2D(GLES30.GL_R32F, w, h, linear = false)
        viewTex = Gl.texture2D(GLES30.GL_RGBA16F, w, h, linear = true)
        logTex = Gl.texture2D(GLES30.GL_RGBA16F, w, h, linear = true)
        noHistory = Gl.texture2D(GLES30.GL_RGBA16F, 1, 1, linear = false)
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
        val rec = recording
        if (rec != null) {
            // Every frame goes to the encoder, oldest first.
            while (true) {
                val image = acquireNext() ?: break
                val f0 = SystemClock.elapsedRealtimeNanos()
                val ts = image.timestamp
                meta = processFrame(image)
                processed++
                val ms = (SystemClock.elapsedRealtimeNanos() - f0) / 1e6
                if (rec.preroll) {
                    rec.prerollSlowestMs = maxOf(rec.prerollSlowestMs, ms)
                } else if (ms > rec.slowestFrameMs) {
                    rec.slowestFrameMs = ms
                    rec.slowestFrameAt = (ts - rec.firstTimestamp) / 1e9
                }
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
        stage(-1)
        val timestamp = image.timestamp
        lastFrameMs = SystemClock.elapsedRealtime()
        val meta = metaSource()
        rawLogAverage(image, meta)?.let { raw -> updateAutoGain(raw) }
        sharpnessRegion?.let {
            sharpness = measureSharpness(image, it)
            sharpnessTimestamp = timestamp
        }
        frameCounter++
        stage(0)
        image.use { uploadRaw(it) } // glTexSubImage2D copies the data before returning
        pendingLut?.let {
            uploadLut(it)
            pendingLut = null
        }
        stage(1)
        runPipeline(meta)
        recording?.let { encodeFrame(it, timestamp) }
        stage(5)
        logStages()
        pendingLogCapture?.let { (width, onFrame) ->
            pendingLogCapture = null
            deliverLogCapture(width, onFrame)
        }
        return meta
    }

    private fun encodeFrame(rec: Recording, timestamp: Long) {
        if (rec.originNs < 0) {
            rec.originNs = timestamp
            rec.recorder.onFirstVideoFrame(timestamp)
        }
        if (rec.commitPending) {
            // The clip starts here: timing and lost frames count from this frame on.
            rec.commitPending = false
            rec.preroll = false
            rec.recorder.commit(timestamp)
            rec.firstTimestamp = timestamp
            rec.lastTimestamp = -1L
        }
        if (!rec.preroll && rec.lastTimestamp >= 0) {
            val lost = ((timestamp - rec.lastTimestamp).toDouble() / rec.frameNs).roundToInt() - 1
            if (lost > 0) {
                rec.dropped += lost
                if (rec.gaps.length < 120) rec.gaps.append(" %.2f s ×%d".format((timestamp - rec.firstTimestamp) / 1e9, lost))
            }
        }
        rec.lastTimestamp = timestamp

        // The encoder context shares our textures; wait on the GPU (not the CPU) for the compute passes.
        val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        GLES30.glFlush()
        egl.makeCurrent(rec.surface, rec.context)
        GLES30.glWaitSync(fence, 0, GLES30.GL_TIMEOUT_IGNORED)
        GLES30.glDeleteSync(fence)

        val sp = rec.superpixel
        GLES30.glViewport(0, 0, rec.outW, rec.outH)
        drawTexture(
            when {
                rec.bakeLut -> if (sp) spViewTex else viewTex
                else -> if (sp) spLogTex else logTex
            },
            rotationQuarterTurns = rec.quarterTurns,
            crop = rec.crop,
            mirror = rec.mirror,
        )
        if (Recorder.debugBars) drawTestBars(rec.outW, rec.outH)
        EGLExt.eglPresentationTimeANDROID(egl.display, rec.surface, timestamp - rec.originNs)
        val s0 = SystemClock.elapsedRealtimeNanos()
        // Blocks when the encoder hasn't taken the previous frames yet.
        EGL14.eglSwapBuffers(egl.display, rec.surface)
        val swapMs = (SystemClock.elapsedRealtimeNanos() - s0) / 1e6
        if (!rec.preroll) rec.slowestSwapMs = maxOf(rec.slowestSwapMs, swapMs)
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
     * Weighted towards the region's centre (Gaussian, the edges count ~15 %): a tapped subject sits
     * in the middle, and with every block counted equally a smooth subject (a berry, a face) lost
     * to busy, out-of-focus background around it, which has far more texture once it's sharp.
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
        // Separable Gaussian weights, sigma = 0.55 of the half-size on each axis.
        val cols = (bx1 - bx0 + step - 1) / step
        if (colWeights.size < cols) colWeights = FloatArray(cols)
        val halfX = (bx1 - bx0) / 2f
        for (k in 0 until cols) {
            val t = (k * step - halfX) / (0.55f * halfX)
            colWeights[k] = kotlin.math.exp(-0.5f * t * t)
        }
        val halfY = (by1 - by0) / 2f
        var energy = 0.0
        var level = 0.0
        var n = 0.0
        var by = by0
        while (by < by1) {
            val ty = (by - by0 - halfY) / (0.55f * halfY)
            val wy = kotlin.math.exp(-0.5f * ty * ty)
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
            var k = 0
            while (i < bx1 - bx0) {
                val c = g(i, 0)
                val dx = g(i + 1, 0) - c
                val dy = g(i, 1) - c
                val wgt = wy * colWeights[k]
                energy += wgt * (dx * dx + dy * dy)
                level += wgt * c
                n += wgt
                i += step
                k++
            }
            by += step
        }
        val mean = level / n
        return if (mean > 0) (energy / n / mean).toFloat() else 0f
    }

    private var rows = arrayOf<ShortArray>()
    private var colWeights = FloatArray(0)

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
        if (cleanup >= 2) updateCoarseColour()
        stage(2)

        // Pass 2: demosaic, colour noise reduction, colour, log, the view (and the clean log).
        val p = profile
        val ccm = meta?.ccm ?: doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val camToTarget = ColorMath.multiply(ColorMath.convert(Primaries.REC709, p.primaries), ccm)
        val prog = if (sp) superpixelProgram else developProgram
        val outW = if (sp) w / 2 else w
        val outH = if (sp) h / 2 else h
        GLES30.glUseProgram(prog)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, linearTex)
        GLES31.glBindImageTexture(0, if (sp) spViewTex else viewTex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
        GLES31.glBindImageTexture(1, if (sp) spLogTex else logTex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
        setViewUniforms(prog, p)
        setColourNr(prog, meta, outW, outH, p)
        GLES30.glUniform2i(Gl.uniform(prog, "uRedOffset"), sensor.redOffset.first, sensor.redOffset.second)
        GLES30.glUniformMatrix3fv(Gl.uniform(prog, "uCamToTarget"), 1, false, ColorMath.toGlColumnMajor(camToTarget), 0)
        GLES30.glUniform1f(Gl.uniform(prog, "uExposure"), currentGain())
        GLES30.glUniform1i(Gl.uniform(prog, "uCurve"), p.shaderId)
        GLES31.glDispatchCompute((outW + 15) / 16, (outH + 15) / 16, 1)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
        stage(3)
    }

    // ---- Temporal colour noise reduction (see PipelineShaders.developBase) ----

    /** Two history images (read one, write the other, swap) at the develop output's size. */
    private val history = IntArray(2)
    private var historyW = 0
    private var historyH = 0
    private var historyRead = 0
    private var historyFresh = true
    private var historyProfile: LogProfile? = null
    private var historyOnLog = false
    /** 1×1 stand-in bound while colour NR is off, so the shader never sees an empty unit. */
    private var noHistory = 0
    /** Block colours (PipelineShaders.coarseColour) of this frame and the previous one, swapped each frame. */
    private val coarse = IntArray(2)
    private var coarseNow = 0

    private fun updateCoarseColour() {
        if (coarse[0] == 0) {
            for (i in 0..1) coarse[i] = Gl.texture2D(GLES30.GL_RGBA16F, w / 4, h / 4, linear = true)
            historyFresh = true // nothing to compare the first block colours with
        }
        coarseNow = 1 - coarseNow
        GLES30.glUseProgram(coarseProgram)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, linearTex)
        GLES31.glBindImageTexture(0, coarse[coarseNow], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
        GLES30.glUniform2i(Gl.uniform(coarseProgram, "uRedOffset"), sensor.redOffset.first, sensor.redOffset.second)
        GLES31.glDispatchCompute((w / 4 + 15) / 16, (h / 4 + 15) / 16, 1)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
    }

    private fun setColourNr(prog: Int, meta: FrameMeta?, outW: Int, outH: Int, p: LogProfile) {
        if (cleanup < 2) {
            releaseHistory()
            for (unit in 2..4) Gl.bindTexture(unit, GLES30.GL_TEXTURE_2D, noHistory)
            GLES31.glBindImageTexture(2, noHistory, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
            GLES30.glUniform1i(Gl.uniform(prog, "uTnr"), 0)
            GLES30.glUniform1i(Gl.uniform(prog, "uTnrOnLog"), 0)
            return
        }
        if (historyW != outW || historyH != outH) {
            releaseHistory()
            for (i in 0..1) history[i] = Gl.texture2D(GLES30.GL_RGBA16F, outW, outH, linear = false)
            historyW = outW
            historyH = outH
            historyFresh = true
        }
        // The history holds the colour of what's recorded: the clean log when recording log,
        // else the view (preview, baked recordings). Another target or profile: start over.
        val onLog = recording?.bakeLut == false
        if (historyProfile != p || historyOnLog != onLog) {
            historyProfile = p
            historyOnLog = onLog
            historyFresh = true
        }
        GLES30.glUniform1i(Gl.uniform(prog, "uTnrOnLog"), if (onLog) 1 else 0)
        Gl.bindTexture(2, GLES30.GL_TEXTURE_2D, history[historyRead])
        Gl.bindTexture(3, GLES30.GL_TEXTURE_2D, coarse[coarseNow])
        Gl.bindTexture(4, GLES30.GL_TEXTURE_2D, coarse[1 - coarseNow])
        GLES31.glBindImageTexture(2, history[1 - historyRead], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
        GLES30.glUniform1i(Gl.uniform(prog, "uTnr"), if (historyFresh) 2 else 1)
        historyFresh = false
        historyRead = 1 - historyRead
        GLES30.glUniform1f(Gl.uniform(prog, "uTnrAlpha"), if (cleanup >= 3) TNR_STRONG else TNR_NORMAL)
        // Sensor noise for normalised RAW (S·x + O), carried through the prep pass's white
        // balance and shading gains (~1.8 on average: variance scales by gain × S and gain² × O).
        val (ns, no) = meta?.noise?.let { it[0] to it[1] } ?: fallbackNoise(meta?.iso ?: 400)
        GLES30.glUniform2f(Gl.uniform(prog, "uNoise"), ns * NR_GAIN, no * NR_GAIN * NR_GAIN)
    }

    /** For a lens that doesn't report SENSOR_NOISE_PROFILE: roughly the X14 main camera's, by ISO. */
    private fun fallbackNoise(iso: Int): Pair<Float, Float> = (iso * 6.6e-7f) to (iso * iso * 1.2e-12f)

    private fun releaseHistory() {
        if (coarse[0] != 0) {
            GLES30.glDeleteTextures(2, coarse, 0)
            coarse.fill(0)
        }
        if (historyW == 0) return
        GLES30.glDeleteTextures(2, history, 0)
        historyW = 0
        historyH = 0
    }

    /**
     * Binds the LUT (texture unit 1) and sets the view half's uniforms (PipelineShaders.viewCommon)
     * for [prog], with our log profile [p]: the baked view LUT when one matches the settings, else
     * the source LUT with eDR / conversion / strength done per pixel (while a bake catches up).
     */
    private fun setViewUniforms(prog: Int, p: LogProfile) {
        pendingBaked?.let { (key, lut) ->
            pendingBaked = null
            bakedTex = uploadLutTexture(lut, bakedTex)
            bakedSize = lut.size
            bakedKey = key
        }
        val key = currentViewKey()?.takeIf { bakeViewLut }
        val baked = key != null && key == bakedKey
        if (key != null && !baked) requestBake(key)
        Gl.bindTexture(1, GLES30.GL_TEXTURE_3D, if (baked) bakedTex else lutTex)
        // Writing the full-size log image costs ~100 MB of memory traffic a frame; skip it unless used.
        GLES30.glUniform1i(Gl.uniform(prog, "uWriteLog"), if (recording?.bakeLut == false || pendingLogCapture != null) 1 else 0)
        GLES30.glUniform1i(Gl.uniform(prog, "uUseLut"), if (lutEnabled) 1 else 0)
        if (baked) {
            GLES30.glUniform1i(Gl.uniform(prog, "uLutConvert"), 0)
            GLES30.glUniform1i(Gl.uniform(prog, "uTone"), 0)
            GLES30.glUniform1f(Gl.uniform(prog, "uLutSize"), bakedSize.toFloat())
            GLES30.glUniform1f(Gl.uniform(prog, "uLutStrength"), 1f)
            GLES30.glUniform1f(Gl.uniform(prog, "uSaturation"), saturation)
            GLES30.glUniform1f(Gl.uniform(prog, "uVibrance"), vibrance)
            return
        }
        val lutIn = lutInput?.takeIf { it != p }
        GLES30.glUniform1i(Gl.uniform(prog, "uLutConvert"), if (lutIn != null) 1 else 0)
        if (lutIn != null) {
            GLES30.glUniform1i(Gl.uniform(prog, "uLutCurve"), lutIn.shaderId)
            GLES30.glUniformMatrix3fv(Gl.uniform(prog, "uLutGamut"), 1, false,
                ColorMath.toGlColumnMajor(ColorMath.convert(p.primaries, lutIn.primaries)), 0)
        }
        val toneOn = abs(toneHighlights) > 0.001f || abs(toneShadows) > 0.001f
        GLES30.glUniform1i(Gl.uniform(prog, "uTone"), if (toneOn) 1 else 0)
        if (toneOn) {
            GLES30.glUniform1f(Gl.uniform(prog, "uToneHi"), toneHighlights)
            GLES30.glUniform1f(Gl.uniform(prog, "uToneLo"), toneShadows)
            val toXyz = ColorMath.rgbToXyz(p.primaries)
            GLES30.glUniform3f(Gl.uniform(prog, "uLumaW"), toXyz[3].toFloat(), toXyz[4].toFloat(), toXyz[5].toFloat())
        }
        GLES30.glUniform1f(Gl.uniform(prog, "uLutSize"), lutSize.toFloat())
        GLES30.glUniform1f(Gl.uniform(prog, "uLutStrength"), lutStrength)
        GLES30.glUniform1f(Gl.uniform(prog, "uSaturation"), saturation)
        GLES30.glUniform1f(Gl.uniform(prog, "uVibrance"), vibrance)
    }

    private fun drawToScreen() {
        // Letterbox the (possibly rotated) image into the view.
        val quarter = rotation % 2 == 1
        val imgAspect = if (quarter) h.toFloat() / w else w.toFloat() / h
        val viewAspect = viewW.toFloat() / viewH
        val (vw, vh) = if (viewAspect > imgAspect) (viewH * imgAspect).toInt() to viewH else viewW to (viewW / imgAspect).toInt()
        val sp = activeSuperpixel()
        val tex = if (sp) spViewTex else viewTex
        val letterbox = intArrayOf((viewW - vw) / 2, (viewH - vh) / 2, vw, vh)
        val rects = glassRects
        val glass = if (rects.isNotEmpty()) (backdrop ?: GlassBackdrop().also { backdrop = it }) else null
        val timingStart = if (glassTiming && glass == null) { GLES30.glFinish(); SystemClock.elapsedRealtimeNanos() } else 0L
        // The glass's offscreen passes go first so the window's render pass stays in one piece. While
        // recording, a fresh blur every third frame is plenty behind the controls.
        glass?.let {
            it.timing = glassTiming
            it.prepare(tex, if (sp) w / 2 else w, if (sp) h / 2 else h, rotation, mirror, letterbox, viewW, viewH,
                refresh = recording == null || frameCounter % 3 == 0L)
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glViewport(letterbox[0], letterbox[1], vw, vh)
        val across = if (quarter) (if (sp) h / 2 else h) else (if (sp) w / 2 else w)
        drawTexture(tex, rotation, peakingTexel = if (peaking) (if (sp) 2f else 1f) else 0f,
            smooth = beatsWithNoise(across.toFloat() / vw), mirror = mirror)
        drawFrameMask(letterbox[0], letterbox[1], vw, vh)
        glass?.drawShapes(rects, viewW, viewH)
        if (timingStart > 0) {
            GLES30.glFinish()
            screenNs += SystemClock.elapsedRealtimeNanos() - timingStart
            if (++screenFrames == 90) {
                android.util.Log.i("AuthRec", "screen draw without glass: %.2f ms a frame".format(screenNs / 90 / 1e6))
                screenFrames = 0
                screenNs = 0
            }
        }
    }

    private var screenFrames = 0
    private var screenNs = 0L

    private var maskProgram = 0

    /**
     * Darkens what a cropped frame leaves out (above and below its band of the image at
     * x, y, w, h on screen) and draws thin frame lines at its edges.
     */
    private fun drawFrameMask(x: Int, y: Int, w: Int, h: Int) {
        val keep = frameCrop
        if (keep >= 0.999f) return
        if (maskProgram == 0) maskProgram = Gl.renderProgram(PipelineShaders.displayVertex, PipelineShaders.solidFragment)
        // The band runs along the screen's long side unless the image is shown turned a quarter.
        val horizontal = rotation % 2 == 0
        val span = if (horizontal) h else w
        val bar = ((1f - keep) / 2f * span).roundToInt()
        GLES30.glUseProgram(maskProgram)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        val color = Gl.uniform(maskProgram, "uColor")
        fun fill(sx: Int, sy: Int, sw: Int, sh: Int, r: Float, g: Float, b: Float, a: Float) {
            GLES30.glScissor(sx, sy, sw, sh)
            GLES30.glUniform4f(color, r, g, b, a)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
        val line = 3
        if (horizontal) {
            fill(x, y, w, bar, 0f, 0f, 0f, 0.62f)
            fill(x, y + h - bar, w, bar, 0f, 0f, 0f, 0.62f)
            fill(x, y + bar, w, line, 1f, 1f, 1f, 0.55f)
            fill(x, y + h - bar - line, w, line, 1f, 1f, 1f, 0.55f)
        } else {
            fill(x, y, bar, h, 0f, 0f, 0f, 0.62f)
            fill(x + w - bar, y, bar, h, 0f, 0f, 0f, 0.62f)
            fill(x + bar, y, line, h, 1f, 1f, 1f, 0.55f)
            fill(x + w - bar - line, y, line, h, 1f, 1f, 1f, 0.55f)
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Debug (Recorder.debugBars): red, green, blue, white, 50 % grey across the top third of a [w]×[h] frame. */
    private fun drawTestBars(w: Int, h: Int) {
        if (maskProgram == 0) maskProgram = Gl.renderProgram(PipelineShaders.displayVertex, PipelineShaders.solidFragment)
        GLES30.glUseProgram(maskProgram)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        val colours = arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f),
            floatArrayOf(1f, 1f, 1f), floatArrayOf(0.5f, 0.5f, 0.5f))
        val bw = w / colours.size
        for ((i, c) in colours.withIndex()) {
            GLES30.glScissor(i * bw, h - h / 3, bw, h / 3)
            GLES30.glUniform4f(Gl.uniform(maskProgram, "uColor"), c[0], c[1], c[2], 1f)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    }

    /** Mid-recording the mode is fixed by what the encoder was set up for; between recordings see [previewFullRes]. */
    private fun activeSuperpixel() = recording?.superpixel ?: (superpixel || !previewFullRes)

    /**
     * Whether drawing at [scale] source pixels per screen pixel makes noise beat. Bilinear
     * sampling averages two texels at some output pixels and copies one at others; when the scale
     * is close to a whole number that phase drifts slowly, so the noise's strength rises and falls
     * in bands: the front camera's 1632-px preview in a 1600-px box (×1.02) showed a grid every
     * ~50 px. Such scales get the smoothing filter instead (a slight blur, the same everywhere).
     */
    private fun beatsWithNoise(scale: Float): Boolean {
        val off = abs(scale - scale.roundToInt())
        return scale < 3f && off > 0.005f && off < 0.2f
    }

    /**
     * [peakingTexel] > 0 enables focus peaking, sampling that many full-res pixels apart. [crop]:
     * see Recording. [smooth]: B-spline instead of bilinear sampling (see [beatsWithNoise]).
     */
    private fun drawTexture(tex: Int, rotationQuarterTurns: Int, peakingTexel: Float = 0f, crop: FloatArray? = null,
                            smooth: Boolean = false, mirror: Boolean = false) {
        GLES30.glUseProgram(displayProgram)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(Gl.uniform(displayProgram, "uImage"), 0)
        GLES30.glUniform1i(Gl.uniform(displayProgram, "uRotation"), rotationQuarterTurns)
        val c = crop ?: FULL_FRAME
        GLES30.glUniform4f(Gl.uniform(displayProgram, "uCrop"), c[0], c[1], c[2], c[3])
        GLES30.glUniform1i(Gl.uniform(displayProgram, "uPeaking"), if (peakingTexel > 0) 1 else 0)
        GLES30.glUniform1i(Gl.uniform(displayProgram, "uSmooth"), if (smooth) 1 else 0)
        GLES30.glUniform1i(Gl.uniform(displayProgram, "uMirror"), if (mirror) 1 else 0)
        GLES30.glUniform2f(Gl.uniform(displayProgram, "uTexel"), peakingTexel / w, peakingTexel / h)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    /**
     * Grabs the clean log image of the next frame at [width] px wide (8-bit is plenty for
     * previewing a look). [onFrame] runs on the GL thread.
     */
    fun captureLogFrame(width: Int, onFrame: (Bitmap) -> Unit) = post { pendingLogCapture = width to onFrame }

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
        lutTex = uploadLutTexture(lut, lutTex)
        lutSize = lut.size
    }

    /** [lut] as a new RGBA16F 3D texture (replacing [old], if any); returns the texture. */
    private fun uploadLutTexture(lut: CubeLut, old: Int): Int {
        if (old != 0) GLES30.glDeleteTextures(1, intArrayOf(old), 0)
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
        val tex = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, tex)
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_3D, 1, GLES30.GL_RGBA16F, n, n, n)
        GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_3D, 0, 0, 0, 0, n, n, n, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)
        for (param in intArrayOf(GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_TEXTURE_MAG_FILTER)) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, param, GLES30.GL_LINEAR)
        }
        for (param in intArrayOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R)) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, param, GLES30.GL_CLAMP_TO_EDGE)
        }
        return tex
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
        /** History weight of colour NR where nothing moved: "+ Colour" / "+ Colour+". */
        private const val TNR_NORMAL = 0.7f
        private const val TNR_STRONG = 0.85f
        /** Average white balance × shading gain between the RAW and the prep pass's output. */
        private const val NR_GAIN = 1.8f
        private val FULL_FRAME = floatArrayOf(0f, 0f, 1f, 1f)
    }

    private fun EglCore.swapWindow() = EGL14.eglSwapBuffers(display, windowSurface)
}
