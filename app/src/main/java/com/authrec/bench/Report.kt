package com.authrec.bench

import android.util.Log

/** Collects bench output as plain text; every line also goes to logcat (tag "AuthRecBench"). */
class Report(private val onLine: (String) -> Unit) {
    private val sb = StringBuilder()

    enum class Verdict(val mark: String) { PASS("✅"), LIMITED("⚠️"), FAIL("❌"), INFO("•") }

    private val verdicts = mutableListOf<Pair<Verdict, String>>()

    fun line(text: String = "") {
        sb.appendLine(text)
        Log.i(TAG, text)
        onLine(text)
    }

    fun section(title: String) {
        line()
        line("=== $title ===")
    }

    fun kv(key: String, value: Any?) = line("  $key: $value")

    /** A headline result that also shows up in the summary at the end. */
    fun verdict(v: Verdict, text: String) {
        verdicts += v to text
        line("${v.mark} $text")
    }

    fun summary() {
        section("Summary")
        verdicts.forEach { (v, t) -> line("${v.mark} $t") }
    }

    override fun toString() = sb.toString()

    companion object {
        const val TAG = "AuthRecBench"
    }
}

/** Target modes for AuthRec; every probe checks against these. */
object Targets {
    const val OPEN_GATE_W = 4096
    const val OPEN_GATE_H = 3072
    const val SUPERPIXEL_W = OPEN_GATE_W / 2
    const val SUPERPIXEL_H = OPEN_GATE_H / 2
    val FPS = listOf(24, 25, 30, 60)
    const val MAX_BITRATE = 150_000_000
}
