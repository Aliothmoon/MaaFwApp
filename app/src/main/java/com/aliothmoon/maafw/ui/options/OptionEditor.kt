package com.aliothmoon.maafw.ui.options

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
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
import com.aliothmoon.maafw.ui.components.MaaLabeledControlRow
import com.aliothmoon.maafw.ui.components.MaaSwitch
import com.aliothmoon.maafw.ui.components.MaaMarkdown

/**
 * 按 OptionEditorState 树渲染；子 option 与父共用 task 级 value map
 * carded：顶层各包白卡；缩进/字号见 docs/ui-implementation-notes.md B4
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
        modifier = Modifier.fillMaxWidth(),
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
    val children = option.activeCases.flatMap { it.children }
    if (children.isNotEmpty()) {
        OptionEditorList(
            options = children,
            locked = locked,
            onSetOption = onSetOption,
            modifier = Modifier.padding(start = MaaDesignTokens.Spacing.sm),
        )
    }
}

@Composable
private fun optionLabelStyle(depth: Int) = when {
    depth <= 1 -> MaterialTheme.typography.bodyLarge
    depth == 2 -> MaterialTheme.typography.bodyMedium
    else -> MaterialTheme.typography.bodySmall
}

@Composable
private fun SelectEditor(
    option: OptionEditorState,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs)) {
        Text(text = option.label, style = optionLabelStyle(option.depth))
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
        SelectEditor(option, locked, onSetOption)
        return
    }
    MaaLabeledControlRow(
        label = option.label,
        labelStyle = optionLabelStyle(option.depth),
        trailing = { OptionSwitch(option, cases, locked, onSetOption) },
    )
}

@Composable
private fun CheckboxEditor(
    option: OptionEditorState,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs)) {
        Text(text = option.label, style = optionLabelStyle(option.depth))
        CheckboxCases(option, locked, onSetOption)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckboxCases(
    option: OptionEditorState,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
    ) {
        val activeNames = option.activeCases.map { it.name }
        option.cases.forEach { case ->
            MaaChoiceChip(
                label = case.label,
                selected = case.active,
                enabled = !locked,
                onClick = {
                    val updated = if (case.active) activeNames - case.name else activeNames + case.name
                    // emptyList() 合法，与 Unset 区分
                    onSetOption(option.name, OptionValue.MultipleCases(updated))
                },
            )
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
        Text(text = option.label, style = optionLabelStyle(option.depth))
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
            // 仅合法候选立即提交；UI 先拒，Builder 再验
            var text by remember(option.name, field.name, field.value) { mutableStateOf(field.value) }
            val valid = validateInputCandidate(field.pipelineType, field.verify, text)
            val supporting: String? = if (valid) {
                field.description
            } else {
                field.patternMessage ?: stringResource(R.string.option_input_invalid)
            }
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
                label = { Text(field.label) },
                // 同 ScheduleEditScreen：既无错误也无描述时必须传 null，
                // 否则空槽照样占一行高，一列输入框之间会撑出空带
                supportingText = supporting?.let { message -> { Text(message) } },
                isError = !valid,
                enabled = !locked,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
