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

    private fun report(context: Context): String = buildString {
        val app = context.packageManager.getPackageInfo(context.packageName, 0)
        appendLine("AuthRec ${app.versionName} diagnostics, ${Date()}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), SoC ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.DISPLAY}")
        appendLine("Fingerprint: ${Build.FINGERPRINT}")
        appendLine()
        appendLine("== Cameras ==")
        append(runCatching { cameraReport(context) }.getOrElse { "failed: $it\n" })
        appendLine()
        for (name in listOf("lenses", "authrec")) {
            appendLine("== Settings: $name ==")
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toSortedMap().forEach { (k, v) -> appendLine("$k = $v") }
            appendLine()
        }
        appendLine("== App log ==")
        // An app may always read its own process's log.
        append(runCatching {
            ProcessBuilder("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}", "-t", "3000")
                .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        }.getOrElse { "log unavailable: $it\n" })
    }

    /** Writes the report to Download/AuthRec and opens the share sheet. Call off the main thread. */
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
