package com.aliothmoon.maafw.privileged

/**
 * 目标 app 在虚拟屏上的看门狗状态
 *
 * 异常结局分开：进程没了([APP_DIED])、进程还在但窗口跑了([DISPLAY_DRIFT])、
 * 虚拟屏还没有应用任务([VIRTUAL_DISPLAY_EMPTY])。前两者表示这一轮已丢目标；
 * 第三种只提示启动缺失或延迟，PI 稍后执行 start_app 时会自动转回 WATCHING。
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
    APP_DIED(3),

    /** 虚拟屏上还没有任何可识别的顶层应用任务 */
    VIRTUAL_DISPLAY_EMPTY(4);

    /** 两者都表示这一轮已经跑不下去了，UI 与运行日志按同一档处理 */
    val isLost: Boolean get() = this == DISPLAY_DRIFT || this == APP_DIED

    /** 空屏不一定是终局：PI 可能稍后才执行 start_app，因此只提示，不当成丢目标 */
    val needsNotice: Boolean get() = isLost || this == VIRTUAL_DISPLAY_EMPTY

    companion object {
        fun fromAidl(value: Int): WatchdogState =
            entries.firstOrNull { it.aidlValue == value } ?: IDLE
    }
}
