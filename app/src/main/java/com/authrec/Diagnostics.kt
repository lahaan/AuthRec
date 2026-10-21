package com.authrec

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A plain-text report for testing on phones we can't plug in: device, what every camera id
 * (listed or hidden) reports, the lens scan results, settings and this app's recent log. Saved to
 * Download/AuthRec and handed to the share sheet so it can be sent over any chat app.
 */
object Diagnostics {

    fun cameraReport(context: Context): String = buildString {
        val cm = context.getSystemService(CameraManager::class.java)
        val listed = cm.cameraIdList.toSet()
        for (id in (0..31).map { it.toString() }) {
            val c = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
            fun <T> g(k: CameraCharacteristics.Key<T>) = c.get(k)
            appendLine("cam $id${if (id in listed) "" else " (hidden)"}: facing=${g(CameraCharacteristics.LENS_FACING)} " +
                "focal=${g(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList()} " +
                "sensor=${g(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)} " +
                "minFocus=${g(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)} " +
                "afModes=${g(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList()} " +
                "afRegions=${g(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)} " +
                "level=${g(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)} " +
                "caps=${g(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList()} " +
                "iso=${g(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)} " +
                "exp=${g(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)} " +
                "orient=${g(CameraCharacteristics.SENSOR_ORIENTATION)} " +
                "array=${g(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)} " +
                "white=${g(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)} " +
                "cfa=${g(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)} " +
                "physical=${c.physicalCameraIds} " +
                "zoom=${g(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)} " +
                "raw=${g(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList()}")
        }
    }

    /** The maker's own OS version properties (which skin, which release). */
    private val osProperties = setOf(
        "ro.build.version.oneui", "ro.miui.ui.version.name", "ro.mi.os.version.name", "ro.build.version.opporom",
        "ro.build.version.oplusrom", "ro.build.version.realmeui", "ro.vivo.os.version", "ro.build.version.magic",
        "ro.build.version.emui", "ro.build.version.nothing", "ro.product.marketname",
    )

    /**
     * The maker's OS version and its camera allow-list properties, where an app may read them (many
     * `vendor.*` ones are SELinux-protected and simply don't show): most makers hide extra lenses
     * from apps not named in a list like `vendor.camera.aux.packagelist` (Xiaomi, Motorola, Asus),
     * `persist.camera.privapp.list` (Oplus) or `ro.camera.aux.packagelist` (Honor).
     */
    private fun systemProperties(): String = buildString {
        val line = Regex("""^\[([^\]]+)]: \[(.*)]$""")
        ProcessBuilder("getprop").redirectErrorStream(true).start().inputStream.bufferedReader().useLines { lines ->
            lines.mapNotNull { line.find(it)?.destructured }.forEach { (name, value) ->
                val camera = "camera" in name && listOf("aux", "packagelist", "privapp", "multicam", "expose").any { it in name }
                if (value.isNotEmpty() && (name in osProperties || camera)) appendLine("$name = $value")
            }
        }
    }

