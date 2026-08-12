package com.aliothmoon.maafw.notification

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextJoin
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.notification.provider.NotificationProvider
import com.aliothmoon.maafw.notification.provider.NotificationSendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 把一条消息投给全部已启用的推送渠道
 *
 */
class ExternalNotificationService(
    private val settingsManager: NotificationSettingsManager,
    providerList: List<NotificationProvider>,
    private val scope: CoroutineScope,
) {

    private val providers = providerList.associateBy(NotificationProvider::id)

    private val _feedbackMessages = MutableSharedFlow<UiText>(extraBufferCapacity = 16)
    val feedbackMessages: SharedFlow<UiText> = _feedbackMessages.asSharedFlow()

    fun send(title: String, content: String) {
        scope.launch(MaaDispatchers.IO) { dispatch(title, content, isTest = false) }
    }

    fun sendTest(title: String, content: String) {
        scope.launch(MaaDispatchers.IO) { dispatch(title, content, isTest = true) }
    }

    private suspend fun dispatch(title: String, content: String, isTest: Boolean) {
        val enabledIds = settingsManager.current().enabledProviderIds()
        if (enabledIds.isEmpty()) {
            if (isTest) _feedbackMessages.tryEmit(uiTextOf(R.string.notification_feedback_no_channel))
            return
        }

        val prefixedTitle = "$TITLE_PREFIX $title"

        for (id in enabledIds) {
            val provider = providers[id]
            if (provider == null) {
                // 渠道被下架或 id 改过，盘上留着旧值；跳过而不是报错，别让一个陈旧条目挡住其余渠道
                Timber.w("unknown notification provider: %s", id)
                continue
            }
            val result = runCatching { provider.send(prefixedTitle, content) }
                .getOrElse {
                    Timber.e(it, "notification provider %s threw", id)
                    NotificationSendResult.Transient(uiTextOf(R.string.notification_err_network))
                }

            when (result) {
                NotificationSendResult.Success ->
                    if (isTest) {
                        _feedbackMessages.tryEmit(
                            uiTextOf(R.string.notification_feedback_send_success, id),
                        )
                    }

                is NotificationSendResult.Failed -> {
                    Timber.w("notification provider %s failed", id)
                    emitFailure(id, result.message)
                }

                // 网络抖动只在测试时报：自动推送里报它等于每次断网都弹一次
                is NotificationSendResult.Transient -> {
                    Timber.w("notification provider %s transient failure", id)
                    if (isTest) emitFailure(id, result.message)
                }
            }
        }
    }

    private fun emitFailure(id: String, message: UiText) {
        _feedbackMessages.tryEmit(
            uiTextJoin(
                uiTextOf(R.string.notification_feedback_send_failed, id),
                message,
                separator = "：",
            ),
        )
    }

    private companion object {
        /** 多个应用共用一条推送渠道时，看得出这条是谁发的 */
        const val TITLE_PREFIX = "[MaaFwApp]"
    }
}
