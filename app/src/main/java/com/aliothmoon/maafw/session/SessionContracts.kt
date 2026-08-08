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
import com.aliothmoon.maafw.runner.isBusy

/** 跨页工作会话聚合态；Activity 作用域单实例，UI 只读 */
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
    /** 唯一锁定规则：RunnerPhase.isBusy */
    val configurationLocked: Boolean
        get() = runner.phase.isBusy

    /** 项目加载诊断 + 会话解析诊断合流 */
    val visibleDiagnostics: List<Diagnostic>
        get() = (projectState as? ProjectState.Ready)?.diagnostics.orEmpty() + sessionDiagnostics

    val canStart: Boolean
        get() = runner.phase == RunnerPhase.Idle && activeConfiguration != null
}

sealed interface SessionIntent {
    data class CreateConfiguration(val name: String) : SessionIntent

    /**
     * configurationName 空白/null 沿用模板名
     * taskNames null = 模板全部任务，非 null 按模板声明序过滤
     */
    data class CreateFromTemplate(
        val templateName: String,
        val configurationName: String? = null,
        val taskNames: List<String>? = null,
    ) : SessionIntent
    data class SelectConfiguration(val id: RunConfigurationId) : SessionIntent
    data class DuplicateConfiguration(val id: RunConfigurationId, val name: String) : SessionIntent
    data class RenameConfiguration(val id: RunConfigurationId, val name: String) : SessionIntent
    data class DeleteConfiguration(val id: RunConfigurationId) : SessionIntent

    /** 每个名称追加独立 ConfiguredTask 实例（可重复 taskName） */
    data class ConfirmAddTasks(
        val configurationId: RunConfigurationId,
        val orderedTaskNames: List<String>,
    ) : SessionIntent

    data class RemoveTask(
        val configurationId: RunConfigurationId,
        val taskInstanceId: String,
    ) : SessionIntent

    data class ToggleTask(
        val configurationId: RunConfigurationId,
        val taskInstanceId: String,
        val enabled: Boolean,
    ) : SessionIntent

    /** targetIndex：移除原位置后的插入下标 */
    data class MoveTask(
        val configurationId: RunConfigurationId,
        val taskInstanceId: String,
        val targetIndex: Int,
    ) : SessionIntent

    data class SetTaskOption(
        val configurationId: RunConfigurationId,
        val taskInstanceId: String,
        val optionName: String,
        val value: OptionValue,
    ) : SessionIntent

    data class SelectResource(val resourceName: String) : SessionIntent
    data class SetThemeMode(val mode: ThemeMode) : SessionIntent

    /**
     * null = 跟随系统；事实来源 AppLocales
     * 落地：Activity 重建 → AppRoot 检测 locale → ReloadProject
     */
    data class SetLanguage(val localeTag: String?) : SessionIntent
    data class SetDeveloperMode(val enabled: Boolean) : SessionIntent
    data object ReloadProject : SessionIntent

    data object Start : SessionIntent
    data object Stop : SessionIntent
}

/** 一次性 Effect，不进 UiState */
sealed interface SessionEffect {
    data class ShowMessage(val message: SessionMessage) : SessionEffect
    data class ShowDiagnostics(val diagnostics: List<Diagnostic>) : SessionEffect
}

/** VM 无 Context；reason 为 Runner 技术文本，UI 拼进提示尾部 */
sealed interface SessionMessage {
    data object ConfigurationLocked : SessionMessage
    data object ProjectNotLoaded : SessionMessage
    data object NoExecutableTasks : SessionMessage
    data class TemplateNotFound(val templateName: String) : SessionMessage
    data class CannotStart(val reason: String) : SessionMessage
    data class CannotStop(val reason: String) : SessionMessage
}
