package com.aliothmoon.maafw.notification

import androidx.compose.runtime.Immutable
import com.aliothmoon.maafw.domain.EventNotificationLevel
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.notification.live.LiveBackend
import com.aliothmoon.maafw.notification.live.LiveCapability

/**
 * 通知设置页的聚合态
 *
 * [settings] 整份带着走而不是拆成几十个字段：这一页就是那份 data class 的编辑面，
 * 拆开只会让每加一个渠道都要动三处
 */
@Immutable
data class NotificationUiState(
    val settings: NotificationSettings = NotificationSettings(),
    val enabledProviders: Set<String> = emptySet(),
    val eventLevel: EventNotificationLevel = EventNotificationLevel.DEFAULT,
    /** 运行通知：本机能力与实际生效的方式；首次读出来之前为 null */
    val live: LiveCapability? = null,
    /** 运行通知：用户选的方式；null 是没选过 */
    val livePreference: LiveBackend? = null,
)

sealed interface NotificationIntent {
    /** 改推送配置的任意字段；transform 在当前值上做 copy */
    data class UpdateSettings(val transform: NotificationSettings.() -> NotificationSettings) :
        NotificationIntent

    data class ToggleProvider(val id: String, val enabled: Boolean) : NotificationIntent

    data class SetEventLevel(val level: EventNotificationLevel) : NotificationIntent

    /** 走系统通知那条链发一条样例，验的是通知权限与档位 */
    data class SendInternalTest(val title: String, val body: String) : NotificationIntent

    /** 投给全部已启用渠道，逐条回结果 */
    data class SendExternalTest(val title: String, val body: String) : NotificationIntent

    data class SetLiveBackend(val backend: LiveBackend) : NotificationIntent

    /** 从系统设置回来时重读：焦点通知、实时更新、通知权限都可能刚改过 */
    data object RefreshLiveCapability : NotificationIntent

    /** 按当前生效的方式发一条假进度 */
    data class SendLiveTest(val title: String, val body: String) : NotificationIntent
}

sealed interface NotificationEffect {
    data class ShowMessage(val message: UiText) : NotificationEffect
}
