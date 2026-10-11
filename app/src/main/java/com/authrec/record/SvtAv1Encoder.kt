package com.authrec.record

import android.media.Image
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.authrec.record.SoftwareEncoder.Companion.sdrRec709
import java.nio.ByteBuffer

/**
 * SVT-AV1 (software AV1, 10-bit Main profile) through JNI (src/main/cpp/svtav1_jni.cpp): the
 * smallest files, but only newer phones, browsers and editors play AV1, so it's never the default.
 * BSD-3-Clause-Clear with the AOM patent licence. [preset]: SVT's speed level ("0" slowest …
 * "13" fastest); [crf]: its quality target (1–63, a scale of its own: ~34 looks like x265 at 24);
 * [maxKbps] as [X265Encoder] has it.
 */
internal class SvtAv1Encoder(
    private val width: Int, private val height: Int, fps: Int,
    preset: String, crf: Float, maxKbps: Int, keyint: Int,
) : SoftwareEncoder {
    private var handle = nativeOpen(width, height, fps, 1, preset.toIntOrNull() ?: 10, Math.round(crf), maxKbps, keyint)
        .also { check(it != 0L) { "SVT-AV1 refused the settings" } }

    override val meta = LongArray(3)

    /** csd-0 is the av1C record MP4 wants (the sequence header with a short summary in front). */
    override val trackFormat: MediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AV1, width, height).apply {
        setByteBuffer("csd-0", ByteBuffer.wrap(nativeHeaders(handle) ?: error("SVT-AV1 gave no sequence header")))
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10)
        sdrRec709()
    }

    /** Returns the size of a temporal unit (one shown frame) written to [out]; 0 = none yet. */
    override fun encode(image: Image, ptsUs: Long, out: ByteBuffer): Int {
        val p = image.planes
        val sixteen = SoftwareEncoder.sixteenBit(image)
        return checked(nativeEncode(handle,
            p[0].buffer, p[0].rowStride, p[0].pixelStride,
            p[1].buffer, p[1].rowStride, p[1].pixelStride,
            p[2].buffer, p[2].rowStride, p[2].pixelStride,
            if (sixteen) 2 else 1, if (sixteen) 6 else 0, ptsUs, out, meta), out)
    }

    override fun drain(out: ByteBuffer): Int =
        checked(nativeEncode(handle, null, 0, 0, null, 0, 0, null, 0, 0, 2, 6, 0, out, meta), out)

    private fun checked(size: Int, out: ByteBuffer): Int {
        check(size != -1) { "SVT-AV1 output didn't fit (${out.capacity()} bytes)" }
        check(size >= 0) { "SVT-AV1 encode error" }
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

        @JvmStatic external fun nativeOpen(width: Int, height: Int, fpsNum: Int, fpsDen: Int, preset: Int, crf: Int, maxKbps: Int, keyint: Int): Long
        @JvmStatic external fun nativeHeaders(handle: Long): ByteArray?
        @JvmStatic external fun nativeEncode(handle: Long,
                                             y: ByteBuffer?, yRow: Int, yPix: Int,
                                             u: ByteBuffer?, uRow: Int, uPix: Int,
                                             v: ByteBuffer?, vRow: Int, vPix: Int,
                                             bytesPerSample: Int, shift: Int, pts: Long,
                                             out: ByteBuffer, meta: LongArray): Int
        @JvmStatic external fun nativeClose(handle: Long)
    }
}
