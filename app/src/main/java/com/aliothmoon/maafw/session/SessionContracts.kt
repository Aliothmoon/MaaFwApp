package com.aliothmoon.maafw.session

import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.ResolvedEnvironment
import com.aliothmoon.maafw.domain.ResolvedRunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.TaskCatalogGroup
import com.aliothmoon.maafw.domain.ThemeMode
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.runner.RunnerState

/**
 * Session* 指跨页面共享的整个工作会话（Activity 作用域），
 * 所有顶层目的地观察同一实例。UI 只观察聚合状态。
 */
data class SessionUiState(
    val projectState: ProjectState = ProjectState.Loading,
    val configurationList: List<ResolvedRunConfiguration> = emptyList(),
    val activeConfiguration: ResolvedRunConfiguration? = null,
    val taskCatalog: List<TaskCatalogGroup> = emptyList(),
    val environment: ResolvedEnvironment? = null,
    val sessionDiagnostics: List<Diagnostic> = emptyList(),
    val runner: RunnerState = RunnerState(),
    val themeMode: ThemeMode = ThemeMode.System,
    val developerMode: Boolean = false,
) {
    /** 锁定唯一规则：runner.phase in { Preparing, Running, Stopping }。 */
    val configurationLocked: Boolean
        get() = runner.phase == RunnerPhase.Preparing ||
            runner.phase == RunnerPhase.Running ||
            runner.phase == RunnerPhase.Stopping
}

/** 封闭的语义 Intent，Screen 不直接构造 copy。 */
sealed interface SessionIntent {
    data class CreateConfiguration(val name: String) : SessionIntent
    data class CreateFromTemplate(val templateName: String) : SessionIntent
    data class SelectConfiguration(val id: RunConfigurationId) : SessionIntent
    data class RenameConfiguration(val id: RunConfigurationId, val name: String) : SessionIntent
    data class DeleteConfiguration(val id: RunConfigurationId) : SessionIntent

    data class ConfirmAddTasks(
        val configurationId: RunConfigurationId,
        val orderedTaskNames: List<String>,
    ) : SessionIntent

    data class RemoveTask(val configurationId: RunConfigurationId, val taskName: String) : SessionIntent
    data class ToggleTask(
        val configurationId: RunConfigurationId,
        val taskName: String,
        val enabled: Boolean,
    ) : SessionIntent

    /** targetIndex 为移除原位置后目标插入位置。 */
    data class MoveTask(
        val configurationId: RunConfigurationId,
        val taskName: String,
        val targetIndex: Int,
    ) : SessionIntent

    data class SetTaskOption(
        val configurationId: RunConfigurationId,
        val taskName: String,
        val optionName: String,
        val value: OptionValue,
    ) : SessionIntent

    data class SelectResource(val resourceName: String) : SessionIntent
    data class SetThemeMode(val mode: ThemeMode) : SessionIntent
    data class SetDeveloperMode(val enabled: Boolean) : SessionIntent
    data object ReloadProject : SessionIntent

    data object Start : SessionIntent
    data object Stop : SessionIntent
}

/** 一次性消息，不长期存进 UiState，不在重组时重复触发。 */
sealed interface SessionEffect {
    data class ShowMessage(val message: String) : SessionEffect
    data class ShowDiagnostics(val diagnostics: List<Diagnostic>) : SessionEffect
}
