package com.authrec

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.authrec.color.Look
import com.authrec.color.LookParams
import com.authrec.color.Looks
import com.authrec.color.LogProfile
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Simple look editor: shows a log frame (grabbed from the camera or from a clip) through the
 * look being edited, with a handful of sliders. Saving stores an editable look for the app and
 * exports a .cube for the frame's log profile to Download/AuthRec (for Resolve and friends).
 * Press and hold the image to compare with the plain Rec.709 view.
 */
class LookEditorActivity : Activity() {

    private var profile = LogProfile.APPLE_LOG
    private var params = LookParams()
    private var lookName = "My look"
    private var presetIndex = 0

    private lateinit var image: ImageView
    private lateinit var hint: TextView
    private lateinit var profileButton: Button
    private lateinit var sliderPanel: LinearLayout
    private val sliders = mutableListOf<() -> Unit>() // refreshers, after params change wholesale

    private var source: IntArray? = null
    private var sourceW = 0
    private var sourceH = 0
    private var output: Bitmap? = null
    private var showingBefore = false

    private val worker = HandlerThread("look-preview").apply { start() }
    private val workerHandler = Handler(worker.looper)
    private val main = Handler(Looper.getMainLooper())
    private val renderVersion = AtomicInteger()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_PROFILE)?.let { profile = LogProfile.valueOf(it) }
        intent.getStringExtra(EXTRA_LOOK)?.let { path ->
            runCatching { Look.load(File(path)) }.onSuccess {
                params = it.params
                lookName = it.name
            }
        }
        intent.getStringExtra(EXTRA_PRESET)?.let { name ->
            Looks.presets.indexOfFirst { it.name == name }.takeIf { it >= 0 }?.let {
                presetIndex = it
                params = Looks.presets[it].params
                lookName = "${Looks.presets[it].name} (mine)"
            }
        }
        buildUi()
        window.insetsController?.hide(WindowInsets.Type.systemBars())
        intent.getStringExtra(EXTRA_SAMPLE)?.let { path -> BitmapFactory.decodeFile(path)?.let { setSample(it) } }
        if (source == null) hint.text = "No frame yet: tap \"Clip frame\", or open the editor from the camera to grab the live image."
    }

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    // ---- UI ----

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 12f
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { showingBefore = true; render() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { showingBefore = false; render() }
                }
                true
            }
        }
        hint = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 11f
            text = "Hold the image to compare with plain Rec.709"
        }
        profileButton = button("") { cycleProfile() }

        sliderPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addSlider("Exposure", -2f, 2f, { "%+.1f EV".format(it) }, { params.exposure }) { params = params.copy(exposure = it) }
        addSlider("Contrast", -1f, 1f, ::signedPct, { params.contrast }) { params = params.copy(contrast = it) }
        addSlider("Highlights", -1f, 1f, ::signedPct, { params.highlights }) { params = params.copy(highlights = it) }
        addSlider("Shadows", -1f, 1f, ::signedPct, { params.shadows }) { params = params.copy(shadows = it) }
        addSlider("Fade", 0f, 1f, ::pct, { params.fade }) { params = params.copy(fade = it) }
        addSlider("Temperature", -1f, 1f, ::signedPct, { params.temperature }) { params = params.copy(temperature = it) }
        addSlider("Tint", -1f, 1f, ::signedPct, { params.tint }) { params = params.copy(tint = it) }
        addSlider("Saturation", 0f, 2f, ::pct, { params.saturation }) { params = params.copy(saturation = it) }
        addSlider("Vibrance", -1f, 1f, ::signedPct, { params.vibrance }) { params = params.copy(vibrance = it) }
        addSlider("Shadow tint", 0f, 1f, ::pct, { params.shadowTint }) { params = params.copy(shadowTint = it) }
        addSlider("Shadow hue", 0f, 360f, { "%.0f°".format(it) }, { params.shadowHue }) { params = params.copy(shadowHue = it) }
        addSlider("Highlight tint", 0f, 1f, ::pct, { params.highlightTint }) { params = params.copy(highlightTint = it) }
        addSlider("Highlight hue", 0f, 360f, { "%.0f°".format(it) }, { params.highlightHue }) { params = params.copy(highlightHue = it) }
        val mono = Switch(this).apply {
            text = "Black & white"
            setTextColor(Color.WHITE)
            isChecked = params.mono
            setOnCheckedChangeListener { _: CompoundButton, on: Boolean -> params = params.copy(mono = on); render() }
        }
        sliders += { mono.isChecked = params.mono }
        sliderPanel.addView(mono)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Start from ▸") { nextPreset() })
            addView(button("Reset") { setParams(LookParams()) })
            addView(button("Clip frame") { pickClip() })
            addView(button("Save") { askNameAndSave() })
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(profileButton)
            addView(ScrollView(this@LookEditorActivity).apply { addView(sliderPanel) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(buttons)
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(image, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(hint)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF111111.toInt())
            addView(left, LinearLayout.LayoutParams(0, MATCH_PARENT, 1.6f))
            addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        })
        updateProfileButton()
    }

    private fun pct(v: Float) = "%.0f%%".format(v * 100)
    private fun signedPct(v: Float) = "%+.0f".format(v * 100)

    private fun addSlider(label: String, min: Float, max: Float, format: (Float) -> String, get: () -> Float, set: (Float) -> Unit) {
        val steps = 1000
        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        fun toProgress(v: Float) = ((v - min) / (max - min) * steps).toInt().coerceIn(0, steps)
        val bar = SeekBar(this).apply {
            this.max = steps
            progress = toProgress(get())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    set(min + (max - min) * p / steps)
                    text.text = "$label  ${format(get())}"
                    render()
                }
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }
        text.text = "$label  ${format(get())}"
        sliders += {
            bar.progress = toProgress(get())
            text.text = "$label  ${format(get())}"
        }
        sliderPanel.addView(text)
        sliderPanel.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun setParams(p: LookParams) {
        params = p
        sliders.forEach { it() }
        render()
    }

    private fun nextPreset() {
        presetIndex = (presetIndex + 1) % Looks.presets.size
        val preset = Looks.presets[presetIndex]
        lookName = "${preset.name} (mine)"
        setParams(preset.params)
        hint.text = "Starting from \"${preset.name}\""
    }

    private fun cycleProfile() {
        profile = LogProfile.entries[(profile.ordinal + 1) % LogProfile.entries.size]
        updateProfileButton()
        render()
    }

    private fun updateProfileButton() {
        profileButton.text = "Frame is: ${profile.label}"
    }

    // ---- Preview ----

    private fun setSample(bmp: Bitmap) {
        // ~1000 px wide keeps slider feedback quick on the CPU.
        val scaled = if (bmp.width > 1000) Bitmap.createScaledBitmap(bmp, 1000, 1000 * bmp.height / bmp.width, true) else bmp
        sourceW = scaled.width
        sourceH = scaled.height
        source = IntArray(sourceW * sourceH).also { scaled.getPixels(it, 0, sourceW, 0, 0, sourceW, sourceH) }
        output = Bitmap.createBitmap(sourceW, sourceH, Bitmap.Config.ARGB_8888)
        render()
    }

    /** Re-renders off the main thread; only the newest request gets displayed. */
    private fun render() {
        val src = source ?: return
        val version = renderVersion.incrementAndGet()
        val p = if (showingBefore) Looks.REC709.params else params
        val prof = profile
        workerHandler.post {
            if (version != renderVersion.get()) return@post
            val lut = Looks.bake("preview", p, prof, size = 25)
            val dst = IntArray(src.size)
            lut.applyTo(src, dst)
            main.post {
                if (version != renderVersion.get()) return@post
                val out = output ?: return@post
                out.setPixels(dst, 0, sourceW, 0, 0, sourceW, sourceH)
                image.setImageBitmap(out)
                image.invalidate()
            }
        }
    }

    // ---- Clip frame ----

    private fun pickClip() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }, REQ_CLIP)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_CLIP && resultCode == RESULT_OK && uri != null) loadClipFrame(uri)
    }

    private fun loadClipFrame(uri: Uri) {
        val frame = runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(this, uri)
                val durUs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0) * 1000
                r.getScaledFrameAtTime(durUs / 3, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1000, 1000)
            }
        }.getOrNull()
        if (frame == null) {
            Toast.makeText(this, "Couldn't read a frame from that clip", Toast.LENGTH_LONG).show()
            return
        }
        setSample(frame)
        hint.text = "Clip frame loaded. Set \"Frame is\" to the log profile it was shot in (log clips only, not baked ones)."
    }

    // ---- Save ----

    private fun askNameAndSave() {
        val input = EditText(this).apply {
            setText(lookName)
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Save look")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> save(input.text.toString().trim().ifEmpty { "My look" }) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save(name: String) {
        val dir = File(getExternalFilesDir(null), "luts").apply { mkdirs() }
        val look = Look(name, params)
        val file = look.save(dir)
        val exported = runCatching { exportCube(look) }.getOrNull()
        Toast.makeText(this, "Saved \"$name\"" + (exported?.let { "; .cube exported to Download/AuthRec/$it" } ?: ""), Toast.LENGTH_LONG).show()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_LOOK, file.absolutePath))
        finish()
    }

    /** Writes the look as a 33³ .cube for [profile] to Download/AuthRec; returns the file name. */
    private fun exportCube(look: Look): String {
        val fileName = "${Look.safeName(look.name)}_${profile.label.replace(" ", "")}.cube"
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AuthRec")
        }) ?: error("can't create $fileName")
        contentResolver.openOutputStream(uri)!!.use { look.toLut(profile).write(it) }
        return fileName
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_SAMPLE = "sample"
        const val EXTRA_LOOK = "look"
        const val EXTRA_PRESET = "preset"
        private const val REQ_CLIP = 1
    }
}
