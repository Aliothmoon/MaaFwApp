package com.aliothmoon.maafw.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maafw.runner.RunSessionLogStore
import com.aliothmoon.maafw.runner.RunSessionRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RunLogDetailUiState(
    val records: List<RunSessionRecord> = emptyList(),
    val loading: Boolean = true,
)

/**
 * 一份历史日志的正文
 *
 * 文件名经导航参数进来，由 Screen 调 [load]——每个 NavBackStackEntry 拿到独立实例，
 * 同一个 VM 不会串两份文件
 */
class RunLogDetailViewModel(private val store: RunSessionLogStore) : ViewModel() {

    private val _uiState = MutableStateFlow(RunLogDetailUiState())
    val uiState: StateFlow<RunLogDetailUiState> = _uiState.asStateFlow()

    fun load(fileName: String) {
        viewModelScope.launch {
            _uiState.value = RunLogDetailUiState(records = store.read(fileName), loading = false)
        }
    }
}
