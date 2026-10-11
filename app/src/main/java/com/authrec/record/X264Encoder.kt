package com.authrec.record

import android.media.Image
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.authrec.record.SoftwareEncoder.Companion.sdrRec709
import java.nio.ByteBuffer

/**
 * x264 (software H.264, 8-bit High profile) through JNI (src/main/cpp/x264_jni.cpp): the format
 * every phone, browser, editor and old laptop plays. 10-bit sources are rounded to 8 bits.
 * GPL-2.0-or-later. [preset], [crf], [maxKbps], [keyint] as [X265Encoder] has them (its CRF runs about 5 lower than
 * x265's for the same look).
 */
internal class X264Encoder(
    private val width: Int, private val height: Int, fps: Int,
    preset: String, crf: Float, maxKbps: Int, keyint: Int,
) : SoftwareEncoder {
    private var handle = nativeOpen(width, height, fps, 1, preset, crf, maxKbps, keyint, 1, 1, 1, false)
        .also { check(it != 0L) { "x264 refused the settings" } }

    override val meta = LongArray(3)

    override val trackFormat: MediaFormat = run {
        val headers = nativeHeaders(handle) ?: error("x264 gave no headers")
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(headers[0] ?: error("no SPS")))
            setByteBuffer("csd-1", ByteBuffer.wrap(headers[1] ?: error("no PPS")))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            sdrRec709()
        }
    }

    override fun encode(image: Image, ptsUs: Long, out: ByteBuffer): Int {
        val p = image.planes
        val sixteen = SoftwareEncoder.sixteenBit(image)
        val size = nativeEncode(handle,
            p[0].buffer, p[0].rowStride, p[0].pixelStride,
            p[1].buffer, p[1].rowStride, p[1].pixelStride,
            p[2].buffer, p[2].rowStride, p[2].pixelStride,
            if (sixteen) 2 else 1, if (sixteen) 6 else 0, ptsUs, out, meta)
        check(size >= 0) { "x264 output didn't fit (${out.capacity()} bytes)" }
        return size
    }

    override fun drain(out: ByteBuffer): Int {
        val size = nativeEncode(handle, null, 0, 0, null, 0, 0, null, 0, 0, 2, 6, 0, out, meta)
        check(size >= 0) { "x264 output didn't fit (${out.capacity()} bytes)" }
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

        @JvmStatic external fun nativeOpen(width: Int, height: Int, fpsNum: Int, fpsDen: Int, preset: String, crf: Float,
                                           maxKbps: Int, keyint: Int, primaries: Int, transfer: Int, matrix: Int, fullRange: Boolean): Long
        @JvmStatic external fun nativeHeaders(handle: Long): Array<ByteArray?>?
        @JvmStatic external fun nativeEncode(handle: Long,
                                             y: ByteBuffer?, yRow: Int, yPix: Int,
                                             u: ByteBuffer?, uRow: Int, uPix: Int,
                                             v: ByteBuffer?, vRow: Int, vPix: Int,
                                             bytesPerSample: Int, shift: Int, pts: Long,
                                             out: ByteBuffer, meta: LongArray): Int
        @JvmStatic external fun nativeClose(handle: Long)
    }
}
