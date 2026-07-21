package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.ProjectDefinition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val ioDispatcher: CoroutineDispatcher,
) : ProjectRepository {

    private val _state = MutableStateFlow<ProjectState>(ProjectState.Loading)
    override val state: StateFlow<ProjectState> = _state.asStateFlow()

    private val reloadMutex = Mutex()
    private var reloadGeneration = 0

    override suspend fun reload() {
        val generation = reloadMutex.withLock { ++reloadGeneration }
        _state.value = ProjectState.Loading
        val next = withContext(ioDispatcher) {
            when (val result = loader.load()) {
                is ProjectLoadResult.Ready -> ProjectState.Ready(result.definition, result.diagnostics)
                is ProjectLoadResult.Failure -> ProjectState.Error(result.diagnostics)
            }
        }
        // 仅最新一次 reload 写回，避免慢请求覆盖新结果
        reloadMutex.withLock {
            if (generation == reloadGeneration) {
                _state.value = next
            }
        }
    }
}
