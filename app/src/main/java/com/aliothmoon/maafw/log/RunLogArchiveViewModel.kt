package com.aliothmoon.maafw.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maafw.runner.RunSessionLogFile
import com.aliothmoon.maafw.runner.RunSessionLogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 历史运行日志的文件列表
 */
data class RunLogArchiveUiState(
    val files: List<RunSessionLogFile> = emptyList(),
    val loading: Boolean = true,
)

sealed interface RunLogArchiveIntent {
    data object Refresh : RunLogArchiveIntent
    data class Delete(val fileName: String) : RunLogArchiveIntent

    /** 手动清一次过期的；平时靠 [RunSessionLogStore.cleanup] 的保留天数 */
    data object CleanupOld : RunLogArchiveIntent
}

class RunLogArchiveViewModel(private val store: RunSessionLogStore) : ViewModel() {

    private val _uiState = MutableStateFlow(RunLogArchiveUiState())
    val uiState: StateFlow<RunLogArchiveUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun onIntent(intent: RunLogArchiveIntent) {
        when (intent) {
            RunLogArchiveIntent.Refresh -> refresh()

            is RunLogArchiveIntent.Delete -> viewModelScope.launch {
                store.delete(intent.fileName)
                reload()
            }

            RunLogArchiveIntent.CleanupOld -> viewModelScope.launch {
                store.cleanup()
                reload()
            }
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            reload()
        }
    }

    private suspend fun reload() {
        _uiState.value = RunLogArchiveUiState(files = store.list(), loading = false)
    }
}
