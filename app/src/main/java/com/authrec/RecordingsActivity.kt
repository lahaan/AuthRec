package com.authrec

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.authrec.record.SoftwareTranscoder
import com.authrec.record.Transcoder
import com.authrec.ui.UiKit
import com.authrec.ui.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The recordings in Movies/AuthRec, newest first: play (the phone's own player), share, compress
 * and delete. Compress re-encodes a copy smaller with the phone's hardware codecs ([Transcoder]);
 * the original stays. Opened from Settings in either layout; the camera is closed meanwhile.
 *
 * Lists the clips this install made; with READ_MEDIA_VIDEO (asked once) also older ones, e.g.
 * from before a debug ⇄ release reinstall.
 *
 * adb (testing): `--es compress <file name> --es preset high|medium|small [--es codec av1]`
 * compresses that clip as soon as the list is loaded; to compare encoder settings also
 * `--ei mbps N --es rc cq|vbr|cbr --ei bframes N --ef gop S --ei priority 0|1|-1 --ei complexity N
 * --ei quality N --ei qpmin N --ei qpmax N --es tag name`. x265 instead: `--es codec x265
 * --es x265preset veryfast --ef crf 24 [--ei bframes N] [--es tag name]`.
 */
class RecordingsActivity : Activity() {

    private data class Clip(val uri: Uri, val name: String, val durationMs: Long, val bytes: Long,
                            val width: Int, val height: Int, val addedS: Long)

    private lateinit var kit: UiKit
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private val main = Handler(Looper.getMainLooper())
    private val thumbnails = Executors.newSingleThreadExecutor()
    private var clips = emptyList<Clip>()

    // The one compression that may run at a time (hardware [Transcoder] or x265).
    private var job: Transcoder? = null
    private var softJob: SoftwareTranscoder? = null
    private var jobClip: Uri? = null
    private var jobProgress = 0f
    private var jobCooling = false
    private val busy get() = job != null || softJob != null

    private fun cancelJob() {
        job?.cancelled = true
        softJob?.cancelled = true
    }
    private val rowUpdaters = mutableMapOf<Uri, () -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this) // this screen can be the first one a process starts (adb, or after a crash)
        kit = UiKit(this, glass = true)
        summary = kit.label("", 12f, dim = true)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(kit.button("‹ Camera") { finish() })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, 0, 0)
                addView(kit.label("Recordings", 18f))
                addView(summary)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(28), dp(16), dp(28), 0)
                addView(header)
                addView(ScrollView(context).apply {
                    isVerticalFadingEdgeEnabled = true
                    setFadingEdgeLength(dp(24))
                    addView(list)
                }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
            })
        })
        // Full screen like the camera (the status bar sat on the header); after setContentView,
        // which creates the window's decor (before it, this threw).
        window.insetsController?.apply {
            hide(android.view.WindowInsets.Type.systemBars())
            systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            getSharedPreferences("authrec", MODE_PRIVATE).getBoolean("askedVideoAccess", false)) {
            load()
        } else {
            getSharedPreferences("authrec", MODE_PRIVATE).edit().putBoolean("askedVideoAccess", true).apply()
            requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_VIDEO), REQ_READ)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_READ) load()
    }

    override fun onDestroy() {
        cancelJob()
        thumbnails.shutdownNow()
        super.onDestroy()
    }

    // ---- The list ----

    private fun load() {
        clips = query()
        val total = clips.sumOf { it.bytes }
        summary.text = "${clips.size} clips · ${mb(total)}"
        list.removeAllViews()
        rowUpdaters.clear()
        if (clips.isEmpty()) list.addView(kit.label("No recordings yet.", 14f, dim = true).apply { setPadding(0, dp(24), 0, 0) })
        clips.forEach { list.addView(row(it)) }
        intent.getStringExtra("compress")?.let { name ->
            intent.removeExtra("compress")
            clips.firstOrNull { it.name == name }?.let { clip ->
                if (intent.getStringExtra("codec") == "x265") {
                    val x = intent.extras!!
                    val p = x.getString("x265preset") ?: "veryfast"
                    val crf = x.getFloat("crf", 24f)
                    startSoftCompress(clip, SoftwareTranscoder.Settings(p, crf, x.getString("tag") ?: "x265-$p-${crf.toInt()}", x.getInt("bframes", -1)))
                    return@let
                }
                val preset = runCatching { Transcoder.Preset.valueOf(intent.getStringExtra("preset")!!.uppercase()) }.getOrDefault(Transcoder.Preset.MEDIUM)
                val mime = if (intent.getStringExtra("codec") == "av1") MediaFormat.MIMETYPE_VIDEO_AV1 else MediaFormat.MIMETYPE_VIDEO_HEVC
                var t = Transcoder.targetFor(preset, mime, clip.width, clip.height, frameRateOf(clip), averageBitrate(clip))
                val x = intent.extras!!
                if (x.containsKey("mbps")) t = t.copy(bitrate = x.getInt("mbps") * 1_000_000)
                x.getString("tag")?.let { t = t.copy(tag = it) }
                t = t.copy(tuning = Transcoder.Tuning(
                    rateMode = when (x.getString("rc")) { "cq" -> 0; "vbr" -> 1; "cbr" -> 2; else -> -1 },
                    bFrames = x.getInt("bframes", 0), gopSeconds = x.getFloat("gop", 2f),
                    priority = x.getInt("priority", 1), complexity = x.getInt("complexity", -1), quality = x.getInt("quality", -1),
                    qpMin = x.getInt("qpmin", -1), qpMax = x.getInt("qpmax", -1)))
                startCompress(clip, t)
            } ?: Log.w(TAG, "compress: no clip named $name")
        }
    }

    private fun query(): List<Clip> = runCatching {
        val projection = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_ADDED,
        )
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.IS_PENDING} = 0",
            arrayOf("${Environment.DIRECTORY_MOVIES}/AuthRec%"), "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    add(Clip(Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString()),
                        c.getString(1) ?: "?", c.getLong(2), c.getLong(3), c.getInt(4), c.getInt(5), c.getLong(6)))
                }
            }
        } ?: emptyList()
    }.getOrElse {
        Log.w(TAG, "listing recordings failed", it)
        emptyList()
    }

    private fun row(clip: Clip): View {
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF1A1D22.toInt())
        }
        thumbnails.execute {
            runCatching { contentResolver.loadThumbnail(clip.uri, Size(320, 180), null) }
                .onSuccess { bmp -> main.post { thumb.setImageBitmap(bmp) } }
        }
        val details = kit.label(detailsOf(clip), 12f, dim = true)
        val compress = kit.button("Compress…") { if (busy && jobClip == clip.uri) cancelJob() else askCompress(clip) }
        val buttons = listOfNotNull(
            kit.button("Play") { play(clip) },
            kit.button("Share") { share(clip) },
            compress.takeIf { COMPRESS_IN_LIST },
            kit.button("Delete") { askDelete(clip) },
        )
        rowUpdaters[clip.uri] = {
            val mine = jobClip == clip.uri && busy
            details.text = when {
                mine && jobCooling -> "Paused: letting the phone cool down… ${(jobProgress * 100).toInt()} %"
                mine -> "Compressing… ${(jobProgress * 100).toInt()} %"
                else -> detailsOf(clip)
            }
            compress.text = if (mine) "Cancel" else "Compress…"
            compress.isEnabled = !busy || mine
        }
        rowUpdaters[clip.uri]!!()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(thumb, LinearLayout.LayoutParams(dp(128), dp(72)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(10), 0)
                addView(kit.label(clip.name, 14f))
                addView(details)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            buttons.forEach { addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) }) }
        }
    }

    private fun detailsOf(c: Clip): String {
        val secs = c.durationMs / 1000
        val date = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(c.addedS * 1000))
        return "${c.width}×${c.height} · ${secs / 60}:%02d · ${mb(c.bytes)} · ${averageBitrate(c) / 1_000_000} Mbps · $date".format(secs % 60)
    }

    private fun mb(bytes: Long) = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000} MB"

    private fun averageBitrate(c: Clip): Int = if (c.durationMs > 0) (c.bytes * 8_000 / c.durationMs).toInt() else 0

    private fun frameRateOf(c: Clip): Int = runCatching {
        val ex = MediaExtractor().apply { setDataSource(this@RecordingsActivity, c.uri, null) }
        try {
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true && it.containsKey(MediaFormat.KEY_FRAME_RATE) }
                ?.getInteger(MediaFormat.KEY_FRAME_RATE) ?: 30
        } finally {
            ex.release()
        }
    }.getOrDefault(30)

    // ---- Actions ----

    private fun play(c: Clip) = runCatching {
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(c.uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }.onFailure { summary.text = "No app to play videos: ${it.message}" }

    private fun share(c: Clip) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, c.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), c.name))
    }

    private fun askDelete(c: Clip) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${c.name}?")
            .setMessage("${mb(c.bytes)}. This can't be undone from here.")
            .setPositiveButton("Delete") { _, _ -> delete(c) }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun delete(c: Clip) {
        try {
            contentResolver.delete(c.uri, null, null)
            load()
        } catch (e: SecurityException) {
            // Not ours (an earlier install's): Android asks the user itself.
            runCatching {
                startIntentSenderForResult(MediaStore.createDeleteRequest(contentResolver, listOf(c.uri)).intentSender, REQ_DELETE, null, 0, 0, 0)
            }.onFailure { summary.text = "Can't delete ${c.name}: ${it.message}" }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DELETE) load()
    }

    /**
     * How close the copy looks to the original: x265's quality target. Measured on the owner's
     * grainy 2K clip (183444, X14, Quick; VMAF against the original): 20 → 90 at 25 % of the
     * size, 24 → 83 at 10 %, 28 → 75 at 4 %. [bitsPerPixel]: what grainy footage needed (per pixel
     * and frame), the starting guess for size until this phone has compressed something.
     */
    private enum class Quality(val label: String, val crf: Float, val note: String, val bitsPerPixel: Double) {
        HIGH("High", 20f, "Very close to the original.", 0.24),
        BALANCED("Balanced", 23f, "Hard to tell apart on a phone or laptop.", 0.12),
        SMALL("Small", 27f, "Fine for sharing; fine detail and grain get softer.", 0.05),
    }

    /**
     * How long the phone works on it (x265's preset), not how it plays. [pixelsPerSecond]: what
     * the X14 managed at Balanced, the starting guess for time (Quick ~31 MP/s, Thorough ~15).
     */
    private enum class Effort(val label: String, val preset: String, val note: String, val pixelsPerSecond: Double) {
        QUICK("Quick", "veryfast", "Shorter wait.", 31e6),
        THOROUGH("Thorough", "medium", "About twice the wait, a slightly better picture at the same size.", 15e6),
    }

    private fun askCompress(c: Clip) {
        val prefs = getSharedPreferences("authrec", MODE_PRIVATE)
        var quality = runCatching { Quality.valueOf(prefs.getString("compressQuality", "")!!) }.getOrDefault(Quality.BALANCED)
        var effort = runCatching { Effort.valueOf(prefs.getString("compressEffort", "")!!) }.getOrDefault(Effort.QUICK)
        val fps = frameRateOf(c)
        val pixels = c.width.toDouble() * c.height * fps * c.durationMs / 1000

        val qualityNote = kit.label("", 12f, dim = true)
        val effortNote = kit.label("", 12f, dim = true)
        val estimate = kit.label("", 13f)
        fun refresh() {
            qualityNote.text = quality.note
            effortNote.text = effort.note
            val bpp = prefs.getFloat("compressBpp_${quality.name}", quality.bitsPerPixel.toFloat()).toDouble()
            val speed = prefs.getFloat("compressSpeed_${effort.name}_${quality.name}", effort.pixelsPerSecond.toFloat()).toDouble()
            val bytes = (pixels * bpp / 8 + AUDIO_BITRATE / 8.0 * c.durationMs / 1000).toLong()
            val secs = (pixels / speed).toLong().coerceAtLeast(1)
            val share = (bytes * 100 / c.bytes.coerceAtLeast(1)).coerceAtLeast(1)
            estimate.text = if (share >= 80) {
                "This clip is already about this small (≈ ${mb(bytes)}): a copy wouldn't save much."
            } else {
                "≈ ${mb(bytes)} ($share % of ${mb(c.bytes)}) · about ${duration(secs)} · the original stays"
            }
        }
        var markQuality: (Int) -> Unit = {}
        var markEffort: (Int) -> Unit = {}
        val (qualityRow, mq) = kit.segmented(Quality.entries.map { it.label }) { i -> quality = Quality.entries[i]; markQuality(i); refresh() }
        val (effortRow, me) = kit.segmented(Effort.entries.map { it.label }) { i -> effort = Effort.entries[i]; markEffort(i); refresh() }
        markQuality = mq
        markEffort = me
        mq(quality.ordinal)
        me(effort.ordinal)

        fun section(title: String, row: View, note: TextView) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
            addView(kit.label(title, 13f))
            addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) })
            addView(note, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2) })
        }
        val view = ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(16), dp(24), dp(4))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(kit.label("Compress a copy", 18f))
                        addView(kit.label(c.name, 12f, dim = true))
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(kit.button("ⓘ  What do these mean?") { explainCompress() })
                })
                addView(section("Quality", qualityRow, qualityNote))
                addView(section("Effort", effortRow, effortNote))
                addView(section("Format", View(context), kit.label(
                    "HEVC (H.265), 10-bit: plays on phones, Macs and PCs from the last several years.", 12f, dim = true)))
                addView(estimate, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(14) })
            })
        }
        refresh()
        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Compress") { _, _ ->
                prefs.edit().putString("compressQuality", quality.name).putString("compressEffort", effort.name).apply()
                startSoftCompress(c, SoftwareTranscoder.Settings(effort.preset, quality.crf,
                    "${quality.name.lowercase()}${if (effort == Effort.THOROUGH) "-thorough" else ""}"), quality, effort, pixels)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun explainCompress() {
        AlertDialog.Builder(this)
            .setTitle("Compress")
            .setMessage(
                "Makes a smaller copy of the clip for sharing or keeping; the original stays as it is.\n\n" +
                "Quality is how close the copy looks to the original. High is very close. Balanced is hard to tell " +
                "apart on a phone or laptop. Small softens fine detail and grain, and is the easiest to send.\n\n" +
                "Effort is how long your phone works on it, not how it plays back: both play the same. Thorough takes " +
                "about twice as long as Quick and gets a slightly better picture into the same size.\n\n" +
                "Sizes and times are estimates. Grain, darkness and movement need more space; clean, still shots far " +
                "less. The estimates learn from what your phone actually does.\n\n" +
                "Compressing keeps every core of the phone busy, so it gets warm; it pauses by itself to cool down " +
                "if needed. Keep this screen open until it's done.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun duration(secs: Long) = when {
        secs < 60 -> "$secs s"
        secs < 3600 -> "${secs / 60} min ${secs % 60} s"
        else -> "${secs / 3600} h ${secs % 3600 / 60} min"
    }

    /** [quality]/[effort]/[pixels] (null from adb): to learn this phone's speed and sizes for the estimates. */
    private fun startSoftCompress(c: Clip, settings: SoftwareTranscoder.Settings, quality: Quality? = null,
                                  effort: Effort? = null, pixels: Double = 0.0) {
        if (busy) return
        val t = SoftwareTranscoder(this, c.uri, c.name, settings) { p ->
            main.post {
                jobProgress = p.fraction
                jobCooling = p.cooling
                rowUpdaters[c.uri]?.invoke()
            }
        }
        softJob = t
        jobClip = c.uri
        jobProgress = 0f
        jobCooling = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rowUpdaters.values.forEach { it() }
        Thread({
            val r = t.run()
            if (r.error == null && quality != null && effort != null && pixels > 0 && r.seconds > 0) {
                val prefs = getSharedPreferences("authrec", MODE_PRIVATE)
                fun learn(key: String, value: Double, first: Double) {
                    val old = prefs.getFloat(key, first.toFloat())
                    prefs.edit().putFloat(key, (old + (value.toFloat() - old) * 0.5f)).apply()
                }
                learn("compressSpeed_${effort.name}_${quality.name}", pixels / r.seconds, effort.pixelsPerSecond)
                learn("compressBpp_${quality.name}", r.bytes * 8.0 / pixels, quality.bitsPerPixel)
            }
            main.post {
                softJob = null
                jobClip = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (isDestroyed) return@post
                load()
                // Not String.format: the "%" of the share would be read as a format specifier.
                summary.text = r.error?.let { "Compress: $it" }
                    ?: "Saved ${mb(r.bytes)} (${(r.bytes * 100 / c.bytes.coerceAtLeast(1)).toInt()} % of ${c.name}) in ${r.seconds.toInt()} s"
            }
        }, "compress").start()
    }

    private fun startCompress(c: Clip, target: Transcoder.Target) {
        if (busy) return
        val t = Transcoder(this, c.uri, c.name, target) { p ->
            main.post {
                jobProgress = p
                rowUpdaters[c.uri]?.invoke()
            }
        }
        job = t
        jobClip = c.uri
        jobProgress = 0f
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rowUpdaters.values.forEach { it() }
        Thread({
            val r = t.run()
            main.post {
                job = null
                jobClip = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (isDestroyed) return@post
                load()
                // Not String.format: the "%" of the share would be read as a format specifier.
                summary.text = r.error?.let { "Compress: $it" }
                    ?: "Saved ${mb(r.bytes)} (${(r.bytes * 100 / c.bytes.coerceAtLeast(1)).toInt()} % of ${c.name}) in ${r.seconds.toInt()} s"
            }
        }, "compress").start()
    }

    companion object {
        private const val TAG = "AuthRec"
        private const val REQ_READ = 1
        private const val REQ_DELETE = 2
        /** The copied AAC track, roughly, for size estimates. */
        private const val AUDIO_BITRATE = 200_000L
        /**
         * Compress uses x265 ([SoftwareTranscoder]). The X14's hardware HEVC encoder re-encoding the
         * owner's grainy 2K clips (VMAF against the original, 2026-10-10): 22 % of the size scored
         * 63, 73 % scored 86, where x265 scores 83 at 10 %; the hardware path stays for adb tests.
         */
        private const val COMPRESS_IN_LIST = true
    }
}
