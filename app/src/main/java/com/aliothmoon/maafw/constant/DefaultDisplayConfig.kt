package com.aliothmoon.maafw.constant

/**
 * 虚拟显示器的兜底参数
 * 实际分辨率由用户选的 [com.aliothmoon.maafw.runner.ResolutionPreference] 决定，
 * 这里只在 payload 没带上尺寸时兜底（docs/privileged-runtime.md §7）
 */
object DefaultDisplayConfig {
    /** 建屏时的名字，只在 dumpsys 里可见 */
    const val VD_NAME = "MaaFwVirtualDisplay"

    const val DISPLAY_NONE = -1

    const val WIDTH = 1280
    const val HEIGHT = 720
    const val DPI = 160
}
