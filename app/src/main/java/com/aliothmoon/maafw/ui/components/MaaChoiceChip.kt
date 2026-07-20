package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
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
) {
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
            .maaClickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MaaDesignTokens.Spacing.sm,
                vertical = MaaDesignTokens.Spacing.xs,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
        ) {
            leading?.invoke()
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}
