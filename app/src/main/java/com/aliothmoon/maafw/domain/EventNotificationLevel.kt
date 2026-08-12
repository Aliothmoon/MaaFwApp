package com.aliothmoon.maafw.domain

/**
 * 运行事件系统通知的档位；存在 `AppSettings`，与推送渠道那套（`NotificationSettings`）分开
 *
 * [OFF] 不发；[DEFAULT] 进状态栏不出声；[HIGH] 弹横幅并出声。两档对应两个 channel，
 * 建好之后重要性由用户在系统设置里说了算，app 改不动（见 RunEventNotifier）
 */
enum class EventNotificationLevel {
    OFF,
    DEFAULT,
    HIGH,
}
