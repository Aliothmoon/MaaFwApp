package com.aliothmoon.maafw.schedule

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit

/**
 * 国产 ROM 自启动设置页引导（移植自 MaaMeow）
 *
 * 各厂商有私有的后台自启动管理，没有标准 API，只能逐个试对应的设置 Activity。
 * 不开自启，开机广播收不到、闹钟拉不起进程，定时就整轮哑掉
 *
 * 决策在 [AutoStartResolution]，本类只做 Intent 解析与提醒记录。
 * 厂商包可见性依赖 manifest 的 `<queries>` 声明
 */
object AutoStartHelper {

    data class OemEntry(val id: String, val intent: Intent)

    private val AUTOSTART_INTENTS = listOf(
        // Xiaomi MIUI / HyperOS
        OemEntry(
            "xiaomi",
            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ),
        // OPPO ColorOS
        OemEntry(
            "coloros",
            component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ),
        // OPPO 旧版
        OemEntry(
            "oppo",
            component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ),
        // Vivo OriginOS / FuntouchOS
        OemEntry(
            "vivo",
            component(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            ),
        ),
        // Huawei EMUI / HarmonyOS
        OemEntry(
            "huawei",
            component(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
        ),
        // Honor（独立后）
        OemEntry(
            "honor",
            component(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
        ),
        // Samsung
        OemEntry(
            "samsung",
            component("com.samsung.android.lool", "com.samsung.android.lool.activity.applist.AppListActivity"),
        ),
        // Meizu Flyme
        OemEntry(
            "meizu",
            component("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
        ),
        // ZTE
        OemEntry(
            "zte",
            component("com.zte.heartyservice", "com.zte.heartyservice.autostart.HeartifyAutoStartActivity"),
        ),
    )

    private val RESTRICTIVE_MANUFACTURERS = setOf(
        "xiaomi", "redmi", "oppo", "realme", "oneplus",
        "vivo", "iqoo", "huawei", "honor", "samsung",
        "meizu", "smartisan", "letv", "zte", "nubia",
    )

    private fun component(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))

    private fun isKnownRestrictiveManufacturer(): Boolean =
        Build.MANUFACTURER.lowercase() in RESTRICTIVE_MANUFACTURERS

    /** 命中即停：一台设备只会属于一家厂商，全扫要白做 8 次跨进程 resolveActivity */
    private fun resolvableOemIds(context: Context): List<String> = listOfNotNull(
        AUTOSTART_INTENTS
            .firstOrNull { context.packageManager.resolveActivity(it.intent, 0) != null }
            ?.id,
    )

    /** null = 无需引导；跨进程，调用方放在 IO 上 */
    fun resolveTarget(context: Context): AutoStartTarget? =
        AutoStartResolution.select(resolvableOemIds(context), isKnownRestrictiveManufacturer())

    fun intentFor(context: Context, target: AutoStartTarget): Intent? = when (target) {
        is AutoStartTarget.Oem -> AUTOSTART_INTENTS.firstOrNull { it.id == target.id }?.intent
            ?.let { Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        AutoStartTarget.AppDetails -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    // ===== 只记「不再提醒」：自启动开没开查不到，问的时机交给调用方（保存规则后） =====

    /**
     * 独立的 SharedPreferences，不进 `AppSettings`：这一项只关乎这台设备的系统设置，
     * 跟着设置导出到别的设备毫无意义
     */
    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "schedule_autostart"
    private const val PREFS_NEVER_REMIND = "never_remind"

    fun isNeverRemind(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREFS_NEVER_REMIND, false)

    fun markNeverRemind(prefs: SharedPreferences) {
        prefs.edit { putBoolean(PREFS_NEVER_REMIND, true) }
    }
}
