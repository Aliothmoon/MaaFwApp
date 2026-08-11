package com.aliothmoon.maafw.notification

import androidx.compose.runtime.Immutable
import com.aliothmoon.maafw.domain.EventNotificationLevel
import com.aliothmoon.maafw.i18n.UiText

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
}

sealed interface NotificationEffect {
    data class ShowMessage(val message: UiText) : NotificationEffect
}
