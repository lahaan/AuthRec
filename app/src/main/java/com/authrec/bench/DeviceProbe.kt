package com.authrec.bench

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

object DeviceProbe {

    fun run(ctx: Context, r: Report) {
        r.section("Device")
        r.kv("Model", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        r.kv("SoC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        r.kv("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        r.kv("Build", Build.DISPLAY)

        val am = ctx.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val gb = 1024.0 * 1024 * 1024
        r.kv("RAM total", "%.1f GB".format(mem.totalMem / gb))
        r.kv("RAM available", "%.1f GB".format(mem.availMem / gb))
        r.kv("App heap limit", "${am.memoryClass} MB (large: ${am.largeMemoryClass} MB)")

        // One open-gate RAW16 frame is 4096*3072*2 bytes ≈ 24 MB; we want a ring of ~8 in flight.
        val ringMb = Targets.OPEN_GATE_W.toLong() * Targets.OPEN_GATE_H * 2 * 8 / (1024 * 1024)
        val headroomOk = mem.availMem / (1024 * 1024) > ringMb * 4
        r.verdict(
            if (headroomOk) Report.Verdict.PASS else Report.Verdict.LIMITED,
            "Memory: ${"%.1f".format(mem.availMem / gb)} GB free for a ~$ringMb MB RAW frame ring",
        )
        thermal(ctx, r)
    }

    fun thermal(ctx: Context, r: Report) {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val status = when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "unknown"
        }
        // Headroom: 1.0 = throttling starts; NaN if the device doesn't report it.
        val headroom = pm.getThermalHeadroom(10)
        r.kv("Thermal status", status)
        r.kv("Thermal headroom (10 s forecast)", if (headroom.isNaN()) "not reported" else "%.2f".format(headroom))
    }
}