    private fun report(context: Context): String = buildString {
        val app = context.packageManager.getPackageInfo(context.packageName, 0)
        appendLine("AuthRec ${app.versionName} diagnostics, ${Date()}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), SoC ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.DISPLAY}")
        appendLine("Fingerprint: ${Build.FINGERPRINT}")
        // 33+ guarantees RAW on the main back camera; 34+ a logical camera over the back lenses.
        appendLine("Media performance class: ${Build.VERSION.MEDIA_PERFORMANCE_CLASS.takeIf { it > 0 } ?: "none"}")
        context.getSystemService(PowerManager::class.java)?.let { pm ->
            appendLine("Thermal status ${pm.currentThermalStatus}, headroom ${runCatching { "%.2f".format(pm.getThermalHeadroom(10)) }.getOrDefault("?")}")
        }
        append(runCatching { systemProperties() }.getOrElse { "properties unavailable: $it\n" })
        appendLine()
        appendLine("== Cameras ==")
        append(runCatching { cameraReport(context) }.getOrElse { "failed: $it\n" })
        appendLine()
        for (name in listOf("lenses", "authrec", "reccheck")) {
            appendLine("== Settings: $name ==")
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toSortedMap().forEach { (k, v) -> appendLine("$k = $v") }
            appendLine()
        }
        appendLine("== Video encoders ==")
        append(encoderReport())
        appendLine()
        appendLine("== How the app last ended (Android's record) ==")
        append(runCatching { exitReport(context) }.getOrElse { "unavailable: $it\n" })
        appendLine()
        appendLine("== Events (this and earlier launches) ==")
        appendLine(EventLog.tail(500))
        appendLine()
        appendLine("== App log ==")
        // An app may always read its own process's log.
        append(runCatching {
            ProcessBuilder("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}", "-t", "3000")
                .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        }.getOrElse { "log unavailable: $it\n" })
    }

    /**
     * Why the app's last processes ended, from Android: killed for memory, crashed (Java or
     * native), not responding, swiped away, updated, with its memory then. The event log can't
     * tell these apart (the app just "started" again); on the X14 this showed 4K AV1 Compress
     * getting the app killed for low memory at 5.7–7.4 GB (2026-10-19). ANRs add their main thread.
     */
    private fun exitReport(context: Context): String = buildString {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        val reasons = mapOf(
            0 to "unknown", 1 to "exited itself", 2 to "signalled", 3 to "LOW MEMORY", 4 to "CRASH (Java)",
            5 to "CRASH (native)", 6 to "ANR (not responding)", 7 to "start-up failed", 8 to "permission changed",
            9 to "excessive resource use", 10 to "user requested (force stop)", 11 to "user stopped", 12 to "dependency died",
            13 to "other system kill", 14 to "freezer", 15 to "package state changed", 16 to "package updated",
        )
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 15)
        if (exits.isEmpty()) appendLine("(none recorded)")
        for (e in exits) {
            appendLine("${time.format(Date(e.timestamp))} ${reasons[e.reason] ?: "reason ${e.reason}"}" +
                (e.description?.let { " ($it)" } ?: "") + ", importance ${e.importance}" +
                ", pss ${e.pss / 1024} MB, rss ${e.rss / 1024} MB")
            if (e.reason == android.app.ApplicationExitInfo.REASON_ANR) {
                runCatching {
                    e.traceInputStream?.bufferedReader()?.useLines { lines ->
                        lines.dropWhile { !it.startsWith("\"main\"") }.take(25).forEach { appendLine("    $it") }
                    }
                }
            }
        }
    }

    /** Writes the report to Download/AuthRec and opens the share sheet. Call off the main thread. */
    /**
     * Every video encoder with its types, hardware or not, and size/bitrate limits: tells us
     * whether a phone could record or compress to AV1 (the X14 has only AV1 decoders).
     */
    private fun encoderReport(): String = buildString {
        runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { t -> t.startsWith("video/") } }
                .forEach { info ->
                    info.supportedTypes.filter { it.startsWith("video/") }.forEach { type ->
                        val v = info.getCapabilitiesForType(type).videoCapabilities
                        appendLine("${info.name} $type ${if (info.isHardwareAccelerated) "hw" else "sw"}" +
                            (v?.let { " up to ${it.supportedWidths.upper}x${it.supportedHeights.upper}, ${it.bitrateRange.upper / 1_000_000} Mbps" } ?: ""))
                    }
                }
        }.onFailure { appendLine("(couldn't list: ${it.message})") }
    }

    fun share(context: Context): Uri {
        val name = "authrec-diagnostics-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AuthRec")
        }) ?: error("can't create $name")
        context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(report(context)) }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AuthRec diagnostics (${Build.MODEL})")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Send AuthRec diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return uri
    }
}
