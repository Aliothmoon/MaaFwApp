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
    /** 锁定唯一规则：runner 忙碌（见 RunnerPhase.isBusy）。 */
    val configurationLocked: Boolean
        get() = runner.phase.isBusy

    /** 面向用户的诊断合流：项目加载诊断 + 会话解析诊断（各页统一读这里）。 */
    val visibleDiagnostics: List<Diagnostic>
        get() = (projectState as? ProjectState.Ready)?.diagnostics.orEmpty() + sessionDiagnostics

    /** 启动按钮可用性的单一出处；Builder 侧仍会对可执行任务做最终校验。 */
    val canStart: Boolean
        get() = runner.phase == RunnerPhase.Idle && activeConfiguration != null
}

/** 封闭的语义 Intent，Screen 不直接构造 copy。 */
sealed interface SessionIntent {
    data class CreateConfiguration(val name: String) : SessionIntent

    /**
     * configurationName 为空白/null 时沿用模板名；taskNames 为 null 表示带入模板全部任务，
     * 非 null 时按模板声明顺序保留其中出现的任务。
     */
    data class CreateFromTemplate(
        val templateName: String,
        val configurationName: String? = null,
        val taskNames: List<String>? = null,
    ) : SessionIntent
    data class SelectConfiguration(val id: RunConfigurationId) : SessionIntent
    data class RenameConfiguration(val id: RunConfigurationId, val name: String) : SessionIntent
    data class DeleteConfiguration(val id: RunConfigurationId) : SessionIntent

    /** 允许重复 taskName：每个名称都新建一个独立 ConfiguredTask 实例。 */
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

    /** targetIndex 为移除原位置后目标插入位置。 */
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
     * 切换 App 语言（null = 跟随系统）。事实来源在平台侧 per-app locale（AppLocales），
     * 落地路径：Activity 重建 -> AppRoot 检测 locale 变化 -> ReloadProject 以新语言重载 PI。
     */
    data class SetLanguage(val localeTag: String?) : SessionIntent
    data class SetDeveloperMode(val enabled: Boolean) : SessionIntent
    data object ReloadProject : SessionIntent

    data object Start : SessionIntent
    data object Stop : SessionIntent
}

/** 一次性消息，不长期存进 UiState，不在重组时重复触发。 */
sealed interface SessionEffect {
    data class ShowMessage(val message: SessionMessage) : SessionEffect
    data class ShowDiagnostics(val diagnostics: List<Diagnostic>) : SessionEffect
}

/**
 * VM 一次性提示的结构化语义：VM 无 Context，文案由 UI 层映射 string 资源。
 * reason 载荷来自 Runner 契约，是面向排查的技术性文本，原样拼进提示尾部。
 */
sealed interface SessionMessage {
    data object ConfigurationLocked : SessionMessage
    data object ProjectNotLoaded : SessionMessage
    data object NoExecutableTasks : SessionMessage
    data class TemplateNotFound(val templateName: String) : SessionMessage
    data class CannotStart(val reason: String) : SessionMessage
    data class CannotStop(val reason: String) : SessionMessage
}
