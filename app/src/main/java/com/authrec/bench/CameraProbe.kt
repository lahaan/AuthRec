package com.authrec.bench

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT
import android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
import android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
import android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
import android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING
import android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Size
import kotlin.math.abs

/** The RAW stream the later stages test against. */
data class RawTarget(
    val cameraId: String,
    /** Set when [cameraId] is a physical lens that must be opened through this logical camera. */
    val logicalParentId: String?,
    val format: Int,
    val size: Size,
    val minFrameDurationNs: Long,
    val cfa: Int,
    val blackLevel: Int,
    val whiteLevel: Int,
    val manualSensor: Boolean,
) {
    val maxFps get() = if (minFrameDurationNs > 0) 1e9 / minFrameDurationNs else 0.0
    val formatName get() = rawFormatName(format)
}

fun rawFormatName(format: Int) = when (format) {
    ImageFormat.RAW_SENSOR -> "RAW_SENSOR (16-bit)"
    ImageFormat.RAW10 -> "RAW10 (packed)"
    ImageFormat.RAW12 -> "RAW12 (packed)"
    else -> "0x${format.toString(16)}"
}

object CameraProbe {

    private val RAW_FORMATS = listOf(ImageFormat.RAW10, ImageFormat.RAW12, ImageFormat.RAW_SENSOR)

    fun run(ctx: Context, r: Report): RawTarget? {
        val cm = ctx.getSystemService(CameraManager::class.java)
        r.section("Camera")
        val ids = cm.cameraIdList.toList()
        r.kv("Camera IDs visible to apps", ids.joinToString())

        val candidates = mutableListOf<RawTarget>()
        val probed = mutableSetOf<String>()
        for (id in ids) {
            val c = cm.getCameraCharacteristics(id)
            candidates += probeCamera(r, id, null, c, label = "Camera $id")
            probed += id
            // Logical multi-cameras hide their real lenses as physical IDs; Xiaomi often only exposes them this way.
            for (pid in c.physicalCameraIds) {
                if (pid in probed) continue
                probed += pid
                runCatching { cm.getCameraCharacteristics(pid) }
                    .onSuccess { candidates += probeCamera(r, pid, id, it, label = "  Physical camera $pid (of $id)") }
                    .onFailure { r.kv("Physical camera $pid", "not accessible: ${it.message}") }
            }
        }

        val best = pickTarget(candidates)
        r.line()
        if (best == null) {
            r.verdict(Report.Verdict.FAIL, "No back camera exposes a RAW stream to third-party apps")
            return null
        }
        val sizeOk = best.size.width >= Targets.OPEN_GATE_W - 96 && best.size.height >= Targets.OPEN_GATE_H - 96
        r.verdict(
            if (sizeOk) Report.Verdict.PASS else Report.Verdict.LIMITED,
            "RAW target: camera ${best.cameraId}${best.logicalParentId?.let { " (via $it)" } ?: ""}, ${best.formatName} ${best.size}, " +
                "advertised max %.1f fps".format(best.maxFps),
        )
        if (!best.manualSensor) r.verdict(Report.Verdict.LIMITED, "Camera ${best.cameraId} lacks MANUAL_SENSOR; exposure control will be limited")
        return best
    }

    private fun probeCamera(r: Report, id: String, parentId: String?, c: CameraCharacteristics, label: String): List<RawTarget> {
        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_BACK -> "back"
            CameraMetadata.LENS_FACING_FRONT -> "front"
            else -> "external"
        }
        val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "?"
        }
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val capNames = buildList {
            if (REQUEST_AVAILABLE_CAPABILITIES_RAW in caps) add("RAW")
            if (REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps) add("MANUAL_SENSOR")
            if (REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps) add("LOGICAL_MULTI")
            if (REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR in caps) add("ULTRA_HIGH_RES")
            if (REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING in caps) add("REMOSAIC")
            if (REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT in caps) add("10BIT_OUTPUT")
        }

        r.line()
        r.line("$label: $facing, $level")
        r.kv("Capabilities", capNames.joinToString().ifEmpty { "none of interest" })
        c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.let { r.kv("Focal length", it.joinToString { f -> "%.2f mm".format(f) }) }
        c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { r.kv("Sensor size", "%.2f × %.2f mm".format(it.width, it.height)) }
        c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { r.kv("Pixel array (binned)", it) }
        c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)?.let { r.kv("Pixel array (full res)", it) }
        c.get(CameraCharacteristics.SENSOR_INFO_BINNING_FACTOR)?.let { r.kv("Binning factor", "${it.width}×${it.height}") }

        val cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: -1
        val black = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.getOffsetForIndex(0, 0) ?: 0
        val white = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 0
        if (REQUEST_AVAILABLE_CAPABILITIES_RAW in caps) {
            r.kv("CFA", cfaName(cfa))
            r.kv("Black / white level", "$black / $white")
            val hasColor = c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1) != null &&
                c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) != null
            r.kv("Colour calibration (forward matrix / colour transform)", if (hasColor) "present" else "MISSING")
        }
        c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
            r.kv("Shutter range", "1/${(1e9 / it.upper).toInt().coerceAtLeast(1)} s … 1/${(1e9 / it.lower).toInt()} s")
        }
        c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { r.kv("ISO range", "${it.lower}–${it.upper}") }
        c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.let { r.kv("AE fps ranges", it.joinToString()) }

        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val targets = mutableListOf<RawTarget>()
        for (format in RAW_FORMATS) {
            val sizes = runCatching { map.getOutputSizes(format) }.getOrNull()?.toList().orEmpty()
            if (sizes.isEmpty()) continue
            val desc = sizes.joinToString { s ->
                val d = map.getOutputMinFrameDuration(format, s)
                if (d > 0) "$s @ ≤%.1f fps".format(1e9 / d) else "$s"
            }
            r.kv(rawFormatName(format), desc)
            if (facing == "back") {
                sizes.forEach { s ->
                    targets += RawTarget(
                        id, parentId, format, s, map.getOutputMinFrameDuration(format, s), cfa, black, white,
                        manualSensor = REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
                    )
                }
            }
        }
        c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)?.let { full ->
            RAW_FORMATS.forEach { f ->
                runCatching { full.getOutputSizes(f) }.getOrNull()?.takeIf { it.isNotEmpty() }
                    ?.let { r.kv("${rawFormatName(f)} full-res (remosaic) mode", it.joinToString()) }
            }
        }
        return targets
    }

    /**
     * Prefer the RAW size closest to 4096×3072 (binned open gate), then the higher advertised fps,
     * then packed RAW10 (less bandwidth than 16-bit RAW_SENSOR).
     */
    private fun pickTarget(all: List<RawTarget>): RawTarget? = all.minWithOrNull(
        compareBy<RawTarget> { abs(it.size.width - Targets.OPEN_GATE_W) + abs(it.size.height - Targets.OPEN_GATE_H) }
            .thenBy { it.minFrameDurationNs }
            .thenBy { if (it.format == ImageFormat.RAW10) 0 else 1 },
    )

    private fun cfaName(cfa: Int) = when (cfa) {
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB -> "RGB (no mosaic)"
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> "MONO"
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR -> "NIR"
        else -> "unknown ($cfa)"
    }
}
