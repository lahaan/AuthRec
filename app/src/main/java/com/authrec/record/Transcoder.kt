package com.authrec.record

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.authrec.EventLog
import java.nio.ByteBuffer

/**
 * Re-encodes a recording smaller ("Compress" in the recordings list): the phone's hardware
 * decoder renders straight into the hardware encoder's input surface (no copies, no GPU work),
 * and the audio is copied as it is. The original stays.
 *
 * Desktop tools (DaVinci Resolve, HandBrake) get small files at high quality from slow software
 * encoders (x265/x264: exhaustive motion search, quality-targeted rate control, B-frames); a
 * phone's hardware encoder is built for real time and needs more bits for the same look. From our
 * 25–150 Mbps originals, a few times smaller still looks close. Asked for: variable bitrate,
 * 2 s between key frames, the encoder's highest complexity, not real-time.
 */
class Transcoder(
    private val context: Context,
    private val source: Uri,
    private val sourceName: String,
    private val target: Target,
    private val onProgress: (Float) -> Unit,
) {
    /** What to make: codec, bitrate, and a word for the file name ("medium"). */
    data class Target(val mime: String, val bitrate: Int, val tag: String, val tuning: Tuning = Tuning())

    /**
     * Encoder settings beyond the bitrate, to compare (adb). No B-frames by default: with them the
     * X14's file showed its first frame twice, so the whole clip ran a frame (33 ms) late against
     * the original and its sound. [rateMode]: a
     * MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_* or -1 for VBR where supported; [quality]
     * for CQ; [complexity] -1 = the encoder's highest; [priority] 0 real-time, 1 not, -1 unset.
     */
    data class Tuning(val rateMode: Int = -1, val bFrames: Int = 0, val gopSeconds: Float = 2f,
                      val priority: Int = 1, val complexity: Int = -1, val quality: Int = -1,
                      /** Quantiser bounds for every frame type (-1 = the encoder's own); min = max is constant quality. */
                      val qpMin: Int = -1, val qpMax: Int = -1)

    class Result(val uri: Uri?, val bytes: Long, val seconds: Float, val error: String?)

    @Volatile var cancelled = false

    /** Blocking; run it on a worker thread. */
    fun run(): Result {
        val started = SystemClock.elapsedRealtime()
        val resolver = context.contentResolver
        var video: MediaExtractor? = null
        var audio: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var surface: Surface? = null
        var outUri: Uri? = null
        var pfd: ParcelFileDescriptor? = null
        var muxer: MediaMuxer? = null
        var muxing = false
        var bytes = 0L
        var error: String? = null
        try {
            video = MediaExtractor().apply { setDataSource(context, source, null) }
            val vTrack = (0 until video.trackCount).first { video.getTrackFormat(it).mime().startsWith("video/") }
            video.selectTrack(vTrack)
            val inFormat = video.getTrackFormat(vTrack)
            audio = MediaExtractor().apply { setDataSource(context, source, null) }
            val aTrack = (0 until audio.trackCount).firstOrNull { audio.getTrackFormat(it).mime().startsWith("audio/") }
            aTrack?.let { audio.selectTrack(it) }
            val audioFormat = aTrack?.let { audio.getTrackFormat(it) }

            val width = inFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = inFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val durationUs = inFormat.optLong(MediaFormat.KEY_DURATION).coerceAtLeast(1)
            val fps = inFormat.optInt(MediaFormat.KEY_FRAME_RATE, 30)
            val tenBit = isTenBit(inFormat)

            val (encName, outFormat) = encoderFor(target, width, height, fps, tenBit, inFormat)
            encoder = MediaCodec.createByCodecName(encName)
            // B-frames and the not-real-time hints are wishes: an encoder that refuses them gets the plain format.
            val tuning = target.tuning
            val withExtras = MediaFormat(outFormat).apply {
                if (tuning.bFrames > 0) setInteger(MediaFormat.KEY_MAX_B_FRAMES, tuning.bFrames)
                if (tuning.priority >= 0) setInteger(MediaFormat.KEY_PRIORITY, tuning.priority)
            }
            runCatching { encoder.configure(withExtras, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }.onFailure {
                Log.w(TAG, "encoder refused B-frames/priority, plain format", it)
                encoder.reset()
                encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            surface = encoder.createInputSurface()
            encoder.start()

            decoder = MediaCodec.createDecoderByType(inFormat.mime())
            inFormat.setInteger(MediaFormat.KEY_PRIORITY, 1)
            decoder.configure(inFormat, surface, null, 0)
            decoder.start()

            val name = sourceName.removeSuffix(".mp4") + "_" + target.tag + ".mp4"
            outUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AuthRec")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }) ?: error("MediaStore refused to create $name")
            pfd = resolver.openFileDescriptor(outUri, "rw") ?: error("can't open $outUri")
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (inFormat.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(inFormat.getInteger(MediaFormat.KEY_ROTATION))

            val info = MediaCodec.BufferInfo()
            val audioInfo = MediaCodec.BufferInfo()
            val audioBuf = ByteBuffer.allocateDirect(audioFormat?.optInt(MediaFormat.KEY_MAX_INPUT_SIZE, 0)?.takeIf { it > 0 } ?: (256 * 1024))
            var videoTrack = -1
            var audioTrack = -1
            var inputDone = false
            var decodeDone = false
            var encodeDone = false
            var audioDone = audioFormat == null
            var writtenUs = 0L
            // Encoders may hand one frame over in pieces (BUFFER_FLAG_PARTIAL_FRAME, as the recorder found).
            var piece: ByteBuffer? = null

            fun copyAudio(upToUs: Long) {
                while (!audioDone && audio.sampleTime in 0..upToUs) {
                    audioBuf.clear()
                    val n = audio.readSampleData(audioBuf, 0)
                    if (n < 0) {
                        audioDone = true
                        break
                    }
                    audioInfo.set(0, n, audio.sampleTime, if (audio.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(audioTrack, audioBuf, audioInfo)
                    bytes += n
                    if (!audio.advance()) audioDone = true
                }
                if (audio.sampleTime < 0) audioDone = true
            }

            while (!encodeDone) {
                if (cancelled) throw CancelledException()
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
                if (!decodeDone) {
                    val o = decoder.dequeueOutputBuffer(info, 5_000)
                    if (o >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (eos) decoder.releaseOutputBuffer(o, false)
                        else decoder.releaseOutputBuffer(o, info.presentationTimeUs * 1000)
                        if (eos) {
                            encoder.signalEndOfInputStream()
                            decodeDone = true
                        } else {
                            onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                    }
                }
                val e = encoder.dequeueOutputBuffer(info, if (decodeDone) 10_000 else 0)
                if (e == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrack = muxer.addTrack(encoder.outputFormat)
                    if (audioFormat != null) audioTrack = muxer.addTrack(audioFormat)
                    muxer.start()
                    muxing = true
                } else if (e >= 0) {
                    val buf = encoder.getOutputBuffer(e)!!
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0 && muxing) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        if (info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME != 0 || piece != null) {
                            piece = append(piece, buf)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME == 0) {
                            val whole = piece?.also { it.flip() } ?: buf
                            val out = MediaCodec.BufferInfo().apply {
                                set(whole.position(), whole.remaining(), info.presentationTimeUs,
                                    info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME.inv())
                            }
                            muxer.writeSampleData(videoTrack, whole, out)
                            bytes += out.size
                            writtenUs = maxOf(writtenUs, info.presentationTimeUs)
                            piece = null
                        }
                    }
                    encoder.releaseOutputBuffer(e, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encodeDone = true
                }
                if (muxing && audioTrack >= 0) copyAudio(writtenUs)
            }
            if (audioTrack >= 0) copyAudio(Long.MAX_VALUE)
            onProgress(1f)
        } catch (e: CancelledException) {
            error = "cancelled"
        } catch (e: Exception) {
            Log.w(TAG, "compress failed", e)
            error = e.message ?: e.javaClass.simpleName
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            surface?.release()
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
        EventLog.log("Compress $sourceName → ${target.tag} ${target.mime.substringAfter('/')} ${target.bitrate / 1_000_000} Mbps: " +
            (error ?: "${bytes / 1_000_000} MB in %.1f s".format(seconds)))
        return Result(if (error == null) outUri else null, bytes, seconds, error)
    }

    private class CancelledException : Exception()

    private fun append(acc: ByteBuffer?, data: ByteBuffer): ByteBuffer {
        val need = (acc?.position() ?: 0) + data.remaining()
        val out = if (acc != null && acc.capacity() >= need) acc else ByteBuffer.allocateDirect(maxOf(need, (acc?.capacity() ?: 0) * 2)).also { n ->
            acc?.let { it.flip(); n.put(it) }
        }
        out.put(data)
        return out
    }

    /** A hardware encoder for [t] and the format to configure it with. */
    private fun encoderFor(t: Target, w: Int, h: Int, fps: Int, tenBit: Boolean, src: MediaFormat): Pair<String, MediaFormat> {
        val info = hardwareEncoder(t.mime) ?: error("no hardware ${t.mime} encoder")
        val caps = info.getCapabilitiesForType(t.mime)
        val profile = when (t.mime) {
            MediaFormat.MIMETYPE_VIDEO_HEVC -> if (tenBit) CodecProfileLevel.HEVCProfileMain10 else CodecProfileLevel.HEVCProfileMain
            MediaFormat.MIMETYPE_VIDEO_AV1 -> if (tenBit) CodecProfileLevel.AV1ProfileMain10 else CodecProfileLevel.AV1ProfileMain8
            else -> CodecProfileLevel.AVCProfileHigh
        }
        val enc = caps.encoderCapabilities
        if (!capsLogged) {
            capsLogged = true
            val modes = listOf("CQ" to 0, "VBR" to 1, "CBR" to 2, "CBR_FD" to 3).filter { enc?.isBitrateModeSupported(it.second) == true }.map { it.first }
            EventLog.log("Encoder ${info.name}: bitrate modes $modes, quality ${enc?.qualityRange}, complexity ${enc?.complexityRange}")
        }
        val tune = t.tuning
        val format = MediaFormat.createVideoFormat(t.mime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, t.bitrate.coerceAtMost(caps.videoCapabilities?.bitrateRange?.upper ?: Int.MAX_VALUE))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, tune.gopSeconds)
            if (caps.profileLevels.any { it.profile == profile }) setInteger(MediaFormat.KEY_PROFILE, profile)
            val mode = if (tune.rateMode >= 0) tune.rateMode else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            if (enc?.isBitrateModeSupported(mode) == true) setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
            if (mode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ && tune.quality >= 0) setInteger(MediaFormat.KEY_QUALITY, tune.quality)
            if (tune.qpMin >= 0) {
                setInteger(MediaFormat.KEY_VIDEO_QP_MIN, tune.qpMin)
                setInteger(MediaFormat.KEY_VIDEO_QP_I_MIN, tune.qpMin)
                setInteger(MediaFormat.KEY_VIDEO_QP_P_MIN, tune.qpMin)
            }
            if (tune.qpMax >= 0) {
                setInteger(MediaFormat.KEY_VIDEO_QP_MAX, tune.qpMax)
                setInteger(MediaFormat.KEY_VIDEO_QP_I_MAX, tune.qpMax)
                setInteger(MediaFormat.KEY_VIDEO_QP_P_MAX, tune.qpMax)
            }
            enc?.complexityRange?.takeIf { it.upper > it.lower }?.let { r ->
                setInteger(MediaFormat.KEY_COMPLEXITY, if (tune.complexity >= 0) tune.complexity.coerceIn(r.lower, r.upper) else r.upper)
            }
            // Same tags as the recording (plain SDR Rec.709), so players treat it alike.
            setInteger(MediaFormat.KEY_COLOR_STANDARD, src.optInt(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709))
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, src.optInt(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO))
            setInteger(MediaFormat.KEY_COLOR_RANGE, src.optInt(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED))
        }
        return info.name to format
    }

    /**
     * Sizes offered, in bits per pixel per frame: x265-class encoders deliver good 4K at ~0.05;
     * a phone's real-time encoder and noisy log footage need more. AV1 gets 0.7 of HEVC's.
     */
    enum class Preset(val bitsPerPixel: Double) { HIGH(0.12), MEDIUM(0.07), SMALL(0.04) }

    companion object {
        private const val TAG = "AuthRec"
        private var capsLogged = false

        /** The bitrate for [preset] at this size and frame rate, never above 0.8 of the source's. */
        fun targetFor(preset: Preset, mime: String, width: Int, height: Int, fps: Int, sourceBitrate: Int): Target {
            var bits = preset.bitsPerPixel * width * height * fps * (if (mime == MediaFormat.MIMETYPE_VIDEO_AV1) 0.7 else 1.0)
            if (sourceBitrate > 0) bits = minOf(bits, sourceBitrate * 0.8)
            val mbps = (bits / 500_000).toInt().coerceAtLeast(2) * 500_000 // 0.5 Mbps steps
            return Target(mime, mbps, preset.name.lowercase())
        }

        fun hardwareEncoder(mime: String): MediaCodecInfo? = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            it.isEncoder && it.isHardwareAccelerated && !it.isAlias && mime in it.supportedTypes
        }

        /** HEVC Main10 / AV1 Main10 (or a 10-bit colour format) in the source. */
        fun isTenBit(f: MediaFormat): Boolean {
            val p = f.optInt(MediaFormat.KEY_PROFILE, -1)
            return when (f.mime()) {
                MediaFormat.MIMETYPE_VIDEO_HEVC -> p == CodecProfileLevel.HEVCProfileMain10 || p == CodecProfileLevel.HEVCProfileMain10HDR10
                MediaFormat.MIMETYPE_VIDEO_AV1 -> p == CodecProfileLevel.AV1ProfileMain10
                else -> false
            }
        }

        private fun MediaFormat.mime() = getString(MediaFormat.KEY_MIME) ?: ""
        private fun MediaFormat.optInt(key: String, default: Int) = if (containsKey(key)) getInteger(key) else default
        private fun MediaFormat.optLong(key: String) = if (containsKey(key)) getLong(key) else 0L
    }
}
