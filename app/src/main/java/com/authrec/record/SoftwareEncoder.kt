package com.authrec.record

import android.media.Image
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * A CPU video encoder for Compress (x265, x264, SVT-AV1 through JNI). Frames go in as a decoder's
 * output Image (YUV 4:2:0, 8-bit or P010); access units come out in decode order, each with its
 * [meta]: pts, dts (µs) and 1 if it's a key frame.
 */
internal interface SoftwareEncoder : AutoCloseable {
    /** The MP4 track's format: codec, size, codec-specific data from the encoder's headers. */
    val trackFormat: MediaFormat
    val meta: LongArray

    /** Returns the size of an access unit written to [out], 0 if none came out for this frame. */
    fun encode(image: Image, ptsUs: Long, out: ByteBuffer): Int

    /** After the last frame: the next access unit the encoder still holds; 0 = all out. */
    fun drain(out: ByteBuffer): Int

    companion object {
        /** 16-bit samples are P010 (the value in the top 10 bits); else 8-bit. */
        fun sixteenBit(image: Image) = image.planes[0].pixelStride >= 2

        /** BT.709, limited range: what our recordings are, and what players expect of SDR video. */
        fun MediaFormat.sdrRec709() = apply {
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
    }
}
