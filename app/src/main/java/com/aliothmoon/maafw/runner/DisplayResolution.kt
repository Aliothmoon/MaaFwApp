package com.aliothmoon.maafw.runner

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/** 虚拟屏尺寸；预览 SurfaceView 的 fixed size 也用它 */
data class DisplayResolution(val width: Int, val height: Int) {
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height else 1f
}

/**
 * 虚拟屏分辨率偏好（对齐 MaaMeow 的 ResolutionPreference）
 *
 * 用户显式选 720P / 1080P，不再由 PI controller 的 display_* 推导——那三个字段
 * 现在解析后无人消费，见 docs/pi-compatibility.md
 */
enum class ResolutionPreference(val resolution: DisplayResolution) {
    P720(DisplayResolution(1280, 720)),
    P1080(DisplayResolution(1920, 1080)),
}

/**
 * 设备屏幕尺寸（对齐 MaaMeow Misc.getScreenSize）：API 30+ 用 maximumWindowMetrics，
 * 旧版回退 getRealMetrics。首页「分辨率」展示用，与运行模式 / 虚拟屏偏好无关
 */
fun screenSize(context: Context): DisplayResolution {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = wm.maximumWindowMetrics.bounds
        bounds.width() to bounds.height()
    } else {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        dm.widthPixels to dm.heightPixels
    }
    return DisplayResolution(w, h)
}
