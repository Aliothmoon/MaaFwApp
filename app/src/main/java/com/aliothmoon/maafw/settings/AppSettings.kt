package com.aliothmoon.maafw.settings

import com.aliothmoon.maafw.privileged.ShizukuInstallHelper
import com.aliothmoon.maafw.runner.RunDurationLimit
import com.aliothmoon.maafw.update.UpdateChannel
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

    /**
     * 后台模式虚拟屏选的 [com.aliothmoon.maafw.runner.ResolutionPreset.id]；空 = 预设列表的第一项
     *
     * 取代旧的 `resolutionPreference`（存 720P/1080P 枚举名），换了键不做迁移：
     * 旧值只是两档里选了哪档，丢了也只是回到默认档
     */
    @PrefKey(default = "")
    val resolutionPreset: String = "",

    /** 调试模式：开启后给特权进程传 isDebug，记录 MaaFramework 详细日志 */
    @PrefKey(default = "false")
    val debugMode: String = "false",

    /**
     * 节点出错时把现场截图存到运行日志（logDir/on_error）
     *
     * 默认开：一张出错瞬间的图比事后复现便宜。对应 MaaGlobalOption_SaveOnError；
     * 框架核心默认 false，此项是唯一的开启途径，关了就永远不存
     */
    @PrefKey(default = "true")
    val saveOnError: String = "true",

    /** [com.aliothmoon.maafw.theme.ThemeStyle] 的 name；DEFAULT 暖石蓝，SEMI_DESIGN 取 Semi Design 配色 */
    @PrefKey(default = "DEFAULT")
    val themeStyle: String = "DEFAULT",

    /** 页面缩放：`auto` 按屏幕推荐，或 80–110 的百分比；见 [com.aliothmoon.maafw.theme.UiScale] */
    @PrefKey(default = "auto")
    val uiScale: String = "auto",

    /** 自定义背景开关；图片本身在 filesDir/backgrounds，见 [com.aliothmoon.maafw.wallpaper.WallpaperStore] */
    @PrefKey(default = "false")
    val wallpaperEnabled: String = "false",

    /** 每次换图写一个新值，图片文件名不变，靠它触发重新解码 */
    @PrefKey(default = "")
    val wallpaperToken: String = "",

    /** 以下三项都是 0–100 的百分比 */
    @PrefKey(default = "80")
    val wallpaperImageAlpha: String = "80",

    @PrefKey(default = "25")
    val wallpaperScrim: String = "25",

    @PrefKey(default = "0")
    val wallpaperBlur: String = "0",

    /**
     * [com.aliothmoon.maafw.domain.EventNotificationLevel] 的 name
     *
     * 只管「跑完了 / 出错了」这类事件通知，前台服务常驻通知与 PI 的 focus 通知不受它影响——
     * 前者是保活的载体，关掉整个执行就失去保护
     */
    @PrefKey(default = "DEFAULT")
    val eventNotificationLevel: String = "DEFAULT",

    /**
     * 定时触发时的解锁方式：swipe / gesture / pin（见 [com.aliothmoon.maafw.domain.UnlockCredential]）
     *
     * 没有总开关：定时到点总要亮屏解锁（对齐 MaaMeow）。空串是「还没选过」，按「无密码」处理；
     * 老版本开过 PIN 解锁的用户由 WakeUnlockTypeMigration 迁成 pin，升级后不用重选
     */
    @PrefKey(default = "")
    val wakeUnlockType: String = "",

    /**
     * 解锁用的纯数字 PIN，**明文存在本 DataStore 里**
     *
     * 没上 Keystore 加密：解锁必须在特权进程里拿到明文才能注入按键，加密只是把明文
     * 挪到进程内存里晚出现一会儿，挡不住能读到 app 私有目录的攻击者。取舍写在
     * docs/scheduled-triggers.md；导出配置时必须清空这一项
     */
    @PrefKey(default = "")
    val wakeCredential: String = "",

    /** 单轮超过 [runDurationLimitMinutes] 就自动停；见 [com.aliothmoon.maafw.runner.RunDurationLimitHook] */
    @PrefKey(default = "false")
    val runDurationLimitEnabled: String = "false",

    @PrefKey(default = "${RunDurationLimit.DEFAULT_MINUTES}")
    val runDurationLimitMinutes: String = RunDurationLimit.DEFAULT_MINUTES.toString(),

    /** PI 声明了 `telemetry.sentry` 时才有意义；默认开，与 MXU 一致，用户可随时关闭 */
    @PrefKey(default = "true")
    val telemetryEnabled: String = "true",

    /** 启动时自动检查更新；只控启动自检，设置页手动检查不受它影响 */
    @PrefKey(default = "true")
    val autoCheckUpdate: String = "true",

    /** 启动自检发现新版本时自动下载并拉起安装器；关闭则弹窗询问。默认关，静默下载近 200MB 要用户先点头 */
    @PrefKey(default = "false")
    val autoDownloadUpdate: String = "false",

    /** [UpdateChannel] 的 name */
    @PrefKey(default = "STABLE")
    val updateChannel: String = "STABLE",

    /** [com.aliothmoon.maafw.update.UpdateSource] 的 name；检查与下载都只走这一个源 */
    @PrefKey(default = "MIRRORCHYAN")
    val updateSource: String = "MIRRORCHYAN",

    /** 后台模式运行中回桌面自动进画中画；只缩预览画面，小窗内无法操作 */
    @PrefKey(default = "true")
    val pipOnHome: String = "true",

    /** Mirror酱 CDK；只在更新源为 Mirror酱 时有意义，下载解析时带上 */
    @PrefKey(default = "")
    val mirrorchyanCdk: String = "",
)
