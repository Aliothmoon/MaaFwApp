package com.aliothmoon.maafw.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaPiIcon
import com.aliothmoon.maafw.ui.components.MaaSelectableCard
import com.aliothmoon.maafw.ui.components.MaaSelectionMarker

/** 紧凑可选行目标高度（单行正文 + 描边，不垫 Checkbox 48dp） */
private val PickRowMinHeight = 40.dp

/**
 * 模板预览 / 添加任务共用的可选行
 *
 * 行首用配置列表同款 [MaaSelectionMarker]（IconContainer.xs=20dp），整卡可点切换
 */
@Composable
internal fun TaskPickRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    labelColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    trailing: (@Composable () -> Unit)? = null,
    belowLabel: (@Composable () -> Unit)? = null,
) {
    MaaSelectableCard(
        selected = checked,
        onClick = { onToggle(!checked) },
        enabled = enabled,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PickRowMinHeight)
                .padding(
                    horizontal = MaaDesignTokens.Spacing.sm,
                    vertical = MaaDesignTokens.Spacing.xxsLg,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            MaaSelectionMarker(selected = checked)
            MaaPiIcon(icon, MaaDesignTokens.IconSize.md, null)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = labelColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                belowLabel?.invoke()
            }
            trailing?.invoke()
        }
    }
}
