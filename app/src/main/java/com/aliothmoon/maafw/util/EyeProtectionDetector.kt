package com.aliothmoon.maafw.util

import android.content.Context
import android.provider.Settings

/**
 * 护眼 / 夜光模式探测（对齐 MaaMeow）
 *
 * 各厂商的开关各存各的键，没有统一接口；逐个读，命中第一个就报它的来源，
 * 运行日志里带上来源，用户才知道去哪个设置里关
 */
object EyeProtectionDetector {

    /** @return 命中的那个设置项（`厂商:键名`）；没开为 null */
    fun detect(context: Context): String? {
        val resolver = context.contentResolver
        return detect(
            secure = { key -> runCatching { Settings.Secure.getInt(resolver, key, 0) == 1 }.getOrDefault(false) },
            system = { key -> runCatching { Settings.System.getInt(resolver, key, 0) == 1 }.getOrDefault(false) },
            global = { key -> runCatching { Settings.Global.getInt(resolver, key, 0) == 1 }.getOrDefault(false) },
            nightDisplayService = { nightDisplayActivated(context) },
        )
    }

    internal fun detect(
        secure: (String) -> Boolean,
        system: (String) -> Boolean,
        global: (String) -> Boolean,
        nightDisplayService: () -> Boolean,
    ): String? = when {
        secure("night_display_activated") -> "aosp:night_display_activated"
        system("screen_paper_mode_enabled") -> "xiaomi:screen_paper_mode_enabled"
        // 华为与荣耀同一个键
        system("eyes_protection_mode") -> "huawei:eyes_protection_mode"
        system("blue_light_filter") || global("blue_light_filter") -> "samsung:blue_light_filter"
        system("coloros_eyeprotect_enable") || system("eyeprotect_enable") -> "oppo:coloros_eyeprotect_enable"
        system("vivo_night_display") || secure("vivo_night_display") -> "vivo:vivo_night_display"
        // 键都没命中时问系统服务：有的 ROM 夜光状态不落 Settings
        nightDisplayService() -> "color_display:isNightDisplayActivated"
        else -> null
    }

    /** ColorDisplayManager 是 @SystemApi，只能反射 */
    private fun nightDisplayActivated(context: Context): Boolean = runCatching {
        val manager = context.getSystemService("color_display") ?: return false
        manager.javaClass.getMethod("isNightDisplayActivated").invoke(manager) as? Boolean
    }.getOrNull() == true
}
