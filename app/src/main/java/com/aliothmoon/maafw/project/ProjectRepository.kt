package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.MaaDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.tracing.trace

sealed interface ProjectState {
    data object Loading : ProjectState
    data class Error(val diagnostics: List<Diagnostic>) : ProjectState
    data class Ready(val definition: ProjectDefinition, val diagnostics: List<Diagnostic>) : ProjectState
}

interface ProjectRepository {
    val state: StateFlow<ProjectState>
    suspend fun reload()
}

class DefaultProjectRepository(
    private val loader: ProjectLoader,
) : ProjectRepository {

    private val _state = MutableStateFlow<ProjectState>(ProjectState.Loading)
    override val state: StateFlow<ProjectState> = _state.asStateFlow()

    private val reloadMutex = Mutex()
    private var reloadGeneration = 0

    override suspend fun reload() {
        val generation = reloadMutex.withLock { ++reloadGeneration }
        _state.value = ProjectState.Loading
        // 段名与 StartupBenchmark.PROJECT_LOAD_SECTION 是一对，改一处要改两处
        val next = withContext(MaaDispatchers.IO) {
            trace(PROJECT_LOAD_TRACE) {
                when (val result = loader.load()) {
                    is ProjectLoadResult.Ready -> ProjectState.Ready(result.definition, result.diagnostics)
                    is ProjectLoadResult.Failure -> ProjectState.Error(result.diagnostics)
                }
            }
        }
        // 仅最新一次 reload 写回，避免慢请求覆盖新结果
        reloadMutex.withLock {
            if (generation == reloadGeneration) {
                _state.value = next
            }
        }
    }

    private companion object {
        const val PROJECT_LOAD_TRACE = "MaaProjectLoad"
    }
}
