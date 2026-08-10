package com.aliothmoon.maafw.runner

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * [ScreenSizeSource] 的设备实现，与 MaaMeow 的 `Misc.getScreenSize` 同一路子
 *
 * 不走 `Resources.displayMetrics`：application context 的那份读不到 `wm size` 改过的
 * forced size（Android 9 上持续返回原生分辨率减去系统栏）。`maximumWindowMetrics` 与
 * `getRealMetrics` 底下都问 IWindowManager，拿得到当前生效的那个值
 */
class SystemScreenSizeSource(context: Context) : ScreenSizeSource {

    private val appContext = context.applicationContext

    override fun current(): DisplayResolution {
        val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            DisplayResolution(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            DisplayResolution(metrics.widthPixels, metrics.heightPixels)
        }
    }
}
