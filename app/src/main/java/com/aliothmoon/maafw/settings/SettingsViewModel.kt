package com.aliothmoon.maafw.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maafw.privileged.PermissionGateway
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置页的 Activity 作用域会话
 *
 * 与 [com.aliothmoon.maafw.session.SessionViewModel] 分开：后端选择是 app 设置而非运行配置，
 * 也不该混进那颗聚合态。目前只管后端；运行模式/overlay/屏保仍由 SessionViewModel 持有，
 * 因为 SessionUiState 直接消费它们（previewResolution 按 runMode 分支），挪走要动更多地方
 */
class SettingsViewModel(
    private val permissionGateway: PermissionGateway,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = permissionGateway.state
        .map { SettingsUiState(remoteAccess = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState(),
        )

    fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.SetBackend -> viewModelScope.launch {
                permissionGateway.setBackend(intent.backend)
            }
        }
    }
}
