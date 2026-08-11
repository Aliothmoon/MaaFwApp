package com.aliothmoon.maafw.privileged

/**
 * 目标 app 在虚拟屏上的看门狗状态
 *
 * [aidlValue] 对齐 RemoteService.watchdogState() 的 AIDL 契约（特权进程返回 int），
 * app 侧用 [fromAidl] 映射回枚举；PermissionGateway 把它投影给 ViewModel/预览徽标
 */
enum class WatchdogState(val aidlValue: Int) {
    IDLE(0),
    WATCHING(1),
    APP_DIED(2);

    companion object {
        fun fromAidl(value: Int): WatchdogState =
            entries.firstOrNull { it.aidlValue == value } ?: IDLE
    }
}
