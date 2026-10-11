package com.authrec

import android.Manifest
import android.app.Activity
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
import com.authrec.ui.GlassDialog
import com.authrec.ui.UiKit
import com.authrec.ui.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The recordings in Movies/AuthRec, newest first: play (the phone's own player), share, compress
 * and delete. Compress re-encodes a smaller copy on the CPU ([SoftwareTranscoder]: HEVC, H.264 or
 * AV1); the original stays. Opened from Settings in either layout; the camera is closed meanwhile.
 *
 * Lists the clips this install made; with READ_MEDIA_VIDEO (asked once) also older ones, e.g.
 * from before a debug ⇄ release reinstall.
 *
 * adb (testing): `--es compress <file name> --es preset high|medium|small [--es codec av1]`
 * compresses that clip as soon as the list is loaded; to compare encoder settings also
 * `--ei mbps N --es rc cq|vbr|cbr --ei bframes N --ef gop S --ei priority 0|1|-1 --ei complexity N
 * --ei quality N --ei qpmin N --ei qpmax N --es tag name`. Software encoders instead: `--es codec
 * x265|x264|svtav1 --es preset2 veryfast (SVT-AV1: 0–13) --ef crf 24 [--ef maxshare 0.35] [--ei bframes N]
 * [--es tag name]`.
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
                val soft = when (intent.getStringExtra("codec")) {
                    "x265" -> SoftwareTranscoder.Format.HEVC
                    "x264" -> SoftwareTranscoder.Format.AVC
                    "svtav1" -> SoftwareTranscoder.Format.AV1
                    else -> null
                }
                if (soft != null) {
                    val x = intent.extras!!
                    val p = x.getString("x265preset") ?: x.getString("preset2") ?: "veryfast"
                    val crf = x.getFloat("crf", 24f)
                    startSoftCompress(clip, SoftwareTranscoder.Settings(p, crf, x.getString("tag") ?: "${soft.label}-$p-${crf.toInt()}",
                        x.getInt("bframes", -1), soft, x.getFloat("maxshare", 0f)))
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
        GlassDialog.message(this, kit, "Delete ${c.name}?", "${mb(c.bytes)}. This can't be undone from here.",
            listOf(GlassDialog.Action("Keep"), GlassDialog.Action("Delete", main = true) { delete(c) }))
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
     * How close the copy looks to the original; each format's quality target for it is in [Codec].
     * [maxShare]: the most the copy may take of the original's bitrate. Clean footage never gets
     * near it (183444 at x265 Quick: 25 / 13 / 4 %); grainy high-ISO footage would, the grain
     * being noise the quality target keeps at any cost (owner's ISO 3200 night clip: 110 % at
     * Balanced, 72 % at Small), and gives up grain instead.
     */
    private enum class Quality(val label: String, val note: String, val maxShare: Float) {
        HIGH("High", "Very close to the original.", 0.6f),
        BALANCED("Balanced", "Hard to tell apart on a phone or laptop.", 0.35f),
        SMALL("Small", "Fine for sharing; fine detail and grain get softer.", 0.15f),
    }

    /** How long the phone works on it (the encoder's preset), not how it plays. */
    private enum class Effort(val label: String, val note: String) {
        QUICK("Quick", "Shorter wait."),
        THOROUGH("Thorough", "About twice the wait; a better picture for the size."),
    }

    /**
     * What the copy is written as, and each encoder's settings for [Quality] ([crfs]) and [Effort]
     * ([presets]), matched on the owner's grainy 2K clip (183444, X14; VMAF against the original
     * at a share of the size):
     * - x265 veryfast: CRF 20 → 90 at 25 %, 24 → 83 at 10 %, 28 → 75 at 4 %.
     * - x264 veryfast: 19 → 91 at 43 %, 21 → 87 at 25 %, 23 → 82 at 14 %, 26 → 74 at 7 %.
     * - SVT-AV1 preset 8: 24 → 91 at 25 %, 29 → 87 at 13 %, 34 → 82 at 7 %, 39 → 77 at 3.6 %, in
     *   half x265's time; preset 6: a quarter smaller again for the same score, in x265 Quick's time.
     * [bitsPerPixel] (per pixel and frame, by quality, Quick) and [pixelsPerSecond] (Quick,
     * Thorough, at Balanced on the X14) start the estimates until this phone has compressed
     * something; [thoroughSize]: Thorough's size against Quick's at the same quality target.
     */
    private enum class Codec(val label: String, val format: SoftwareTranscoder.Format, val note: String,
                             val crfs: List<Float>, val presets: List<String>, val bitsPerPixel: List<Double>,
                             val pixelsPerSecond: List<Double>, val thoroughSize: Double, val tag: String) {
        HEVC("HEVC", SoftwareTranscoder.Format.HEVC,
            "Recommended. 10-bit; plays on phones, Macs and PCs from the last several years.",
            listOf(20f, 23f, 27f), listOf("veryfast", "medium"), listOf(0.24, 0.12, 0.05), listOf(31e6, 15e6), 1.0, ""),
        AVC("H.264", SoftwareTranscoder.Format.AVC,
            "Plays everywhere, old computers and TVs too. Bigger files, 8-bit: keep the original for grading.",
            listOf(19f, 22f, 25f), listOf("veryfast", "medium"), listOf(0.42, 0.18, 0.09), listOf(41e6, 20e6), 1.0, "-h264"),
        AV1("AV1", SoftwareTranscoder.Format.AV1,
            "The smallest files, 10-bit. Only newer phones, computers and browsers play it.",
            listOf(25f, 31f, 39f), listOf("8", "6"), listOf(0.21, 0.10, 0.035), listOf(63e6, 38e6), 0.75, "-av1"),
    }

    /**
     * This phone's measured [kind] ("Bpp" or "Speed") for a combination, else [guess]. (0.5.0's
     * keys without the format learned sizes from grainy clips too and are left behind.)
     */
    private fun learned(kind: String, codec: Codec, effort: Effort, quality: Quality, guess: Double): Double =
        getSharedPreferences("authrec", MODE_PRIVATE).getFloat(learnedKey(kind, codec, effort, quality), guess.toFloat()).toDouble()

    private fun learnedKey(kind: String, codec: Codec, effort: Effort, quality: Quality) =
        "compress${kind}_${codec.name}_${effort.name}_${quality.name}"

    private fun bppGuess(codec: Codec, effort: Effort, quality: Quality) =
        codec.bitsPerPixel[quality.ordinal] * if (effort == Effort.THOROUGH) codec.thoroughSize else 1.0

    private fun askCompress(c: Clip) {
        val prefs = getSharedPreferences("authrec", MODE_PRIVATE)
        var quality = runCatching { Quality.valueOf(prefs.getString("compressQuality", "")!!) }.getOrDefault(Quality.BALANCED)
        var effort = runCatching { Effort.valueOf(prefs.getString("compressEffort", "")!!) }.getOrDefault(Effort.QUICK)
        var codec = runCatching { Codec.valueOf(prefs.getString("compressFormat", "")!!) }.getOrDefault(Codec.HEVC)
        val fps = frameRateOf(c)
        val pixels = c.width.toDouble() * c.height * fps * c.durationMs / 1000

        val qualityNote = kit.label("", 12f, dim = true)
        val effortNote = kit.label("", 12f, dim = true)
        val formatNote = kit.label("", 12f, dim = true)
        val estimate = kit.label("", 13f)
        fun refresh() {
            qualityNote.text = quality.note
            effortNote.text = effort.note
            formatNote.text = codec.note
            val bpp = learned("Bpp", codec, effort, quality, bppGuess(codec, effort, quality))
            val speed = learned("Speed", codec, effort, quality, codec.pixelsPerSecond[effort.ordinal])
            // From clean footage's size up to the cap that grainy footage meets.
            val audio = (AUDIO_BITRATE / 8.0 * c.durationMs / 1000).toLong()
            val bytes = (pixels * bpp / 8).toLong() + audio
            val most = (c.bytes * quality.maxShare).toLong()
            val secs = (pixels / speed).toLong().coerceAtLeast(1)
            estimate.text = if (bytes >= c.bytes * 0.8) {
                "This clip is already about this small (≈ ${mb(bytes)}): a copy wouldn't save much."
            } else {
                val size = if (bytes < most * 0.8) "≈ ${mb(bytes)}, up to ${mb(most)} if grainy or dark" else "≈ ${mb(most)}"
                "$size (the original: ${mb(c.bytes)})\nabout ${duration(secs)} · the original stays"
            }
        }
        var markQuality: (Int) -> Unit = {}
        var markEffort: (Int) -> Unit = {}
        var markFormat: (Int) -> Unit = {}
        val (qualityRow, mq) = kit.segmented(Quality.entries.map { it.label }) { i -> quality = Quality.entries[i]; markQuality(i); refresh() }
        val (effortRow, me) = kit.segmented(Effort.entries.map { it.label }) { i -> effort = Effort.entries[i]; markEffort(i); refresh() }
        val (formatRow, mf) = kit.segmented(Codec.entries.map { it.label }) { i -> codec = Codec.entries[i]; markFormat(i); refresh() }
        markQuality = mq
        markEffort = me
        markFormat = mf
        mq(quality.ordinal)
        me(effort.ordinal)
        mf(codec.ordinal)

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
                // Quality and Effort side by side: in landscape three rows under each other pushed the
                // estimate below the dialog's fold.
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(section("Quality", qualityRow, qualityNote), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(section("Effort", effortRow, effortNote), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
                })
                addView(section("Format", formatRow, formatNote))
                addView(estimate, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(14) })
            })
        }
        refresh()
        GlassDialog.show(this, kit, view, listOf(GlassDialog.Action("Cancel"), GlassDialog.Action("Compress", main = true) {
                prefs.edit().putString("compressQuality", quality.name).putString("compressEffort", effort.name)
                    .putString("compressFormat", codec.name).apply()
                val tag = quality.name.lowercase() + (if (effort == Effort.THOROUGH) "-thorough" else "") + codec.tag
                startSoftCompress(c, SoftwareTranscoder.Settings(codec.presets[effort.ordinal], codec.crfs[quality.ordinal], tag,
                    format = codec.format, maxShare = quality.maxShare), Learn(codec, quality, effort, pixels))
            }))
    }

    private fun explainCompress() {
        GlassDialog.message(this, kit, "Compress",
                "Makes a smaller copy of the clip for sharing or keeping; the original stays as it is.\n\n" +
                "Quality is how close the copy looks to the original. High is very close. Balanced is hard to tell " +
                "apart on a phone or laptop. Small softens fine detail and grain, and is the easiest to send.\n\n" +
                "Effort is how long your phone works on it, not how it plays back: both play the same. Thorough takes " +
                "about twice as long as Quick and gets a better picture into the same size.\n\n" +
                "Format is how the copy is stored. HEVC is the best all-round choice. H.264 plays on anything, old " +
                "computers and TVs too, but needs more space and keeps less colour detail (8-bit), so keep the " +
                "original if you'll still grade it. AV1 makes the smallest files, but only newer phones, computers " +
                "and browsers play it.\n\n" +
                "Sizes and times are estimates. Grain, darkness and movement need more space; clean, still shots far " +
                "less. Grainy clips (night, high ISO) stop at a size limit for each quality and lose some grain " +
                "instead, so a copy is always clearly smaller. The estimates learn from what your phone actually does.\n\n" +
                "Compressing keeps every core of the phone busy, so it gets warm; it pauses by itself to cool down " +
                "if needed. Keep this screen open until it's done.",
            listOf(GlassDialog.Action("OK", main = true)))
    }

    private fun duration(secs: Long) = when {
        secs < 60 -> "$secs s"
        secs < 3600 -> "${secs / 60} min ${secs % 60} s"
        else -> "${secs / 3600} h ${secs % 3600 / 60} min"
    }

    /** What a compression from the dialog was, to learn this phone's speed and sizes for the estimates. */
    private class Learn(val codec: Codec, val quality: Quality, val effort: Effort, val pixels: Double)

    /** [learn]: null from adb. */
    private fun startSoftCompress(c: Clip, settings: SoftwareTranscoder.Settings, learn: Learn? = null) {
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
            if (r.error == null && learn != null && learn.pixels > 0 && r.seconds > 0) {
                val prefs = getSharedPreferences("authrec", MODE_PRIVATE)
                fun update(kind: String, value: Double, guess: Double) = with(learn) {
                    val old = learned(kind, codec, effort, quality, guess).toFloat()
                    prefs.edit().putFloat(learnedKey(kind, codec, effort, quality), old + (value.toFloat() - old) * 0.5f).apply()
                }
                with(learn) {
                    update("Speed", pixels / r.seconds, codec.pixelsPerSecond[effort.ordinal])
                    // Sizes only from clips that stayed clear of the cap: grain's size is its own.
                    if (r.bytes < c.bytes * quality.maxShare * 0.85) update("Bpp", r.bytes * 8.0 / pixels, bppGuess(codec, effort, quality))
                }
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
         * Compress uses the CPU encoders ([SoftwareTranscoder]). The X14's hardware HEVC encoder re-encoding the
         * owner's grainy 2K clips (VMAF against the original, 2026-10-10): 22 % of the size scored
         * 63, 73 % scored 86, where x265 scores 83 at 10 %; the hardware path stays for adb tests.
         */
        private const val COMPRESS_IN_LIST = true
    }
}
