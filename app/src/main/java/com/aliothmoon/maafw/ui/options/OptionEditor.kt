package com.aliothmoon.maafw.ui.options

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.domain.OptionEditorState
import com.aliothmoon.maafw.domain.OptionKind
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.validateInputCandidate
import com.aliothmoon.maafw.theme.MaaDesignTokens

/**
 * 动态 option 编辑器：按 OptionEditorState 树递归渲染，
 * 活动分支的子 option 以缩进线性平铺（docs/ui-implementation-notes.md B4）。
 * 状态变更一律通过 onSetOption(optionName, value) 发出，子 option 与父共用同一 task 作用域 value map。
 */
@Composable
fun OptionEditorList(
    options: List<OptionEditorState>,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
    ) {
        options.forEach { option ->
            OptionEditorItem(option, locked, onSetOption)
        }
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
        option.description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 活动分支子 option 递归渲染
        val children = option.activeCases.flatMap { it.children }
        if (children.isNotEmpty()) {
            OptionEditorList(children, locked, onSetOption)
        }
    }
}

@Composable
private fun SelectEditor(
    option: OptionEditorState,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = option.activeCases.firstOrNull()
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
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = !locked,
            ) {
                Text(
                    text = selected?.label ?: "未设置",
                    maxLines = 1,
                )
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                option.cases.forEach { case ->
                    DropdownMenuItem(
                        text = { Text(case.label) },
                        onClick = {
                            expanded = false
                            onSetOption(option.name, OptionValue.SingleCase(case.name))
                        },
                    )
                }
            }
        }
    }
}

private val SWITCH_ON_NAMES = setOf("yes", "on", "true", "enable", "开", "开启", "启用")

@Composable
private fun SwitchEditor(
    option: OptionEditorState,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    val onCase = option.cases.firstOrNull { it.name.lowercase() in SWITCH_ON_NAMES }
    val offCase = option.cases.firstOrNull { it != onCase }
    if (option.cases.size != 2 || onCase == null || offCase == null) {
        // 非标准两态 switch 回退为下拉选择
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
        Switch(
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
}

@Composable
private fun CheckboxEditor(
    option: OptionEditorState,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs)) {
        Text(text = option.label, style = MaterialTheme.typography.bodyLarge)
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

@Composable
fun OptionEditorDivider() {
    HorizontalDivider(
        thickness = MaaDesignTokens.Separator.thickness,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
