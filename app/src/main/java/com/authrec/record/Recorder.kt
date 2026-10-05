package com.authrec.record

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

enum class VideoCodec(val label: String, val mime: String, val profile: Int, val tenBit: Boolean) {
    HEVC_10("HEVC 10-bit", MediaFormat.MIMETYPE_VIDEO_HEVC, CodecProfileLevel.HEVCProfileMain10, true),
    HEVC_8("HEVC 8-bit", MediaFormat.MIMETYPE_VIDEO_HEVC, CodecProfileLevel.HEVCProfileMain, false),
    AVC_8("H.264 8-bit", MediaFormat.MIMETYPE_VIDEO_AVC, CodecProfileLevel.AVCProfileHigh, false),
}

data class RecordConfig(
    val codec: VideoCodec,
    val bitrateMbps: Int,
    val fps: Int,
    val audio: Boolean,
    /** Clock of the camera's frame timestamps, so audio can be put on the same timeline. */
    val timestampsAreBoottime: Boolean,
)

/**
 * Hardware video encoder fed through an input [Surface] (the GL thread draws frames into it),
 * plus optional AAC audio from the camcorder mic, muxed to an MP4 in Movies/AuthRec via
 * MediaStore so it shows up in the gallery.
 *
 * Timeline: video pts = frame timestamp − first frame timestamp (the renderer reports the
 * first one through [onFirstVideoFrame]); audio is timestamped on the same clock and anything
 * captured before the first video frame is dropped, so both tracks start together.
 */
class Recorder(context: Context, val config: RecordConfig, width: Int, height: Int) {

    val inputSurface: Surface
    val uri: Uri

