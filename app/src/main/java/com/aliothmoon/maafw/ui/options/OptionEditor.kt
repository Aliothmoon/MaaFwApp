package com.aliothmoon.maafw.ui.options

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.domain.OptionCaseState
import com.aliothmoon.maafw.domain.OptionEditorState
import com.aliothmoon.maafw.domain.OptionKind
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.standardSwitchCases
import com.aliothmoon.maafw.domain.validateInputCandidate
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaChoiceChip
import com.aliothmoon.maafw.ui.components.MaaDescriptionPanel
import com.aliothmoon.maafw.ui.components.MaaSwitch
import com.aliothmoon.maafw.ui.components.MaaMarkdown

/**
 * 动态 option 编辑器：按 OptionEditorState 树递归渲染，
 * 活动分支的子 option 以缩进线性平铺（docs/ui-implementation-notes.md B4）。
 * 状态变更一律通过 onSetOption(optionName, value) 发出，子 option 与父共用同一 task 作用域 value map。
 * carded = true 时顶层 option 各自包白卡片（option 名为卡片标题），子 option 仍在父卡内平铺。
 */
@Composable
fun OptionEditorList(
    options: List<OptionEditorState>,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
    modifier: Modifier = Modifier,
    carded: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(
            if (carded) MaaDesignTokens.Spacing.sm else MaaDesignTokens.Spacing.md,
        ),
    ) {
        options.forEach { option ->
            if (carded) {
                CardedOptionItem(option, locked, onSetOption)
            } else {
                OptionEditorItem(option, locked, onSetOption)
            }
        }
    }
}

/** 卡片形态：option 名升为卡片标题（容器实体名角色），标准两态 Switch 直接放标题行尾。 */
@Composable
private fun CardedOptionItem(
    option: OptionEditorState,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    val switchCases = option.standardSwitchCases()
    MaaCard(
        title = option.label,
        trailing = if (switchCases != null) {
            { OptionSwitch(option, switchCases, locked, onSetOption) }
        } else {
            null
        },
    ) {
        when (option.kind) {
            OptionKind.Select, OptionKind.Switch ->
                if (switchCases == null) ChoiceChipFlow(option, locked, onSetOption)

            OptionKind.Checkbox -> CheckboxCases(option, locked, onSetOption)
            OptionKind.Input -> InputFields(option, locked, onSetOption)
        }
        OptionDescriptionAndChildren(option, locked, onSetOption)
    }
}

@Composable
private fun OptionEditorItem(
    option: OptionEditorState,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (option.depth * 16).dp),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
    ) {
        when (option.kind) {
            OptionKind.Select -> SelectEditor(option, locked, onSetOption)
            OptionKind.Switch -> SwitchEditor(option, locked, onSetOption)
            OptionKind.Checkbox -> CheckboxEditor(option, locked, onSetOption)
            OptionKind.Input -> InputEditor(option, locked, onSetOption)
        }
        OptionDescriptionAndChildren(option, locked, onSetOption)
    }
}

@Composable
private fun OptionDescriptionAndChildren(
    option: OptionEditorState,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    option.description?.let {
        MaaDescriptionPanel {
            MaaMarkdown(
                text = it,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
    // 活动分支子 option 递归渲染（卡片内不再嵌套卡片）
    val children = option.activeCases.flatMap { it.children }
    if (children.isNotEmpty()) {
        OptionEditorList(children, locked, onSetOption)
    }
}

@Composable
private fun SelectEditor(
    option: OptionEditorState,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs)) {
        Text(text = option.label, style = MaterialTheme.typography.bodyLarge)
        ChoiceChipFlow(option, locked, onSetOption)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceChipFlow(
    option: OptionEditorState,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
    ) {
        option.cases.forEach { case ->
            MaaChoiceChip(
                label = case.label,
                selected = case.active,
                enabled = !locked,
                onClick = { onSetOption(option.name, OptionValue.SingleCase(case.name)) },
            )
        }
    }
}

@Composable
private fun OptionSwitch(
    option: OptionEditorState,
    cases: Pair<OptionCaseState, OptionCaseState>,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    val (onCase, offCase) = cases
    MaaSwitch(
        checked = option.activeCases.firstOrNull()?.name == onCase.name,
        onCheckedChange = { checked ->
            onSetOption(
                option.name,
                OptionValue.SingleCase(if (checked) onCase.name else offCase.name),
            )
        },
        enabled = !locked,
    )
}

@Composable
private fun SwitchEditor(
    option: OptionEditorState,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    val cases = option.standardSwitchCases()
    if (cases == null) {
        // 非标准两态 switch 回退为 chip 平铺
        SelectEditor(option, locked, onSetOption)
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = option.label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f, fill = false),
        )
        OptionSwitch(option, cases, locked, onSetOption)
    }
}

@Composable
private fun CheckboxEditor(
    option: OptionEditorState,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs)) {
        Text(text = option.label, style = MaterialTheme.typography.bodyLarge)
        CheckboxCases(option, locked, onSetOption)
    }
}

@Composable
private fun CheckboxCases(
    option: OptionEditorState,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs)) {
        val activeNames = option.activeCases.map { it.name }
        option.cases.forEach { case ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = case.active,
                    onCheckedChange = { checked ->
                        val updated = if (checked) activeNames + case.name else activeNames - case.name
                        // 明确的空选择也是合法值（MultipleCases(emptyList())），与 Unset 区分
                        onSetOption(option.name, OptionValue.MultipleCases(updated))
                    },
                    enabled = !locked,
                )
                Text(text = case.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun InputEditor(
    option: OptionEditorState,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
        Text(text = option.label, style = MaterialTheme.typography.bodyLarge)
        InputFields(option, locked, onSetOption)
    }
}

@Composable
private fun InputFields(
    option: OptionEditorState,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
        option.inputs.forEach { field ->
            // 本地保留正在输入的候选值；仅合法候选立即提交（UI 立即拒绝，Builder 再校验）
            var text by remember(option.name, field.name, field.value) { mutableStateOf(field.value) }
            val valid = validateInputCandidate(field.pipelineType, field.verify, text)
            OutlinedTextField(
                value = text,
                onValueChange = { candidate ->
                    text = candidate
                    if (validateInputCandidate(field.pipelineType, field.verify, candidate)) {
                        val values = option.inputs.associate {
                            it.name to (if (it.name == field.name) candidate else it.value)
                        }
                        onSetOption(option.name, OptionValue.Inputs(values))
                    }
                },
                label = { Text(field.name) },
                supportingText = {
                    when {
                        !valid -> Text(field.patternMessage ?: "输入不合法")
                        field.description != null -> Text(field.description)
                    }
                },
                isError = !valid,
                enabled = !locked,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
