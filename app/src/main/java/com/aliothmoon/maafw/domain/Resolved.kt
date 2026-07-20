package com.aliothmoon.maafw.domain

/**
 * ProjectDefinition 与 UserConfiguration 的只读组合投影。
 * 不持久化，也不是执行结果。
 */
data class ResolvedProjectSession(
    val configurationList: List<ResolvedRunConfiguration>,
    val activeConfiguration: ResolvedRunConfiguration?,
    val taskCatalog: List<TaskCatalogGroup>,
    val environment: ResolvedEnvironment,
    val diagnostics: List<Diagnostic>,
)

data class ResolvedEnvironment(
    val controllerName: String,
    val resource: ResolvedResource?,
    val resourceCandidates: List<ResolvedResource>,
)

/** 运行匹配使用稳定内部名，UI 只展示已本地化 label。 */
data class ResolvedResource(
    val name: String,
    val label: String,
)

data class ResolvedRunConfiguration(
    val id: RunConfigurationId,
    val name: String,
    val isActive: Boolean,
    val tasks: List<ResolvedConfiguredTask>,
) {
    val enabledTaskCount: Int get() = tasks.count { it.enabled }
    val effectiveTaskCount: Int get() = tasks.count { it.effectiveEnabled }
}

/**
 * 任务不可用的结构化原因。领域层只表达语义，
 * 展示文案由 UI 层映射 string 资源（领域层无 Context，不做本地化）。
 */
sealed interface UnavailableReason {
    /** 配置引用的任务已不在项目定义中。 */
    data object MissingDefinition : UnavailableReason
    data class ControllerMismatch(val required: List<String>) : UnavailableReason
    data class ResourceMismatch(val required: List<String>) : UnavailableReason
}

data class ResolvedConfiguredTask(
    val instanceId: String,
    val taskName: String,
    val label: String,
    val description: String?,
    val enabled: Boolean,
    val applicable: Boolean,
    val missingDefinition: Boolean,
    val unavailableReason: UnavailableReason?,
    val options: List<OptionEditorState>,
) {
    /** 派生状态，不写回持久化；环境恢复兼容后启用意图自动恢复。 */
    val effectiveEnabled: Boolean get() = enabled && applicable && !missingDefinition
    val hasOptions: Boolean get() = options.isNotEmpty()
}

data class TaskCatalogGroup(
    val groupName: String,
    val label: String,
    val tasks: List<TaskCatalogItem>,
    /** 加载器合成的「未分组」兜底组；UI 据此用资源显示组名，不展示 label 原文。 */
    val isUngrouped: Boolean = false,
)

data class TaskCatalogItem(
    val taskName: String,
    val label: String,
    val description: String?,
    val applicable: Boolean,
    val unavailableReason: UnavailableReason?,
    val defaultChecked: Boolean,
)

enum class OptionKind { Select, Switch, Checkbox, Input }

/**
 * 单个 option 实例的编辑器投影：定义 + 当前值 + 活动子树。
 * UI 按 kind 选择控件，不直接递归解释原始 PI JSON。
 */
data class OptionEditorState(
    val name: String,
    val label: String,
    val description: String?,
    val kind: OptionKind,
    val depth: Int,
    /** 当前持久化值；null 表示 Unset。 */
    val value: OptionValue?,
    val cases: List<OptionCaseState>,
    val inputs: List<InputFieldState>,
) {
    /** 活动 case（含默认值回退）；Select/Switch 至多一个，Checkbox 按声明顺序。 */
    val activeCases: List<OptionCaseState> get() = cases.filter { it.active }
}

data class OptionCaseState(
    val name: String,
    val label: String,
    val description: String?,
    val active: Boolean,
    /** 仅活动 case 物化子树（active branch）；dormant 值保留在持久层不展示。 */
    val children: List<OptionEditorState>,
)

// 官方推荐 Yes/No（允许 Y/y）；on/true/开 等为本项目宽容超集
private val SWITCH_ON_NAMES = setOf("yes", "y", "on", "true", "enable", "开", "开启", "启用")

/**
 * 标准两态 Switch 的 (on, off) case；非标准两态返回 null（UI 回落 chip 平铺）。
 * PI switch 语义解释属于领域层，UI 与其他消费者共用同一份判定。
 */
fun OptionEditorState.standardSwitchCases(): Pair<OptionCaseState, OptionCaseState>? {
    if (kind != OptionKind.Switch || cases.size != 2) return null
    val onCase = cases.firstOrNull { it.name.lowercase() in SWITCH_ON_NAMES } ?: return null
    val offCase = cases.firstOrNull { it != onCase } ?: return null
    return onCase to offCase
}

data class InputFieldState(
    val name: String,
    val label: String,
    val pipelineType: PipelineType,
    val value: String,
    val default: String,
    val verify: Regex?,
    val patternMessage: String?,
    val description: String?,
) {
    val isValid: Boolean get() = validateInputCandidate(pipelineType, verify, value)
}

/** UI 即时校验与 Builder 复验共用的候选值规则（docs/dynamic-options.md §8）。 */
fun validateInputCandidate(type: PipelineType, verify: Regex?, candidate: String): Boolean {
    val typeOk = when (type) {
        PipelineType.StringType -> true
        PipelineType.IntType -> candidate.isEmpty() || candidate.toLongOrNull() != null
        PipelineType.BoolType -> candidate == "true" || candidate == "false"
    }
    if (!typeOk) return false
    return verify == null || verify.matches(candidate)
}
