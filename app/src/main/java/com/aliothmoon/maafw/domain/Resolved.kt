package com.aliothmoon.maafw.domain

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf

/** 定义 × 用户状态的只读投影；不持久化、非执行结果 */
data class ResolvedProjectSession(
    val configurationList: List<ResolvedRunConfiguration>,
    val activeConfiguration: ResolvedRunConfiguration?,
    val taskCatalog: List<TaskCatalogGroup>,
    /** PI `global_option[]` 的编辑投影，按声明顺序；不随运行配置走 */
    val globalOptions: List<OptionEditorState>,
    /** PI `setting[]` 分区：[globalOptions] 里的同一批投影，只是分了组；没有可见选项的分区不出现 */
    val settingSections: List<OptionSectionState> = emptyList(),
    /** 当前选中 resource 的 `option[]`；换资源换这份，值按 resource name 分桶 */
    val resourceOptions: List<OptionEditorState> = emptyList(),
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
    val icon: String? = null,
)

data class ResolvedRunConfiguration(
    val id: RunConfigurationId,
    val name: String,
    val isActive: Boolean,
    val tasks: List<ResolvedConfiguredTask>,
)

/**
 * 任务不适用的原因文案
 * `applicable` 才是判定位；这里只承担展示，切语言后由 UI 重新解析
 */
object UnavailableReasons {
    fun missingDefinition(): UiText = uiTextOf(R.string.task_unavailable_missing)

    fun controllerMismatch(required: List<String>): UiText =
        uiTextOf(R.string.task_unavailable_controller, required.joinToString())

    fun resourceMismatch(required: List<String>): UiText =
        uiTextOf(R.string.task_unavailable_resource, required.joinToString())
}

data class ResolvedConfiguredTask(
    val instanceId: String,
    val taskName: String,
    val label: String,
    val description: String?,
    val enabled: Boolean,
    val applicable: Boolean,
    val missingDefinition: Boolean,
    val unavailableReason: UiText?,
    val options: List<OptionEditorState>,
    val icon: String? = null,
) {
    /** 派生态，不写回；环境恢复后 enabled 意图自动生效 */
    val effectiveEnabled: Boolean get() = enabled && applicable && !missingDefinition
    val hasOptions: Boolean get() = options.isNotEmpty()
}

data class TaskCatalogGroup(
    val groupName: String,
    val label: String,
    val tasks: List<TaskCatalogItem>,
    val icon: String? = null,
    /** 未分组兜底；UI 用资源显示组名，不展示 label 原文 */
    val isUngrouped: Boolean = false,
)

data class TaskCatalogItem(
    val taskName: String,
    val label: String,
    val description: String?,
    val applicable: Boolean,
    val unavailableReason: UiText?,
    val defaultChecked: Boolean,
    val icon: String? = null,
)

/** PI v2.8.0 `setting` 分区的展示投影；选项按分区声明的顺序排 */
data class OptionSectionState(
    val name: String,
    val label: String,
    val description: String?,
    val icon: String?,
    val defaultExpand: Boolean,
    val options: List<OptionEditorState>,
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
    val icon: String? = null,
    /** 仅 Checkbox 有意义，见 [OptionDefinition.Checkbox.minCount] */
    val minCount: Int = 0,
    val maxCount: Int? = null,
) {
    /** 含默认回退；Select/Switch 至多一个，Checkbox 按声明序 */
    val activeCases: List<OptionCaseState> get() = cases.filter { it.active }

    val countRule: UiText? get() = checkboxCountRule(minCount, maxCount)

    val belowMinCount: Boolean get() = activeCases.size < minCount

    /** 选满 [maxCount] 后未选的 case 不能再点；已选的始终能取消，PI 收紧上限后旧选择才减得下来 */
    fun canToggle(case: OptionCaseState): Boolean =
        case.active || maxCount == null || activeCases.size < maxCount
}

/** checkbox 选择数量的要求，UI 提示与运行期诊断共用；不限时为 null */
fun checkboxCountRule(minCount: Int, maxCount: Int?): UiText? = when {
    maxCount == null -> if (minCount > 0) uiTextOf(R.string.option_checkbox_count_at_least, minCount) else null
    minCount == maxCount -> uiTextOf(R.string.option_checkbox_count_exactly, minCount)
    minCount > 0 -> uiTextOf(R.string.option_checkbox_count_between, minCount, maxCount)
    else -> uiTextOf(R.string.option_checkbox_count_at_most, maxCount)
}

data class OptionCaseState(
    val name: String,
    val label: String,
    val description: String?,
    val icon: String? = null,
    val active: Boolean,
    /** 仅 active branch 物化子树；dormant 值留在持久层 */
    val children: List<OptionEditorState>,
)

private val SWITCH_ON_NAMES = setOf("yes", "y", "on", "true", "enable")

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
    /** 输入框掩码，不回显原文 */
    val password: Boolean = false,
)

/** UI 即时校验与 Builder 复验共用（docs/domain-model.md §6.6） */
fun validateInputCandidate(type: PipelineType, verify: Regex?, candidate: String): Boolean {
    val typeOk = when (type) {
        PipelineType.StringType -> true
        PipelineType.IntType -> candidate.isEmpty() || candidate.toLongOrNull() != null
        PipelineType.BoolType -> candidate == "true" || candidate == "false"
    }
    if (!typeOk) return false
    return verify == null || verify.matches(candidate)
}
