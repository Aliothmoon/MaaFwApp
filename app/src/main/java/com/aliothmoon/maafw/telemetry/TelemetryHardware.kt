package com.aliothmoon.maafw.telemetry

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Sentry `hardware` context，键名对齐 MXU `collect_hardware`（cpu / cpu_cores / memory_total_mb / gpu / os）
 *
 * Android 上拿不到 GPU 型号——要先建 GL 上下文才能查 renderer，为这一项起 GL 不划算，这里留空，
 * 与 MXU 非 Windows 平台一致；另补 `device` 和 `abi`，同一 SoC 的不同机型要靠它们分开
 */
internal object TelemetryHardware {

    fun collect(context: Context): Map<String, Any> = buildMap {
        put("cpu", cpu())
        put("cpu_cores", Runtime.getRuntime().availableProcessors())
        memoryTotalMb(context)?.let { put("memory_total_mb", it) }
        put("gpu", "")
        put("os", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        put("abi", Build.SUPPORTED_ABIS.joinToString(","))
    }

    private fun cpu(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val soc = "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim()
            if (soc.isNotBlank() && !soc.equals("unknown unknown", ignoreCase = true)) return soc
        }
        return Build.HARDWARE.orEmpty()
    }

    private fun memoryTotalMb(context: Context): Long? = runCatching {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        info.totalMem / 1024 / 1024
    }.getOrNull()
}
