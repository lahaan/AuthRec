package com.authrec.record

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import com.authrec.gl.Renderer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves Photo mode's pictures to MediaStore `Pictures/AuthRec`: the developed view as a JPEG
 * (EXIF: orientation, camera, exposure) and, when asked, the burst's first RAW frame as a DNG of
 * the same name. The DNG carries the lens's own calibration (DngCreator), so other apps develop
 * it their own way; the JPEG is ours, look and all.
 */
internal object PhotoSaver {

    class Result(val jpeg: Uri?, val jpegBytes: Long, val dng: Uri?, val error: String?)

    /**
     * [turn]: clockwise degrees a viewer turns the JPEG (the phone's pose, as for a recording);
     * [rawTurn]: the same for the RAW, which is the sensor's own image (never mirrored, the front
     * camera not yet turned). [dng]: what DngCreator needs for [photo]'s RAW, null for none.
     * Runs on the caller's thread (seconds for 12 MP); recycles the photo's bitmap.
     */
    fun save(context: Context, photo: Renderer.Photo, turn: Int, dng: Pair<CameraCharacteristics, CaptureResult>?, rawTurn: Int): Result {
        val name = "AuthRec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val errors = mutableListOf<String>()
        var jpegBytes = 0L
        val jpeg = runCatching {
            val tmp = File(context.cacheDir, "$name.jpg")
            try {
                tmp.outputStream().buffered().use { photo.image.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                writeExif(context, tmp, photo, turn)
                jpegBytes = tmp.length()
                insert(context, "$name.jpg", "image/jpeg") { out -> tmp.inputStream().use { it.copyTo(out) } }
            } finally {
                tmp.delete()
            }
        }.onFailure { errors += "JPEG: ${it.message ?: it.javaClass.simpleName}" }.getOrNull()
        photo.image.recycle()

        val raw = photo.raw
        val dngUri = if (raw == null || dng == null) {
            if (raw != null) errors += "DNG: no capture result for the frame"
            null
        } else runCatching {
            DngCreator(dng.first, dng.second).use { creator ->
                creator.setOrientation(exifOrientation(rawTurn))
                insert(context, "$name.dng", "image/x-adobe-dng") { out ->
                    raw.rewind()
                    creator.writeByteBuffer(out, Size(photo.rawWidth, photo.rawHeight), raw, 0)
                }
            }
        }.onFailure { errors += "DNG: ${it.message ?: it.javaClass.simpleName}" }.getOrNull()
        return Result(jpeg, jpegBytes, dngUri, errors.joinToString("; ").ifEmpty { null })
    }

    private fun writeExif(context: Context, file: File, photo: Renderer.Photo, turn: Int) {
        val exif = ExifInterface(file.absolutePath)
        val now = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation(turn).toString())
        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "AuthRec " + context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        exif.setAttribute(ExifInterface.TAG_DATETIME, now)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
        photo.meta?.let { m ->
            if (m.iso > 0) exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, m.iso.toString())
            if (m.exposureNs > 0) exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, (m.exposureNs / 1e9).toString())
        }
        exif.saveAttributes()
    }

    /** A pending MediaStore image filled by [write], published once it's complete (removed if it fails). */
    private fun insert(context: Context, displayName: String, mime: String, write: (java.io.OutputStream) -> Unit): Uri {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AuthRec")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }) ?: error("MediaStore refused to create $displayName")
        try {
            (resolver.openOutputStream(uri) ?: error("can't write $displayName")).use { write(it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    private fun exifOrientation(clockwiseDegrees: Int) = when ((clockwiseDegrees % 360 + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private const val JPEG_QUALITY = 95
}
