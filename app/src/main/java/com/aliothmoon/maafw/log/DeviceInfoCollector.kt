package com.aliothmoon.maafw.log

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.util.ScreenSize
import java.io.File
import java.time.ZonedDateTime
import java.util.Locale

/** 单项取不到就填 unknown：导出是排障用的，不能被某个系统服务拖成整份拿不到 */
object DeviceInfoCollector {

    fun collect(context: Context, storageDir: File) = DeviceInfo(
        exportTime = ZonedDateTime.now(),
        applicationId = BuildConfig.APPLICATION_ID,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        buildType = BuildConfig.BUILD_TYPE,
        appVersion = BuildConfig.MAFW_APP_VERSION,
        frameworkVersion = BuildConfig.MAFW_FRAMEWORK_VERSION,
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        securityPatch = Build.VERSION.SECURITY_PATCH,
        abi = Build.SUPPORTED_ABIS.joinToString(),
        screen = screenInfo(context),
        memory = memoryInfo(context),
        storage = storageInfo(storageDir),
        batteryOptimization = batteryOptimized(context),
        selinux = selinuxMode(),
    )

    // 取生效尺寸而非物理尺寸：改过分辨率的机器上，这份快照要能对上 app 实际看到的屏
    private fun screenInfo(context: Context): String = runCatching {
        val (w, h) = ScreenSize.current(context)
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val refresh = wm.defaultDisplay.refreshRate
        "$w x $h @ ${"%.0f".format(Locale.US, refresh)}Hz " +
            "(density ${context.resources.displayMetrics.densityDpi}dpi)"
    }.getOrDefault(UNKNOWN)

    private fun memoryInfo(context: Context): String = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        "${formatGb(mi.totalMem)} total, ${formatGb(mi.availMem)} free"
    }.getOrDefault(UNKNOWN)

    // 排障要的是「现在还剩多少」，不是清完缓存能腾出多少
    @SuppressLint("UsableSpace")
    private fun storageInfo(dir: File): String = runCatching {
        "${formatGb(dir.usableSpace)} usable / ${formatGb(dir.totalSpace)} total"
    }.getOrDefault(UNKNOWN)

    private fun batteryOptimized(context: Context): String = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
            "ignored (app exempt)"
        } else {
            "NOT ignored (schedule may be killed)"
        }
    }.getOrDefault(UNKNOWN)

    private fun selinuxMode(): String = runCatching {
        when (File("/sys/fs/selinux/enforce").readText().trim()) {
            "1" -> "enforcing"
            "0" -> "permissive"
            else -> UNKNOWN
        }
    }.getOrDefault(UNKNOWN)

    private fun formatGb(bytes: Long): String =
        "%.1f GB".format(Locale.US, bytes / 1024f / 1024f / 1024f)
}