    private val resolver = context.contentResolver
    /** Video encoder callbacks and all muxer access run on this thread. */
    private val thread = HandlerThread("encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val videoCodec: MediaCodec
    private val pfd: ParcelFileDescriptor
    private val muxer: MediaMuxer
    private val audio: AudioCapture?

    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    /** Samples that arrive before every track's format is known (the muxer can't start yet). */
    private val pending = ArrayList<Triple<Boolean, ByteBuffer, MediaCodec.BufferInfo>>()
    private var videoDone = false
    private var audioDone = false
    private var finished = false
    private var onFinished: ((Result) -> Unit)? = null
    private var result: Result? = null

    @Volatile private var firstVideoNs = -1L

    var bytesWritten = 0L
        private set
    var framesWritten = 0
        private set

    data class Result(val uri: Uri, val frames: Int, val bytes: Long, val audio: Boolean, val error: String?)

    private val videoCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // surface input

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            videoTrack = muxer.addTrack(format)
            maybeStartMuxer()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (finished) return
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (!isConfig && info.size > 0) {
                writeSample(video = true, codec.getOutputBuffer(index)!!, info)
                framesWritten++
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                videoDone = true
                maybeFinish()
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "video encoder error", e)
            finish("video encoder error: ${e.diagnosticInfo}")
        }
    }

    init {
        val name = "AuthRec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AuthRec")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }) ?: error("MediaStore refused to create $name")
        pfd = resolver.openFileDescriptor(uri, "rw") ?: error("can't open $uri")
        muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first {
            it.isEncoder && it.isHardwareAccelerated && !it.isAlias && config.codec.mime in it.supportedTypes
        }
        val maxBitrate = info.getCapabilitiesForType(config.codec.mime).videoCapabilities?.bitrateRange?.upper ?: Int.MAX_VALUE
        val format = MediaFormat.createVideoFormat(config.codec.mime, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, (config.bitrateMbps * 1_000_000).coerceAtMost(maxBitrate))
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, config.codec.profile)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            // Tag as plain SDR Rec.709 so players don't apply HDR tone mapping to log footage.
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
        videoCodec = MediaCodec.createByCodecName(info.name)
        videoCodec.setCallback(videoCallback, handler)
        videoCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = videoCodec.createInputSurface()

        audio = if (config.audio) {
            runCatching { AudioCapture() }
                .onFailure { Log.w(TAG, "audio unavailable, recording video only", it) }
                .getOrNull()
        } else {
            null
        }
        if (audio == null) audioDone = true

        videoCodec.start()
        audio?.start()
        Log.i(TAG, "Recording ${config.codec.label} ${width}x$height @ ${config.fps} fps, ${config.bitrateMbps} Mbps " +
            "with ${info.name}, audio ${if (audio != null) "on" else "off"} → $name")
    }

    /** The renderer calls this with the sensor timestamp of the first frame it encodes. */
    fun onFirstVideoFrame(timestampNs: Long) {
        firstVideoNs = timestampNs
    }

    /** Call once the last frame has been drawn into [inputSurface]. */
    fun stop(onFinished: (Result) -> Unit) = handler.post {
        result?.let { // already ended by an encoder error
            onFinished(it)
            thread.quitSafely()
            return@post
        }
        this.onFinished = onFinished
        audio?.stop()
        runCatching { videoCodec.signalEndOfInputStream() }.onFailure { finish("signalEndOfInputStream: ${it.message}") }
        // Safety net in case an encoder never reports end of stream.
        handler.postDelayed({ finish("encoder didn't finish in time") }, 4000)
    }

    private fun maybeStartMuxer() {
        if (muxerStarted || videoTrack < 0 || (audio != null && audioTrack < 0)) return
        muxer.start()
        muxerStarted = true
        pending.forEach { (video, buf, info) -> writeSample(video, buf, info) }
        pending.clear()
    }

    private fun writeSample(video: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!muxerStarted) {
            // Keep a copy; the codec reuses its buffer once released.
            pending += Triple(video, copyOf(buf, info), MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) })
            return
        }
        muxer.writeSampleData(if (video) videoTrack else audioTrack, buf, info)
        bytesWritten += info.size
    }

    private fun maybeFinish() {
        if (videoDone && audioDone) finish(null)
    }

    private fun finish(error: String?) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        audio?.release()
        runCatching { videoCodec.stop() }
        videoCodec.release()
        inputSurface.release()
        val muxError = runCatching { if (muxerStarted) muxer.stop() }.exceptionOrNull()
        runCatching { muxer.release() }
        pfd.close()
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        val err = error ?: muxError?.let { "muxer: ${it.message}" }
        Log.i(TAG, "Recording finished: $framesWritten frames, ${bytesWritten / 1_000_000} MB${err?.let { ", error: $it" } ?: ""}")
        val r = Result(uri, framesWritten, bytesWritten, audio != null, err)
        result = r
        onFinished?.let {
            it(r)
            thread.quitSafely() // otherwise stop() still needs this thread to deliver the result
        }
    }

    /** Mic → AAC on its own thread; encoded packets are copied over to the muxer thread. */
    private inner class AudioCapture {
        private val sampleRate = 48_000
        private val channels = 2
        private val record: AudioRecord
        private val codec: MediaCodec
        @Volatile private var running = false
        private var worker: Thread? = null

        init {
            val channelMask = AudioFormat.CHANNEL_IN_STEREO
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            @SuppressLint("MissingPermission") // CameraActivity only enables audio with RECORD_AUDIO granted
            val r = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
            check(r.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
            record = r
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        fun start() {
            running = true
            codec.start()
            record.startRecording()
            worker = thread(name = "audio") { loop() }
        }

        fun stop() {
            running = false
        }

        fun release() {
            running = false
            worker?.join(1000)
            runCatching { record.stop() }
            record.release()
            runCatching { codec.stop() }
            codec.release()
        }

        private fun loop() {
            val bytesPerFrame = 2 * channels
            val chunk = ByteArray(4096)
            val ts = AudioTimestamp()
            val timebase = if (config.timestampsAreBoottime) AudioTimestamp.TIMEBASE_BOOTTIME else AudioTimestamp.TIMEBASE_MONOTONIC
            var framesRead = 0L
            val info = MediaCodec.BufferInfo()
            var eosQueued = false

            while (true) {
                if (!eosQueued) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n > 0) {
                        // Capture time of this chunk's first sample, on the camera's clock.
                        val startNs = if (record.getTimestamp(ts, timebase) == AudioRecord.SUCCESS) {
                            ts.nanoTime + (framesRead - ts.framePosition) * 1_000_000_000L / sampleRate
                        } else {
                            val now = if (config.timestampsAreBoottime) SystemClock.elapsedRealtimeNanos() else System.nanoTime()
                            now - (n / bytesPerFrame) * 1_000_000_000L / sampleRate
                        }
                        framesRead += n / bytesPerFrame
                        val first = firstVideoNs
                        if (first >= 0 && startNs >= first) queue(chunk, n, (startNs - first) / 1000, 0)
                    }
                    if (!running) {
                        queue(chunk, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosQueued = true
                    }
                }
                if (drain(info, if (eosQueued) 10_000 else 0)) break
            }
            handler.post {
                audioDone = true
                maybeFinish()
            }
        }

        private fun queue(data: ByteArray, size: Int, ptsUs: Long, flags: Int) {
            val idx = codec.dequeueInputBuffer(20_000)
            if (idx < 0) return // encoder backed up; dropping a chunk beats stalling the mic
            codec.getInputBuffer(idx)!!.apply { clear(); put(data, 0, size) }
            codec.queueInputBuffer(idx, 0, size, ptsUs, flags)
        }

        /** Moves finished AAC packets to the muxer thread; returns true at end of stream. */
        private fun drain(info: MediaCodec.BufferInfo, timeoutUs: Long): Boolean {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, timeoutUs)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        handler.post {
                            audioTrack = muxer.addTrack(format)
                            maybeStartMuxer()
                        }
                    }
                    idx < 0 -> return false
                    else -> {
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0) {
                            val copy = copyOf(codec.getOutputBuffer(idx)!!, info)
                            val ci = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                            handler.post { if (!finished) writeSample(video = false, copy, ci) }
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "AuthRec"

        private fun copyOf(buf: ByteBuffer, info: MediaCodec.BufferInfo): ByteBuffer {
            val src = buf.duplicate()
            src.position(info.offset)
            src.limit(info.offset + info.size)
            return ByteBuffer.allocateDirect(info.size).put(src).also { it.flip() }
        }
    }
}
