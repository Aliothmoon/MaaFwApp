package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.ProjectDefinition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    override suspend fun reload() {
        _state.value = ProjectState.Loading
        _state.value = withContext(ioDispatcher) {
            when (val result = loader.load()) {
                is ProjectLoadResult.Ready -> ProjectState.Ready(result.definition, result.diagnostics)
                is ProjectLoadResult.Failure -> ProjectState.Error(result.diagnostics)
            }
        }
    }
}
