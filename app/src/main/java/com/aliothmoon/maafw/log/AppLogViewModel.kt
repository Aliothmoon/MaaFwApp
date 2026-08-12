package com.aliothmoon.maafw.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maafw.MaaDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppLogFileInfo(
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

data class AppLogUiState(
    val files: List<AppLogFileInfo> = emptyList(),
    val loading: Boolean = true,
)

sealed interface AppLogIntent {
    data object ClearAll : AppLogIntent
}

/** 错误日志的文件列表；正文归 [AppLogDetailViewModel] */
class AppLogViewModel(
    private val writer: AppLogWriter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppLogUiState())
    val uiState: StateFlow<AppLogUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    fun onIntent(intent: AppLogIntent) {
        when (intent) {
            AppLogIntent.ClearAll -> viewModelScope.launch {
                // 必须 join：删除排在写入通道上，不等它刷出来的还是旧的那几份
                writer.purge().join()
                reload()
            }
        }
    }

    private suspend fun reload() {
        val files = withContext(MaaDispatchers.IO) {
            writer.listFiles().map {
                AppLogFileInfo(name = it.name, sizeBytes = it.length(), lastModified = it.lastModified())
            }
        }
        _uiState.value = AppLogUiState(files = files, loading = false)
    }
}
