package com.aliothmoon.maafw.privileged

/**
 * 目标 app 在虚拟屏上的看门狗状态
 *
 * 两种坏结局分开：进程没了([APP_DIED])与进程还在但窗口跑了([DISPLAY_DRIFT])，
 * 用户要做的事完全不同——前者是应用被杀，后者多半是 ROM 把窗口挪回了主屏。
 * 名字对齐 MaaMeow 的 `appDiedEvent` / `displayDriftEvent`
 *
 * [aidlValue] 对齐 RemoteService.watchdogState() 的 AIDL 契约（特权进程返回 int），
 * app 侧用 [fromAidl] 映射回枚举；PermissionGateway 把它投影给 ViewModel/预览徽标
 */
enum class WatchdogState(val aidlValue: Int) {
    IDLE(0),
    WATCHING(1),

    /** 窗口离开虚拟屏且自动拉回失败；进程还活着 */
    DISPLAY_DRIFT(2),

    /** pidof 查不到进程 */
    APP_DIED(3);

    /** 两者都表示这一轮已经跑不下去了，UI 与运行日志按同一档处理 */
    val isLost: Boolean get() = this == DISPLAY_DRIFT || this == APP_DIED

    companion object {
        fun fromAidl(value: Int): WatchdogState =
            entries.firstOrNull { it.aidlValue == value } ?: IDLE
    }
}
