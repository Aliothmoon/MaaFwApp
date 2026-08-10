package com.aliothmoon.maafw.constant

/**
 * 采集与注入的目标屏，由用户在设置里选（[com.aliothmoon.maafw.domain.RunMode]）
 *
 * PRIMARY 采主屏：不建虚拟屏，尺寸跟着设备旋转走，预览里的手动触摸不接管（用户直接摸真屏即可）
 * BACKGROUND 建虚拟屏：尺寸由 PI controller 的 display_* 推导，目标应用被拉到该屏上
 */
object DisplayMode {
    const val PRIMARY = 1
    const val BACKGROUND = 2
}
