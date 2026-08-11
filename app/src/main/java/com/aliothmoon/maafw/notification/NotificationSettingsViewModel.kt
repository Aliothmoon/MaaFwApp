package com.aliothmoon.maafw.notification

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maafw.settings.AppSettingsManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 通知设置页的 ViewModel
 *
 * 写入不做防抖：每个 [NotificationSettings] 字段都是文本框，逐字符落盘看着吓人，
 * 但 `@PrefSchema` 生成的 update 只写真正变了的键，且这一页不在运行热路径上
 */
class NotificationSettingsViewModel(
    private val settingsManager: NotificationSettingsManager,
    private val appSettingsManager: AppSettingsManager,
    private val externalService: ExternalNotificationService,
    private val eventNotifier: RunEventNotifier,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationUiState())
    val uiState: StateFlow<NotificationUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<NotificationEffect>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<NotificationEffect> = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            settingsManager.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        settings = settings,
                        enabledProviders = settings.enabledProviderIds().toSet(),
                    )
                }
            }
        }
        viewModelScope.launch {
            appSettingsManager.eventNotificationLevel.collect { level ->
                _uiState.update { it.copy(eventLevel = level) }
            }
        }
        viewModelScope.launch {
            externalService.feedbackMessages.collect { _effects.tryEmit(NotificationEffect.ShowMessage(it)) }
        }
    }

    fun onIntent(intent: NotificationIntent) {
        when (intent) {
            is NotificationIntent.UpdateSettings -> viewModelScope.launch {
                settingsManager.update(_uiState.value.settings.let(intent.transform))
            }

            is NotificationIntent.ToggleProvider -> viewModelScope.launch {
                val current = _uiState.value.settings
                // 用有序集合而不是 Set：enabledProviders 的顺序即发送顺序，
                // 每次开关都把已有渠道重排一遍会让用户排好的顺序无声地变掉
                val ids = current.enabledProviderIds().toMutableList()
                if (intent.enabled) {
                    if (intent.id !in ids) ids.add(intent.id)
                } else {
                    ids.remove(intent.id)
                }
                settingsManager.update(current.copy(enabledProviders = ids.joinToString(",")))
            }

            is NotificationIntent.SetEventLevel -> viewModelScope.launch {
                appSettingsManager.setEventNotificationLevel(intent.level)
            }

            is NotificationIntent.SendInternalTest ->
                eventNotifier.notifyTest(intent.title, intent.body)

            is NotificationIntent.SendExternalTest ->
                externalService.sendTest(intent.title, intent.body)
        }
    }
}
