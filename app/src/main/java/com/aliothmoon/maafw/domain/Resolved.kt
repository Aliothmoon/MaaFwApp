package com.aliothmoon.maafw.domain

/** 定义 × 用户状态的只读投影；不持久化、非执行结果 */
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

/** 匹配用内部名；UI 展示 label */
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

/** 领域只表达语义；文案由 UI 映射 string 资源 */
sealed interface UnavailableReason {
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
    /** 派生态，不写回；环境恢复后 enabled 意图自动生效 */
    val effectiveEnabled: Boolean get() = enabled && applicable && !missingDefinition
    val hasOptions: Boolean get() = options.isNotEmpty()
}

data class TaskCatalogGroup(
    val groupName: String,
    val label: String,
    val tasks: List<TaskCatalogItem>,
    /** 未分组兜底；UI 用资源显示组名，不展示 label 原文 */
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

/** option 编辑投影；UI 按 kind 选控件，不递归解释原始 PI JSON */
data class OptionEditorState(
    val name: String,
    val label: String,
    val description: String?,
    val kind: OptionKind,
    val depth: Int,
    /** null = Unset */
    val value: OptionValue?,
    val cases: List<OptionCaseState>,
    val inputs: List<InputFieldState>,
) {
    /** 含默认回退；Select/Switch 至多一个，Checkbox 按声明序 */
    val activeCases: List<OptionCaseState> get() = cases.filter { it.active }
}

data class OptionCaseState(
    val name: String,
    val label: String,
    val description: String?,
    val active: Boolean,
    /** 仅 active branch 物化子树；dormant 值留在持久层 */
    val children: List<OptionEditorState>,
)

// 官方推荐 Yes/No（含 Y/y）；on/true/开 等为本项目宽容超集
private val SWITCH_ON_NAMES = setOf("yes", "y", "on", "true", "enable", "开", "开启", "启用")

/** 标准两态 (on, off)；非标准返回 null，UI 回落 chip 平铺 */
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

/** UI 即时校验与 Builder 复验共用（docs/dynamic-options.md §8） */
fun validateInputCandidate(type: PipelineType, verify: Regex?, candidate: String): Boolean {
    val typeOk = when (type) {
        PipelineType.StringType -> true
        PipelineType.IntType -> candidate.isEmpty() || candidate.toLongOrNull() != null
        PipelineType.BoolType -> candidate == "true" || candidate == "false"
    }
    if (!typeOk) return false
    return verify == null || verify.matches(candidate)
}
