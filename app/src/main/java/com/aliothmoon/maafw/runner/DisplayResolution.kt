package com.aliothmoon.maafw.runner

import android.content.res.Resources
import com.aliothmoon.maafw.domain.ControllerDefinition
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 虚拟屏尺寸；同时定 native controller 的 screen_resolution 与预览 SurfaceView 的 fixed size */
data class DisplayResolution(val width: Int, val height: Int) {
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height else 1f
}

/**
 * 由 PI controller 的 display_* 声明推导虚拟屏分辨率（docs/privileged-runtime.md §5）
 * 官方语义是「截图缩放到该边长」；这里是自己建屏，直接按目标边长建，省掉再缩放一次
 *
 * 方向固定横屏：虚拟屏与设备旋转无关，PI 的模板一般按横屏截取——这是本项目的假设，不是协议规定
 * 边长取偶数：奇数宽在部分编码器上会导致 stride 与预期不符
 */
fun resolveDisplayResolution(controller: ControllerDefinition): DisplayResolution {
    val metrics = Resources.getSystem().displayMetrics
    val rawLong = max(metrics.widthPixels, metrics.heightPixels)
    val rawShort = min(metrics.widthPixels, metrics.heightPixels)
    if (controller.displayRaw || rawShort <= 0) {
        return DisplayResolution(rawLong.alignEven(), rawShort.alignEven())
    }
    val aspect = rawLong.toDouble() / rawShort
    controller.displayLongSide?.takeIf { it > 0 }?.let { long ->
        return DisplayResolution(long.alignEven(), (long / aspect).roundToInt().alignEven())
    }
    val short = controller.displayShortSide?.takeIf { it > 0 } ?: DEFAULT_SHORT_SIDE
    return DisplayResolution((short * aspect).roundToInt().alignEven(), short.alignEven())
}

/** PI V2 的 display_short_side 默认值 */
private const val DEFAULT_SHORT_SIDE = 720

private fun Int.alignEven(): Int = this and 1.inv()
