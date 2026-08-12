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
import timber.log.Timber

data class AppLogDetailUiState(
    val lines: List<String> = emptyList(),
    val loading: Boolean = true,
)

/**
 * 一份错误日志的正文，整份读进来不截断
 *
 * 单份上限 4MB，`readLines()` 出来约四万个字符串——`LazyColumn` 只组合可见行，撑得住；
 * 截尾的话用户看到的是残缺现场，比多占点内存糟糕
 */
class AppLogDetailViewModel(
    private val writer: AppLogWriter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppLogDetailUiState())
    val uiState: StateFlow<AppLogDetailUiState> = _uiState.asStateFlow()

    fun load(fileName: String) {
        viewModelScope.launch {
            val lines = withContext(MaaDispatchers.IO) {
                val file = writer.listFiles().firstOrNull { it.name == fileName }
                    ?: return@withContext emptyList()
                runCatching { file.readLines() }.getOrElse {
                    Timber.w(it, "读错误日志失败：%s", fileName)
                    emptyList()
                }
            }
            _uiState.value = AppLogDetailUiState(lines = lines, loading = false)
        }
    }
}
