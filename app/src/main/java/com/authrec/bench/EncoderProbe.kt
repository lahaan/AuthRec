package com.authrec.bench

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES30
import android.os.SystemClock
import android.view.Surface

/**
 * Lists the hardware H.264/HEVC encoders and their limits, then does short real encodes at
 * the AuthRec target modes through each input path we might use. 10-bit in particular works
 * on some vendor encoders only through one specific path, so we try them all.
 */
object EncoderProbe {

    private const val TEST_FRAMES = 60
    private const val TEST_FPS = 30
    private const val EGL_RECORDABLE_ANDROID = 0x3142

    private enum class Input(val label: String) {
        SURFACE_RGBA8("surface, 8-bit GL"),
        SURFACE_RGBA1010102("surface, 10-bit GL"),
        BUFFER_P010("buffers, P010"),
    }

    private data class Mode(val name: String, val w: Int, val h: Int)

    private val MODES = listOf(
        Mode("Open gate", Targets.OPEN_GATE_W, Targets.OPEN_GATE_H),
        Mode("16:9 UHD", 3840, 2160),
        Mode("Superpixel", Targets.SUPERPIXEL_W, Targets.SUPERPIXEL_H),
    )

    fun run(r: Report) {
        r.section("Encoders")
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.isHardwareAccelerated && !it.isAlias }

        val hevc = encoders.firstOrNull { MediaFormat.MIMETYPE_VIDEO_HEVC in it.supportedTypes }
        val avc = encoders.firstOrNull { MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes }

        hevc?.let { describe(r, it, MediaFormat.MIMETYPE_VIDEO_HEVC) }
            ?: r.verdict(Report.Verdict.FAIL, "No hardware HEVC encoder")
        avc?.let { describe(r, it, MediaFormat.MIMETYPE_VIDEO_AVC) }
            ?: r.verdict(Report.Verdict.FAIL, "No hardware H.264 encoder")

