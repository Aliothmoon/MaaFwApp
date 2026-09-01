package com.aliothmoon.maafw.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.OptionEditorState
import com.aliothmoon.maafw.domain.OptionKind
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.standardSwitchCases
import com.aliothmoon.maafw.domain.validateInputCandidate
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaMarkdown
import com.aliothmoon.maafw.ui.components.MaaPiIcon
import com.aliothmoon.maafw.ui.components.MaaSwitch
import com.aliothmoon.maafw.ui.components.maaClickable

/**
 * 悬浮窗选项树：字号 / 开关 / 描述面板都按 overlay 密度，不套任务页 OptionEditorList
 */
@Composable
internal fun OverlayOptionEditorList(
    options: List<OptionEditorState>,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
    ) {
        options.forEach { option ->
            OverlayOptionItem(option, locked, onSetOption)
        }
    }
}

@Composable
private fun OverlayOptionItem(
    option: OptionEditorState,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
    ) {
        when (option.kind) {
            OptionKind.Select -> OverlaySelectEditor(option, locked, onSetOption)
            OptionKind.Switch -> OverlaySwitchEditor(option, locked, onSetOption)
            OptionKind.Checkbox -> OverlayCheckboxCasesEditor(option, locked, onSetOption)
            OptionKind.Input -> OverlayInputEditor(option, locked, onSetOption)
        }
        option.description?.let { OverlayDescription(it) }
        val children = option.activeCases.flatMap { it.children }
        if (children.isNotEmpty()) {
            OverlayOptionEditorList(
                options = children,
                locked = locked,
                onSetOption = onSetOption,
                modifier = Modifier.padding(start = MaaDesignTokens.Spacing.sm),
            )
        }
    }
}

@Composable
private fun OverlayOptionLabel(option: OptionEditorState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        MaaPiIcon(option.icon, MaaDesignTokens.IconSize.xs, null)
        Text(text = option.label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun OverlaySelectEditor(
    option: OptionEditorState,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs)) {
        OverlayOptionLabel(option)
        OverlayChoiceFlow(option, locked, multiple = false, onSetOption = onSetOption)
    }
}

@Composable
private fun OverlaySwitchEditor(
    option: OptionEditorState,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    val cases = option.standardSwitchCases()
    if (cases == null) {
        OverlaySelectEditor(option, locked, onSetOption)
        return
    }
    val (onCase, offCase) = cases
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        MaaPiIcon(option.icon, MaaDesignTokens.IconSize.xs, null)
        Text(
            text = option.label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
        )
        OverlaySwitch(
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
private fun OverlayCheckboxCasesEditor(
    option: OptionEditorState,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs)) {
        OverlayOptionLabel(option)
        OverlayChoiceFlow(option, locked, multiple = true, onSetOption = onSetOption)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverlayChoiceFlow(
    option: OptionEditorState,
    locked: Boolean,
    multiple: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    val activeNames = option.activeCases.map { it.name }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
    ) {
        option.cases.forEach { case ->
            OverlayChoiceChip(
                label = case.label,
                selected = case.active,
                enabled = !locked,
                leading = case.icon?.let { { MaaPiIcon(it, MaaDesignTokens.IconSize.xs, null) } },
                onClick = {
                    if (multiple) {
                        val updated = if (case.active) activeNames - case.name else activeNames + case.name
                        onSetOption(option.name, OptionValue.MultipleCases(updated))
                    } else {
                        onSetOption(option.name, OptionValue.SingleCase(case.name))
                    }
                },
            )
        }
    }
}

@Composable
private fun OverlayInputEditor(
    option: OptionEditorState,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs)) {
        OverlayOptionLabel(option)
        option.inputs.forEach { field ->
            var text by remember(option.name, field.name, field.value) { mutableStateOf(field.value) }
            val valid = validateInputCandidate(field.pipelineType, field.verify, text)
            val supporting: String? = if (valid) {
                field.description
            } else {
                field.patternMessage ?: stringResource(R.string.option_input_invalid)
            }
            OverlayField(
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
                hint = field.label,
                enabled = !locked,
                modifier = Modifier.fillMaxWidth(),
            )
            supporting?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (valid) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

@Composable
private fun OverlaySwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val scale = MaaDesignTokens.Overlay.switchScale
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Box(
            modifier = Modifier
                .size(
                    MaaDesignTokens.Overlay.switchTrackWidth * scale,
                    MaaDesignTokens.Overlay.switchTrackHeight * scale,
                )
                .wrapContentSize(unbounded = true, align = Alignment.Center)
                .scale(scale),
            contentAlignment = Alignment.Center,
        ) {
            MaaSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun OverlayChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(MaaTheme.style.radii.button),
        color = if (selected) scheme.primaryContainer else Color.Transparent,
        border = BorderStroke(
            MaaDesignTokens.Separator.thickness,
            if (selected) scheme.primary else scheme.outline,
        ),
        modifier = Modifier.maaClickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MaaDesignTokens.Spacing.sm,
                vertical = MaaDesignTokens.Spacing.xxs,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
        ) {
            leading?.invoke()
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            )
        }
    }
}

@Composable
private fun OverlayDescription(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(MaaTheme.style.radii.inner),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier.padding(
                horizontal = MaaDesignTokens.Spacing.xs,
                vertical = MaaDesignTokens.Spacing.xxs,
            ),
        ) {
            MaaMarkdown(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
