package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.ResolvedResource
import com.aliothmoon.maafw.theme.MaaDesignTokens

/**
 * 资源单选的唯一一套 UI，任务页与设置页共用
 *
 * 之前设置页是 OutlinedButton + DropdownMenu、任务页是卡片 + bottom sheet，
 * 同一个 SelectResource 动作两副样子（docs/design-system.md 的一动作一形态）
 */
@Composable
fun ResourceSelectorRow(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MaaDesignTokens.Alpha.disabledContent)
    }
    MaaSelectableCard(
        selected = false,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(MaaDesignTokens.Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(MaaDesignTokens.IconSize.md),
            )
        }
    }
}

/** 点选即切换并关闭；选中项不重复发 Intent */
@Composable
fun ResourceSwitchSheet(
    candidates: List<ResolvedResource>,
    currentName: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    MaaModalSheet(onDismiss = onDismiss) { sheetModifier ->
        Column(
            modifier = sheetModifier,
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
        ) {
            MaaSheetHeader(title = stringResource(R.string.resource_switch_title), onClose = onDismiss)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                items(candidates, key = { it.name }) { resource ->
                    val selected = resource.name == currentName
                    MaaSelectableCard(
                        selected = selected,
                        onClick = {
                            onDismiss()
                            if (!selected) onSelect(resource.name)
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(MaaDesignTokens.Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                        ) {
                            MaaSelectionMarker(selected = selected)
                            MaaPiIcon(resource.icon, MaaDesignTokens.IconSize.md, null)
                            Text(
                                text = resource.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
