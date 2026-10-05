package com.authrec.bench

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Phase 0: capability bench. Probes device, camera RAW support, GPU throughput and
 * encoders against the AuthRec target modes, then writes a report to
 * Android/data/com.authrec/files/bench/.
 */
class BenchActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var runButton: Button
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runButton = Button(this).apply {
            text = "Run bench"
            setOnClickListener { startBench() }
        }
        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(output) }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(24, 48, 24, 24)
            addView(runButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })

        // Lets the bench be started over adb: adb shell am start -n com.authrec/.bench.BenchActivity --ez autorun true
        if (intent.getBooleanExtra("autorun", false)) startBench()
    }

    private fun startBench() {
        if (running) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        running = true
        runButton.isEnabled = false
        output.text = ""

        val report = Report { line -> main.post { appendLine(line) } }
        thread(name = "bench") {
            runStage(report, "Device") { DeviceProbe.run(this, report) }
            val rawTarget = runStage(report, "Camera") { CameraProbe.run(this, report) }
            runStage(report, "RAW stream") { RawStreamTest.run(this, report, rawTarget) }
            runStage(report, "GPU") { GpuProbe.run(report, rawTarget) }
            runStage(report, "Encoders") { EncoderProbe.run(report) }
            runStage(report, "Thermal") { DeviceProbe.thermal(this, report) }
            report.summary()

            val file = saveReport(report)
            report.line()
            report.line("Report saved: ${file.absolutePath}")
            main.post {
                running = false
                runButton.isEnabled = true
            }
        }
    }

    /** Runs one stage; a crash in one probe shouldn't kill the rest of the bench. */
    private fun <T> runStage(report: Report, name: String, block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        report.verdict(Report.Verdict.FAIL, "$name stage crashed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    private fun saveReport(report: Report): File {
        val dir = File(getExternalFilesDir(null), "bench").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(dir, "bench-$stamp.txt").apply { writeText(report.toString()) }
    }

    private fun appendLine(line: String) {
        output.append(line + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startBench()
        } else {
            output.text = "Camera permission is required for the bench."
        }
    }

    companion object {
        private const val REQ_CAMERA = 1
    }
}
