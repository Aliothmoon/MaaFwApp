package com.aliothmoon.maafw.project

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 可控的 ProjectRepository，供 ViewModel 单测注入 */
class FakeProjectRepository(
    initial: ProjectState = ProjectState.Loading,
) : ProjectRepository {

    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<ProjectState> = _state.asStateFlow()

    var reloadCount: Int = 0
        private set

    fun emit(state: ProjectState) {
        _state.value = state
    }

    override suspend fun reload() {
        reloadCount++
    }
}
