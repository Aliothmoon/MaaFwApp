package com.aliothmoon.maafw.settings

import com.aliothmoon.maafw.privileged.ShizukuInstallHelper
import com.aliothmoon.preferences.PrefKey
import com.aliothmoon.preferences.PrefSchema

/**
 * app 级设置，与 `UserConfiguration` 分开存
 *
 * `UserConfiguration` 是运行配置的聚合根，走 schemaVersion 信封 + 版本不符即重置；
 * 提权后端这类设置不该跟着运行配置一起被重置，所以另起一个 Preferences DataStore
 *
 * 字段一律声明成 String：`@PrefSchema` 生成的 key 按字段类型选 preferencesKey，
 * 枚举与布尔都以文本落盘，改默认值不会让老数据变成非法值（见 [AppSettingsManager] 的解析）
 */
@PrefSchema
data class AppSettings(
    /** [com.aliothmoon.maafw.domain.RemoteBackend] 的 name */
    @PrefKey(default = "SHIZUKU")
    val startupBackend: String = "SHIZUKU",

    /** 用户在引导弹窗上点过「不再提醒」 */
    @PrefKey(default = "false")
    val skipShizukuCheck: String = "false",

    /** Shizuku 管理器的包名；有 ROM 内置了自己的分发，允许指到别处 */
    @PrefKey(default = ShizukuInstallHelper.SHIZUKU_PACKAGE)
    val shizukuLaunchPackage: String = ShizukuInstallHelper.SHIZUKU_PACKAGE,

    /** 首页是否显示「打开 Shizuku」快捷入口 */
    @PrefKey(default = "true")
    val shizukuShortcutEnabled: String = "true",

    /** [com.aliothmoon.maafw.domain.RunMode] 的 name */
    @PrefKey(default = "BACKGROUND")
    val runMode: String = "BACKGROUND",

    /** [com.aliothmoon.maafw.domain.OverlayControlMode] 的 name；仅前台模式生效 */
    @PrefKey(default = "FLOAT_BALL")
    val overlayControlMode: String = "FLOAT_BALL",

    /** 后台模式运行期是否自动盖上屏保；默认关，盖住整块屏幕这种事要用户先点头 */
    @PrefKey(default = "false")
    val screenSaverEnabled: String = "false",

    /** [com.aliothmoon.maafw.runner.ResolutionPreference] 的 name */
    @PrefKey(default = "P720")
    val resolutionPreference: String = "P720",

    /** 调试模式：开启后给特权进程传 isDebug，记录 MaaFramework 详细日志 */
    @PrefKey(default = "false")
    val debugMode: String = "false",

    /** [com.aliothmoon.maafw.theme.ThemeStyle] 的 name；DEFAULT 暖石蓝，SEMI_DESIGN 取 Semi Design 配色 */
    @PrefKey(default = "DEFAULT")
    val themeStyle: String = "DEFAULT",
)
