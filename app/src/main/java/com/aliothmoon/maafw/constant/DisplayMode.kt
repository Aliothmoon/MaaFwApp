package com.aliothmoon.maafw.constant

/**
 * 采集与注入的目标屏
 * 当前只实现 BACKGROUND；PRIMARY（直接操作主屏）见 docs/privileged-runtime.md §7
 */
object DisplayMode {
    const val PRIMARY = 1
    const val BACKGROUND = 2
}
