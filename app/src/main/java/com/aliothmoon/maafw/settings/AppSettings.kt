package com.aliothmoon.maafw.settings

import com.aliothmoon.maafw.privileged.ShizukuInstallHelper
import com.aliothmoon.maafw.update.UpdateChannel
import com.aliothmoon.maafw.update.UpdateSource
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

    /**
     * 跑完自动强停目标应用的**全局**开关；开了就一律关，含手动 Start 那轮
     *
     * 与 `ScheduleStrategy.closeAppAfterTask` 并存，本项优先（见 [com.aliothmoon.maafw.runner.CloseTargetAppHook]）：
     * 规则级那条只管自己那次触发，全局这条管每一轮
     */
    @PrefKey(default = "false")
    val closeAppAfterTask: String = "false",

    /** 预览上是否画出注入的触点；默认开，关掉即不再向特权进程注册触点回调 */
    @PrefKey(default = "true")
    val touchPreviewEnabled: String = "true",

    /** 后台模式下 StartApp 前是否无条件强停目标应用；默认开，保持既有强停行为 */
    @PrefKey(default = "true")
    val forceRestartApp: String = "true",

    /** [com.aliothmoon.maafw.runner.ResolutionPreference] 的 name */
    @PrefKey(default = "P720")
    val resolutionPreference: String = "P720",

    /** 调试模式：开启后给特权进程传 isDebug，记录 MaaFramework 详细日志 */
    @PrefKey(default = "false")
    val debugMode: String = "false",

    /** [com.aliothmoon.maafw.theme.ThemeStyle] 的 name；DEFAULT 暖石蓝，SEMI_DESIGN 取 Semi Design 配色 */
    @PrefKey(default = "DEFAULT")
    val themeStyle: String = "DEFAULT",

    /**
     * [com.aliothmoon.maafw.domain.EventNotificationLevel] 的 name
     *
     * 只管「跑完了 / 出错了」这类事件通知，前台服务常驻通知与 PI 的 focus 通知不受它影响——
     * 前者是保活的载体，关掉整个执行就失去保护
     */
    @PrefKey(default = "DEFAULT")
    val eventNotificationLevel: String = "DEFAULT",

    /** 定时触发时是否亮屏解锁；默认关，注入 PIN 这种事要用户先点头。手动 Start 不走这条 */
    @PrefKey(default = "false")
    val wakeUnlockEnabled: String = "false",

    /**
     * 解锁用的纯数字 PIN，**明文存在本 DataStore 里**
     *
     * 没上 Keystore 加密：解锁必须在特权进程里拿到明文才能注入按键，加密只是把明文
     * 挪到进程内存里晚出现一会儿，挡不住能读到 app 私有目录的攻击者。取舍写在
     * docs/scheduled-triggers.md；导出配置时必须清空这一项
     */
    @PrefKey(default = "")
    val wakeCredential: String = "",

    /**
     * PI 声明了 `telemetry.sentry` 时才有意义；默认关
     *
     * 与 MXU 的默认开相反：DSN 是 PI 作者的，外壳这一侧没有隐私说明的位置，
     * 上报与否交给用户先点头
     */
    @PrefKey(default = "false")
    val telemetryEnabled: String = "false",

    /** [UpdateSource] 的 name；只决定更新 APK 从哪解析/下载，不改变检查更新的默认源 */
    @PrefKey(default = "MIRROR_CHYAN")
    val updateDownloadSource: String = "MIRROR_CHYAN",

    /** [UpdateChannel] 的 name */
    @PrefKey(default = "STABLE")
    val updateChannel: String = "STABLE",

    /** GitHub API token；可为空，只在下载前解析 GitHub 下载地址与下载请求时使用 */
    @PrefKey(default = "")
    val githubToken: String = "",

    /**
     * Mirror酱 CDK；选择 Mirror酱 下载时必填。
     *
     * 与解锁 PIN 一样明文落在 app 私有 DataStore；这里没有服务端会话可复用，
     * 加 Keystore 也只能推迟明文出现，不能阻止能读私有目录的攻击者拿到它
     */
    @PrefKey(default = "")
    val mirrorChyanCdk: String = "",
)
