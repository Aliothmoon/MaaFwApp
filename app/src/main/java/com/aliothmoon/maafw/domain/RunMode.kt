package com.aliothmoon.maafw.domain

import com.aliothmoon.maafw.constant.DisplayMode

/**
 * 采集与注入落在哪块屏；存在 `AppSettings` 而非 `UserConfiguration`——它随设备走，
 * 不该被运行配置的 schemaVersion 重置
 *
 * [FOREGROUND] 直接操作主屏：目标应用就在用户眼前，不建虚拟屏也不 force_stop；
 * 代价是运行期间手机被占用，且屏幕尺寸随旋转变
 * [BACKGROUND] 建后台虚拟屏：手机可继续正常使用，尺寸由 PI controller 的 display_* 推导
 */
enum class RunMode(val displayMode: Int) {
    FOREGROUND(DisplayMode.PRIMARY),
    BACKGROUND(DisplayMode.BACKGROUND),
}
