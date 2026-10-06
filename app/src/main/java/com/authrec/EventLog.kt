package com.authrec

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the camera did (opens, errors, retries, lens scans, recordings, crashes), kept across
 * launches in files/events.log so Send diagnostics shows the history even when the problem was
 * a crash, a frozen session or an earlier launch. Logcat alone is too short-lived for that, and
 * testers often send the report long after the problem. Every line also goes to logcat.
 */
object EventLog {
    private const val TAG = "AuthRec"
    /** The file is cut back to its newer half beyond this. */
    private const val MAX_BYTES = 384 * 1024

    private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Opens the log and records crashes from any thread. Safe to call more than once. */
    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "events.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { log("CRASH on thread ${thread.name}", e) }
            previous?.uncaughtException(thread, e)
        }
    }

    /** [e] adds a stack trace (and logs as a warning). */
    @Synchronized
    fun log(message: String, e: Throwable? = null) {
        if (e != null) Log.w(TAG, message, e) else Log.i(TAG, message)
        val f = file ?: return
        runCatching {
            f.appendText("${time.format(Date())} $message${e?.let { "\n" + it.stackTraceToString().trimEnd() } ?: ""}\n")
            if (f.length() > MAX_BYTES) {
                val text = f.readText()
                f.writeText(text.substring(text.indexOf('\n', text.length / 2) + 1))
            }
        }
    }

    /** The last [lines] lines, oldest first. */
    @Synchronized
    fun tail(lines: Int): String =
        runCatching { file?.readLines()?.takeLast(lines)?.joinToString("\n") }.getOrNull() ?: "(no events)"
}