        r.line()
        r.line("Test encodes ($TEST_FRAMES frames, ${Targets.MAX_BITRATE / 1_000_000} Mbps target):")
        if (hevc != null) {
            val mime = MediaFormat.MIMETYPE_VIDEO_HEVC
            for (mode in MODES) {
                encode(r, hevc, mime, mode, CodecProfileLevel.HEVCProfileMain, "Main 8-bit", Input.SURFACE_RGBA8)
                for (input in Input.entries) {
                    encode(r, hevc, mime, mode, CodecProfileLevel.HEVCProfileMain10, "Main10", input)
                }
            }
        }
        if (avc != null) {
            val mime = MediaFormat.MIMETYPE_VIDEO_AVC
            for (mode in MODES) encode(r, avc, mime, mode, CodecProfileLevel.AVCProfileHigh, "High 8-bit", Input.SURFACE_RGBA8)
        }
    }

    private fun describe(r: Report, info: MediaCodecInfo, mime: String) {
        val caps = info.getCapabilitiesForType(mime)
        val vc = caps.videoCapabilities ?: return
        r.line()
        r.line("${if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) "HEVC" else "H.264"}: ${info.name}")
        r.kv("Profiles", caps.profileLevels.map { profileName(mime, it.profile) }.distinct().joinToString())
        r.kv("Max size", "${vc.supportedWidths.upper}×${vc.supportedHeights.upper}")
        r.kv("Bitrate range", "${vc.bitrateRange.lower / 1000} kbps – ${vc.bitrateRange.upper / 1_000_000} Mbps")
        r.kv("Input formats", caps.colorFormats.joinToString { colorFormatName(it) })
        val enc = caps.encoderCapabilities ?: return
        r.kv("Bitrate modes", listOfNotNull(
            "CBR".takeIf { enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) },
            "VBR".takeIf { enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) },
            "CQ".takeIf { enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) },
        ).joinToString())
        vc.supportedPerformancePoints?.takeIf { it.isNotEmpty() }?.let { r.kv("Performance points", it.joinToString()) }
        for (mode in MODES) {
            val sizeOk = vc.isSizeSupported(mode.w, mode.h)
            val rates = if (sizeOk) runCatching { vc.getSupportedFrameRatesFor(mode.w, mode.h) }.getOrNull() else null
            r.kv("${mode.name} ${mode.w}×${mode.h}", if (rates != null) "up to %.0f fps advertised".format(rates.upper) else "size not supported")
        }
    }

    private fun encode(r: Report, info: MediaCodecInfo, mime: String, mode: Mode, profile: Int, profileLabel: String, input: Input) {
        val codecLabel = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) "HEVC" else "H.264"
        val label = "$codecLabel $profileLabel ${mode.name} ${mode.w}×${mode.h} (${input.label})"
        val caps = info.getCapabilitiesForType(mime)
        if (input == Input.BUFFER_P010 && CodecCapabilities.COLOR_FormatYUVP010 !in caps.colorFormats) {
            r.verdict(Report.Verdict.INFO, "$label: encoder doesn't accept P010 buffers")
            return
        }

        val format = MediaFormat.createVideoFormat(mime, mode.w, mode.h).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, Targets.MAX_BITRATE.coerceAtMost(caps.videoCapabilities?.bitrateRange?.upper ?: Targets.MAX_BITRATE))
            setInteger(MediaFormat.KEY_FRAME_RATE, TEST_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, profile)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, when (input) {
                Input.BUFFER_P010 -> CodecCapabilities.COLOR_FormatYUVP010
                else -> CodecCapabilities.COLOR_FormatSurface
            })
            // Plain Rec.709 SDR tagging; log footage is SDR-range data until a LUT says otherwise.
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }

        val codec = MediaCodec.createByCodecName(info.name)
        var egl: EncoderEgl? = null
        try {
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                r.verdict(Report.Verdict.FAIL, "$label: configure rejected (${e.javaClass.simpleName})")
                return
            }
            val inputSurface = if (input == Input.BUFFER_P010) null else codec.createInputSurface()
            codec.start()
            if (inputSurface != null) {
                egl = EncoderEgl.create(inputSurface, tenBit = input == Input.SURFACE_RGBA1010102)
                    ?: run {
                        r.verdict(Report.Verdict.FAIL, "$label: no matching EGL config for the encoder surface")
                        return
                    }
            }

            val stats = Stats()
            val t0 = SystemClock.elapsedRealtimeNanos()
            for (i in 0 until TEST_FRAMES) {
                val ptsUs = i * 1_000_000L / TEST_FPS
                if (egl != null) {
                    egl.drawFrame(i, ptsUs * 1000)
                } else {
                    queueP010(codec, mode, ptsUs, eos = i == TEST_FRAMES - 1)
                }
                drain(codec, stats, timeoutUs = 0)
            }
            if (egl != null) codec.signalEndOfInputStream()
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (!stats.eos && SystemClock.elapsedRealtime() < deadline) drain(codec, stats, timeoutUs = 10_000)
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6

            val throughput = stats.frames * 1000.0 / elapsedMs
            val mbps = stats.bytes * 8.0 / (stats.frames.coerceAtLeast(1).toDouble() / TEST_FPS) / 1e6
            val outProfile = stats.outputFormat?.takeIf { it.containsKey(MediaFormat.KEY_PROFILE) }
                ?.getInteger(MediaFormat.KEY_PROFILE)?.let { profileName(mime, it) } ?: "not reported"
            val detail = "${stats.frames}/$TEST_FRAMES frames, %.0f fps throughput, ≈%.0f Mbps, output profile $outProfile"
                .format(throughput, mbps)
            val v = when {
                stats.frames < TEST_FRAMES * 0.9 -> Report.Verdict.FAIL
                throughput < TEST_FPS -> Report.Verdict.LIMITED
                profile == CodecProfileLevel.HEVCProfileMain10 && outProfile != "Main10" && outProfile != "not reported" -> Report.Verdict.LIMITED
                else -> Report.Verdict.PASS
            }
            r.verdict(v, "$label: $detail")
        } catch (e: Exception) {
            r.verdict(Report.Verdict.FAIL, "$label: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            egl?.release()
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private class Stats {
        var frames = 0
        var bytes = 0L
        var eos = false
        var outputFormat: MediaFormat? = null
    }

    private fun drain(codec: MediaCodec, stats: Stats, timeoutUs: Long) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> stats.outputFormat = codec.outputFormat
                idx < 0 -> return
                else -> {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        stats.frames++
                        stats.bytes += info.size
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        stats.eos = true
                        return
                    }
                }
            }
        }
    }

    /** Queues one P010 frame; the pixel content is irrelevant for a capability test. */
    private fun queueP010(codec: MediaCodec, mode: Mode, ptsUs: Long, eos: Boolean) {
        val idx = codec.dequeueInputBuffer(100_000)
        if (idx < 0) return
        val buf = codec.getInputBuffer(idx) ?: return
        val size = (mode.w * mode.h * 3).coerceAtMost(buf.capacity()) // 16-bit Y + 16-bit interleaved UV at half height
        codec.queueInputBuffer(idx, 0, size, ptsUs, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
    }

    private fun profileName(mime: String, profile: Int): String = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
        when (profile) {
            CodecProfileLevel.HEVCProfileMain -> "Main"
            CodecProfileLevel.HEVCProfileMain10 -> "Main10"
            CodecProfileLevel.HEVCProfileMain10HDR10 -> "Main10 HDR10"
            CodecProfileLevel.HEVCProfileMain10HDR10Plus -> "Main10 HDR10+"
            CodecProfileLevel.HEVCProfileMainStill -> "Main Still"
            else -> "0x${profile.toString(16)}"
        }
    } else {
        when (profile) {
            CodecProfileLevel.AVCProfileBaseline -> "Baseline"
            CodecProfileLevel.AVCProfileConstrainedBaseline -> "Constrained Baseline"
            CodecProfileLevel.AVCProfileMain -> "Main"
            CodecProfileLevel.AVCProfileHigh -> "High"
            CodecProfileLevel.AVCProfileConstrainedHigh -> "Constrained High"
            CodecProfileLevel.AVCProfileHigh10 -> "High10"
            else -> "0x${profile.toString(16)}"
        }
    }

    private fun colorFormatName(f: Int) = when (f) {
        CodecCapabilities.COLOR_FormatSurface -> "Surface"
        CodecCapabilities.COLOR_FormatYUV420Flexible -> "YUV420 flexible"
        CodecCapabilities.COLOR_FormatYUVP010 -> "P010"
        21 -> "NV12" // COLOR_FormatYUV420SemiPlanar (deprecated constant)
        19 -> "I420" // COLOR_FormatYUV420Planar (deprecated constant)
        CodecCapabilities.COLOR_Format32bitABGR2101010 -> "RGBA1010102"
        else -> "0x${f.toString(16)}"
    }

    /** EGL window surface on the encoder's input surface, drawn with plain GL clears. */
    private class EncoderEgl private constructor(
        private val display: android.opengl.EGLDisplay,
        private val context: android.opengl.EGLContext,
        private val surface: android.opengl.EGLSurface,
        private val inputSurface: Surface,
    ) {
        fun drawFrame(i: Int, ptsNs: Long) {
            val t = i / TEST_FRAMES.toFloat()
            GLES30.glClearColor(t, 0.5f, 1f - t, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            EGLExt.eglPresentationTimeANDROID(display, surface, ptsNs)
            EGL14.eglSwapBuffers(display, surface)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            inputSurface.release()
        }

        companion object {
            fun create(inputSurface: Surface, tenBit: Boolean): EncoderEgl? {
                val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)
                val bits = if (tenBit) intArrayOf(10, 10, 10, 2) else intArrayOf(8, 8, 8, 8)
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                EGL14.eglChooseConfig(display, intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RED_SIZE, bits[0], EGL14.EGL_GREEN_SIZE, bits[1],
                    EGL14.EGL_BLUE_SIZE, bits[2], EGL14.EGL_ALPHA_SIZE, bits[3],
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE,
                ), 0, configs, 0, 1, count, 0)
                if (count[0] == 0) {
                    EGL14.eglTerminate(display)
                    return null
                }
                val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                val surface = EGL14.eglCreateWindowSurface(display, configs[0], inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
                if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
                    EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                    return null
                }
                return EncoderEgl(display, context, surface, inputSurface)
            }
        }
    }
}
