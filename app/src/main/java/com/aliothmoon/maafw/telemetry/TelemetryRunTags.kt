package com.aliothmoon.maafw.telemetry

import android.content.Context
import android.content.pm.PackageManager
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.runner.ResolutionPreset

/**
 * 会随用户设置或目标应用变的运行环境，每轮开跑时重读
 *
 * 只收影响识别的几项，给 PI 作者分「同一个节点为什么有人过有人不过」：运行模式、后台虚拟屏的分辨率、
 * 目标应用版本。ROM、提权后端、定时失败这类外壳自己的诊断只写本地日志，不发 PI 作者的 Sentry
 *
 * 目标包名只有特权进程知道（看门狗从屏上反推），开跑前拿到的是上一轮的那个，还没跑过就不打
 */
internal object TelemetryRunTags {

    fun collect(context: Context, runMode: RunMode, preset: ResolutionPreset, targetPackage: String?): Map<String, String> =
        buildMap {
            put("run_mode", runMode.name.lowercase())
            // 前台模式的尺寸是设备当下的物理尺寸，硬件摘要里已有
            if (runMode == RunMode.BACKGROUND) put("resolution", preset.id)
            targetPackage?.let { put("target.version", targetVersion(context, it)) }
        }

    private fun targetVersion(context: Context, packageName: String): String = try {
        context.packageManager.getPackageInfo(packageName, 0).versionName ?: UNKNOWN
    } catch (_: PackageManager.NameNotFoundException) {
        "not_installed"
    }

    private const val UNKNOWN = "unknown"
}
