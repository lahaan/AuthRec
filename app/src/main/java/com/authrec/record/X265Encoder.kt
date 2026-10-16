package com.authrec.record

import android.media.Image
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.authrec.record.SoftwareEncoder.Companion.sdrRec709
import java.nio.ByteBuffer

/**
 * x265 (software HEVC, Main10) through JNI (src/main/cpp/x265_jni.cpp). Not thread-safe: one
 * compression owns one encoder. x265 is GPL-2.0-or-later; AuthRec is GPL-3.0 because of it.
 *
 * [preset]: x265's speed/size trade-off ("veryfast" … "medium"); [crf]: the quality target
 * (lower = better and bigger; ~18 looks like the source, 28 clearly softer); [maxKbps]: a
 * ceiling on the bitrate the quality target may need (0 = none); [keyint]: frames between key
 * frames. Colour description as the source's (ISO/IEC 23091-2 codes).
 */
internal class X265Encoder(
    private val width: Int, private val height: Int, private val fpsNum: Int, fpsDen: Int,
    preset: String, crf: Float, maxKbps: Int, keyint: Int, bframes: Int,
    primaries: Int = 1, transfer: Int = 1, matrix: Int = 1, fullRange: Boolean = false,
) : SoftwareEncoder {
    private var handle = nativeOpen(width, height, fpsNum, fpsDen, preset, crf, maxKbps, keyint, bframes, primaries, transfer, matrix, fullRange)
        .also { check(it != 0L) { "x265 refused the settings" } }

    /** VPS/SPS/PPS, Annex B: the track's csd-0. */
    val headers: ByteArray = nativeHeaders(handle) ?: run {
        close() // the caller never gets an object to close
        error("x265 gave no headers")
    }

    override val meta = LongArray(3)

    override val trackFormat: MediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
        setByteBuffer("csd-0", ByteBuffer.wrap(headers))
        setInteger(MediaFormat.KEY_FRAME_RATE, fpsNum)
        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
        sdrRec709()
    }

    /**
     * Encodes [image] (a decoder's YUV 4:2:0 output, 8-bit or P010) with presentation time
     * [ptsUs]; returns the size of an access unit written to [out] (0 = none yet: x265 holds a
     * few frames for look-ahead).
     */
    override fun encode(image: Image, ptsUs: Long, out: ByteBuffer): Int {
        val p = image.planes
        val sixteen = p[0].pixelStride >= 2
        val size = nativeEncode(handle,
            p[0].buffer, p[0].rowStride, p[0].pixelStride,
            p[1].buffer, p[1].rowStride, p[1].pixelStride,
            p[2].buffer, p[2].rowStride, p[2].pixelStride,
            if (sixteen) 2 else 1, if (sixteen) 6 else 0, ptsUs, out, meta)
        check(size >= 0) { "x265 output didn't fit (${out.capacity()} bytes)" }
        return size
    }

    /** Takes the next frame x265 still holds after the last input; 0 = all out. */
    override fun drain(out: ByteBuffer): Int {
        val size = nativeEncode(handle, null, 0, 0, null, 0, 0, null, 0, 0, 2, 6, 0, out, meta)
        check(size >= 0) { "x265 output didn't fit (${out.capacity()} bytes)" }
        return size
    }

    override fun close() {
        if (handle != 0L) nativeClose(handle)
        handle = 0
    }

    private companion object {
        init {
            System.loadLibrary("authrec_codecs")
        }

        @JvmStatic private external fun nativeOpen(width: Int, height: Int, fpsNum: Int, fpsDen: Int, preset: String, crf: Float,
                                                   maxKbps: Int, keyint: Int, bframes: Int, primaries: Int, transfer: Int, matrix: Int,
                                                   fullRange: Boolean): Long
        @JvmStatic private external fun nativeHeaders(handle: Long): ByteArray?
        @JvmStatic private external fun nativeEncode(handle: Long,
                                                     y: ByteBuffer?, yRow: Int, yPix: Int,
                                                     u: ByteBuffer?, uRow: Int, uPix: Int,
                                                     v: ByteBuffer?, vRow: Int, vPix: Int,
                                                     bytesPerSample: Int, shift: Int, pts: Long,
                                                     out: ByteBuffer, meta: LongArray): Int
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
