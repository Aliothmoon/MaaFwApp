package com.aliothmoon.maafw.runner

/** 虚拟屏尺寸；预览 SurfaceView 的 fixed size 也用它 */
data class DisplayResolution(val width: Int, val height: Int) {
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height else 1f
}

/**
 * 虚拟屏分辨率偏好（对齐 MaaMeow 的 ResolutionPreference）
 *
 * 用户显式选 720P / 1080P，不再由 PI controller 的 display_* 推导；
 * PI 的 display_* 只在执行侧由特权进程自己读
 */
enum class ResolutionPreference(val resolution: DisplayResolution) {
    P720(DisplayResolution(1280, 720)),
    P1080(DisplayResolution(1920, 1080)),
}
