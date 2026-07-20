package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import com.aliothmoon.maafw.theme.MaaDesignTokens

/**
 * 单选胶囊 chip：宽度随文字自适应，配合 FlowRow 平铺换行；
 * 选中态 primaryContainer 底 + primary 描边（选项胶囊与分组页签共用此实现）。
 * leading 为可选前置装饰（如分组色点）。
 */
@Composable
fun MaaChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    role: Role? = null,
) {
    val interactionModifier = if (role == null) {
        Modifier.maaClickable(enabled = enabled, onClick = onClick)
    } else {
        Modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = role,
            onClick = onClick,
        )
    }
    Surface(
        shape = RoundedCornerShape(MaaDesignTokens.CornerRadius.button),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        border = BorderStroke(
            width = MaaDesignTokens.Separator.thickness,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
        ),
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .then(interactionModifier),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MaaDesignTokens.Spacing.md,
                vertical = MaaDesignTokens.Spacing.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
        ) {
            leading?.invoke()
            Text(
                text = label,
                // label 档位间隔过大（15/12sp），chip 取中间值 13sp
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

/** 内容宽度单选组；空间不足时整颗 chip 换行，不在等分单元内挤压标签。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> MaaSingleChoiceFlow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
    ) {
        options.forEach { (value, label) ->
            MaaChoiceChip(
                label = label,
                selected = selected == value,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = { onSelect(value) },
            )
        }
    }
}
