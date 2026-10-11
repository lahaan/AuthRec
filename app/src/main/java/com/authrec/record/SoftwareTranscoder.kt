package com.authrec.record

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import com.authrec.EventLog
import java.nio.ByteBuffer

/**
 * "Compress": re-encodes a recording with x265 ([X265Encoder]), the encoder desktop tools like
 * HandBrake use. The phone's hardware decoder hands over each frame (P010 for our 10-bit
 * recordings), x265 encodes it on the CPU, the MP4 gets the result plus the original audio as it
 * is. The original stays.
 *
 * Why software: the X14's hardware HEVC encoder needed 73 % of the size for the quality x265
 * reaches at ~10–16 % on grainy footage (VMAF against the original, owner's clips 2026-10-10).
 * The price is time and heat: x265 keeps every CPU core busy, so the job pauses itself while the
 * battery is at [HOT_C] or more and carries on at [COOL_C] (PowerKeeper kills apps at ~48 °C).
 */
class SoftwareTranscoder(
    private val context: Context,
    private val source: Uri,
    private val sourceName: String,
    private val settings: Settings,
    private val onProgress: (Progress) -> Unit,
) {
    /**
     * [preset]: x265's effort ("veryfast" … "medium"); [crf]: quality target (lower = better,
     * bigger); [tag]: for the file name; [bframes]: -1 = the preset's.
     */
    data class Settings(val preset: String, val crf: Float, val tag: String, val bframes: Int = -1)

    data class Progress(val fraction: Float, val cooling: Boolean)

    class Result(val uri: Uri?, val bytes: Long, val seconds: Float, val error: String?)

    @Volatile var cancelled = false

    fun run(): Result {
        val started = SystemClock.elapsedRealtime()
        val resolver = context.contentResolver
        var video: MediaExtractor? = null
        var audio: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: X265Encoder? = null
        var outUri: Uri? = null
        var pfd: ParcelFileDescriptor? = null
        var muxer: MediaMuxer? = null
        var muxing = false
        var bytes = 0L
        var frames = 0
        var error: String? = null
        try {
            video = MediaExtractor().apply { setDataSource(context, source, null) }
            val vTrack = (0 until video.trackCount).first { video.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            video.selectTrack(vTrack)
            val inFormat = video.getTrackFormat(vTrack)
            audio = MediaExtractor().apply { setDataSource(context, source, null) }
            val aTrack = (0 until audio.trackCount).firstOrNull { audio.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            aTrack?.let { audio.selectTrack(it) }
            val audioFormat = aTrack?.let { audio.getTrackFormat(it) }

            val width = inFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = inFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 1L
            val fps = if (inFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) inFormat.getInteger(MediaFormat.KEY_FRAME_RATE) else 30

            // Decoded frames as YUV we can read: P010 for 10-bit sources where the decoder offers it.
            decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            val tenBit = Transcoder.isTenBit(inFormat)
            val p010 = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
            val canP010 = tenBit && decoder.codecInfo.getCapabilitiesForType(inFormat.getString(MediaFormat.KEY_MIME)!!).colorFormats.contains(p010)
            inFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, if (canP010) p010 else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            decoder.configure(inFormat, null, null, 0)
            decoder.start()

            encoder = X265Encoder(width, height, fps, 1, settings.preset, settings.crf, keyint = fps * 2, bframes = settings.bframes)

            val name = sourceName.removeSuffix(".mp4") + "_" + settings.tag + ".mp4"
            outUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AuthRec")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }) ?: error("MediaStore refused to create $name")
            pfd = resolver.openFileDescriptor(outUri, "rw") ?: error("can't open $outUri")
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(encoder.headers))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            val videoTrack = muxer.addTrack(outFormat)
            val audioTrack = audioFormat?.let { muxer.addTrack(it) } ?: -1
            muxer.start()
            muxing = true

            val out = ByteBuffer.allocateDirect(width * height * 3) // an I-frame never comes near raw size
            val info = MediaCodec.BufferInfo()
            val sample = MediaCodec.BufferInfo()
            val audioBuf = ByteBuffer.allocateDirect(256 * 1024)
            var writtenUs = 0L
            var audioDone = audioTrack < 0

            fun writeVideo(size: Int) {
                if (size <= 0) return
                out.position(0)
                out.limit(size)
                sample.set(0, size, encoder.meta[0], if (encoder.meta[2] == 1L) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(videoTrack, out, sample)
                out.clear()
                bytes += size
                frames++
                writtenUs = maxOf(writtenUs, encoder.meta[0])
            }

            fun copyAudio(upToUs: Long) {
                while (!audioDone && audio.sampleTime in 0..upToUs) {
                    audioBuf.clear()
                    val n = audio.readSampleData(audioBuf, 0)
                    if (n < 0) break
                    sample.set(0, n, audio.sampleTime, if (audio.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(audioTrack, audioBuf, sample)
                    bytes += n
                    if (!audio.advance()) break
                }
                if (audio.sampleTime < 0) audioDone = true
            }

            var inputDone = false
            var decodeDone = false
            var lastHeatCheck = 0L
            while (!decodeDone) {
                if (cancelled) throw CancelledException()
                val now = SystemClock.elapsedRealtime()
                if (now - lastHeatCheck > 2000) {
                    lastHeatCheck = now
                    coolDownIfHot(writtenUs.toFloat() / durationUs)
                }
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val n = video.readSampleData(decoder.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, n, video.sampleTime, 0)
                            video.advance()
                        }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, 5_000)
                if (o >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        decodeDone = true
                    } else {
                        decoder.getOutputImage(o)?.use { image -> writeVideo(encoder.encode(image, info.presentationTimeUs, out)) }
                        onProgress(Progress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f), false))
                    }
                    decoder.releaseOutputBuffer(o, false)
                    if (audioTrack >= 0) copyAudio(writtenUs)
                }
            }
            // The frames x265 still holds for its look-ahead.
            while (true) {
                if (cancelled) throw CancelledException()
                val size = encoder.drain(out)
                if (size == 0) break
                writeVideo(size)
            }
            if (audioTrack >= 0) copyAudio(Long.MAX_VALUE)
            onProgress(Progress(1f, false))
        } catch (e: CancelledException) {
            error = "cancelled"
        } catch (e: Exception) {
            Log.w(TAG, "compress failed", e)
            error = e.message ?: e.javaClass.simpleName
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.close() }
            video?.release()
            audio?.release()
            val muxError = runCatching { if (muxing) muxer?.stop() }.exceptionOrNull()
            runCatching { muxer?.release() }
            runCatching { pfd?.close() }
            if (error == null && muxError != null) error = "muxer: ${muxError.message}"
            outUri?.let { uri ->
                if (error == null) resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                else runCatching { resolver.delete(uri, null, null) }
            }
        }
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000f
        EventLog.log("Compress $sourceName → x265 ${settings.preset} crf ${settings.crf}: " +
            (error ?: "${bytes / 1_000_000} MB, $frames frames in ${"%.1f".format(seconds)} s"))
        return Result(if (error == null) outUri else null, bytes, seconds, error)
    }

    /** Waits while the battery is hot (x265 can't be slowed, only paused); cancelling still works. */
    private fun coolDownIfHot(fraction: Float) {
        if (batteryC() < HOT_C) return
        EventLog.log("Compress paused: battery ${"%.1f".format(batteryC())} °C")
        onProgress(Progress(fraction, true))
        while (batteryC() > COOL_C) {
            if (cancelled) throw CancelledException()
            Thread.sleep(2000)
        }
        onProgress(Progress(fraction, false))
    }

    private fun batteryC(): Float =
        (context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f

    private class CancelledException : Exception()

    companion object {
        private const val TAG = "AuthRec"
        /** Battery °C at which compressing pauses, and at which it carries on. */
        const val HOT_C = 44f
        const val COOL_C = 40f
    }
}
