package com.aliothmoon.maafw.runner

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.aliothmoon.maafw.BuildConfig

/** 虚拟屏尺寸；预览 SurfaceView 的 fixed size 也用它 */
data class DisplayResolution(val width: Int, val height: Int) {
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height else 1f
}

/**
 * 后台模式虚拟屏的一档预设：尺寸连同 dpi 一起定
 *
 * 用户在预设里显式选，不由 PI controller 的 display_* 推导——那三个字段
 * 解析后无人消费，见 docs/pi-compatibility.md
 */
data class ResolutionPreset(val label: String, val resolution: DisplayResolution, val dpi: Int) {
    /** 持久化身份：尺寸与 dpi 定了预设就定了，不另设 id；profile 改了其中一项，已选的用户回到默认档 */
    val id: String get() = "${resolution.width}x${resolution.height}@$dpi"
}

/**
 * 可选的预设列表：打包配方（build profile 的 `display.presets`）写了就整体替换内置两档，第一项是默认
 *
 * 配方的校验（横屏、dpi 范围、不重复）在构建期做完，这里只拆 `label|width|height|dpi`，拆不开的跳过
 */
object ResolutionPresets {

    val builtIn: List<ResolutionPreset> = listOf(
        ResolutionPreset("720P", DisplayResolution(1280, 720), 240),
        ResolutionPreset("1080P", DisplayResolution(1920, 1080), 280),
    )

    val available: List<ResolutionPreset> by lazy { parse(BuildConfig.MAFW_RESOLUTION_PRESETS.toList()).ifEmpty { builtIn } }

    val default: ResolutionPreset get() = available.first()

    /** 存的 id 为空或已不在列表里（换了配方）时落到默认档 */
    fun resolve(id: String?): ResolutionPreset = available.find { it.id == id } ?: default

    internal fun parse(encoded: List<String>): List<ResolutionPreset> = encoded.mapNotNull { entry ->
        val parts = entry.split('|')
        if (parts.size != 4) return@mapNotNull null
        val (width, height, dpi) = parts.drop(1).map { it.toIntOrNull() ?: return@mapNotNull null }
        ResolutionPreset(parts[0], DisplayResolution(width, height), dpi)
    }
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
