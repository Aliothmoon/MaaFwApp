package com.aliothmoon.maafw.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap

/** 桌面可见应用；清单的 queries 声明了 MAIN / LAUNCHER，targetSdk 30+ 才查得到别的包 */
object LauncherApps {

    data class App(val label: String, val packageName: String)

    /** 按应用名排序；查询慢，调用方放 IO 线程 */
    fun load(context: Context): List<App> {
        val pm = context.packageManager
        return pm.queryIntentActivities(launcherIntent(), 0)
            .distinctBy { it.activityInfo?.packageName }
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                App(info.loadLabel(pm).toString().ifBlank { pkg }, pkg)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun label(context: Context, packageName: String): String? {
        if (packageName.isBlank()) return null
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()
    }

    /** 桌面图标优先，退回应用图标，再退回系统默认图标 */
    fun icon(context: Context, packageName: String, sizePx: Int): Bitmap {
        val pm = context.packageManager
        return runCatching {
            val drawable = pm.queryIntentActivities(launcherIntent().setPackage(packageName), 0)
                .firstOrNull()?.loadIcon(pm)
                ?: pm.getApplicationIcon(packageName)
            drawable.toBitmap(sizePx, sizePx)
        }.getOrElse { pm.defaultActivityIcon.toBitmap(sizePx, sizePx) }
    }

    private fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
}
