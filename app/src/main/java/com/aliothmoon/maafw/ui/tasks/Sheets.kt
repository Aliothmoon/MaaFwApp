package com.aliothmoon.maafw.ui.tasks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.ResolvedConfiguredTask
import com.aliothmoon.maafw.domain.ResolvedResource
import com.aliothmoon.maafw.domain.TaskCatalogGroup
import com.aliothmoon.maafw.domain.TaskCatalogItem
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaMotion
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.theme.MaaTone
import com.aliothmoon.maafw.ui.components.ExpandableTipContent
import com.aliothmoon.maafw.ui.components.ExpandableTipIcon
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaCardSurface
import com.aliothmoon.maafw.ui.components.MaaChoiceChip
import com.aliothmoon.maafw.ui.components.MaaDescriptionPanel
import com.aliothmoon.maafw.ui.components.MaaMarkdown
import com.aliothmoon.maafw.ui.components.MaaModalSheet
import com.aliothmoon.maafw.ui.components.MaaSheetHeader
import com.aliothmoon.maafw.ui.components.maaClickable
import com.aliothmoon.maafw.ui.i18n.displayLabel
import com.aliothmoon.maafw.ui.i18n.localized
import com.aliothmoon.maafw.ui.options.OptionEditorList

/**
 * 任务目录多选；draft 仅 UI 态，确认才发 ConfirmAddTasks
 * 不限制已添加任务，一律追加独立实例
 */
@Composable
fun AddTasksSheet(
    catalog: List<TaskCatalogGroup>,
    locked: Boolean = false,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    // 有序 draft；同 taskName 跨分组共享选中态
    val selected = remember(catalog) { mutableStateListOf<String>() }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedGroupName by rememberSaveable(catalog) {
        mutableStateOf(catalog.firstOrNull()?.groupName.orEmpty())
    }
    // 色调按完整目录固定，搜索过滤时不跳色
    val accents = MaaTheme.palette.accents
    val toneByGroup = remember(catalog, accents) {
        catalog.mapIndexed { index, group -> group.groupName to accents[index % accents.size] }.toMap()
    }
    val filteredCatalog = remember(catalog, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            catalog
        } else {
            catalog.mapNotNull { group ->
                val tasks = group.tasks.filter { task ->
                    task.label.contains(q, ignoreCase = true) ||
                        task.taskName.contains(q, ignoreCase = true) ||
                        task.description?.contains(q, ignoreCase = true) == true
                }
                if (tasks.isEmpty()) null else group.copy(tasks = tasks)
            }
        }
    }

    MaaModalSheet(onDismiss = onDismiss) { sheetModifier ->
        Column(
            modifier = sheetModifier,
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            MaaSheetHeader(title = stringResource(R.string.tasks_add_task), onClose = onDismiss)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.tasks_search_placeholder)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.tasks_search_clear))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.tasks_add_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val toggleTask: (String, Boolean) -> Unit = { taskName, checked ->
                selected.setPresent(taskName, checked)
            }
            when {
                filteredCatalog.isEmpty() -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.tasks_search_empty, query.trim()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                query.isNotBlank() -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
                ) {
                    filteredCatalog.forEach { group ->
                        item(key = "group-${group.groupName}") {
                            GroupHeader(
                                group = group,
                                tone = toneByGroup[group.groupName] ?: MaaTheme.palette.neutral,
                            )
                        }
                        items(group.tasks, key = { "${group.groupName}/${it.taskName}" }) { item ->
                            CatalogRow(
                                item = item,
                                checked = item.taskName in selected,
                                enabled = !locked,
                                onCheckedChange = { toggleTask(item.taskName, it) },
                            )
                        }
                    }
                }

                else -> {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs)) {
                        items(catalog, key = { it.groupName }) { group ->
                            GroupTabChip(
                                label = group.displayLabel(),
                                tone = toneByGroup[group.groupName] ?: MaaTheme.palette.neutral,
                                selected = group.groupName == selectedGroupName,
                                onClick = { selectedGroupName = group.groupName },
                            )
                        }
                    }
                    val currentGroup = catalog.firstOrNull { it.groupName == selectedGroupName }
                        ?: catalog.first()
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
                    ) {
                        items(currentGroup.tasks, key = { it.taskName }) { item ->
                            CatalogRow(
                                item = item,
                                checked = item.taskName in selected,
                                enabled = !locked,
                                onCheckedChange = { toggleTask(item.taskName, it) },
                            )
                        }
                    }
                }
            }
            Button(
                onClick = { onConfirm(selected.toList()) },
                enabled = !locked && selected.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = MaaDesignTokens.Spacing.lg),
            ) {
                Text(
                    if (selected.isEmpty()) {
                        stringResource(R.string.tasks_confirm_add)
                    } else {
                        stringResource(R.string.tasks_confirm_add_count, selected.size)
                    },
                )
            }
        }
    }

}

@Composable
private fun GroupTabChip(
    label: String,
    tone: MaaTone,
    selected: Boolean,
    onClick: () -> Unit,
) {
    MaaChoiceChip(
        label = label,
        selected = selected,
        onClick = onClick,
        leading = {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tone.content),
            )
        },
    )
}

/** 有序 draft：加入保持点选序，重复勾选不追加 */
internal fun MutableList<String>.setPresent(name: String, present: Boolean) {
    if (present) {
        if (name !in this) add(name)
    } else {
        remove(name)
    }
}

@Composable
private fun GroupHeader(group: TaskCatalogGroup, tone: MaaTone) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MaaDesignTokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(tone.content),
        )
        Text(
            text = group.displayLabel(),
            style = MaterialTheme.typography.titleSmall,
            color = tone.content,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CatalogRow(
    item: TaskCatalogItem,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    var expanded by remember(item.taskName) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .maaClickable(enabled = enabled, onClick = { onCheckedChange(!checked) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (item.applicable) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (!item.description.isNullOrBlank()) {
                    Spacer(Modifier.width(MaaDesignTokens.Spacing.xs))
                    ExpandableTipIcon(expanded = expanded, onExpandedChange = { expanded = it })
                }
            }
            when {
                item.unavailableReason != null -> Text(
                    text = item.unavailableReason.localized(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                !item.description.isNullOrBlank() -> ExpandableTipContent(visible = expanded) {
                    MaaMarkdown(
                        text = item.description,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
fun TaskOptionSheet(
    task: ResolvedConfiguredTask,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
    onDismiss: () -> Unit,
) {
    MaaModalSheet(onDismiss = onDismiss) { sheetModifier ->
        Column(
            modifier = sheetModifier.padding(bottom = MaaDesignTokens.Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            MaaSheetHeader(title = task.label, onClose = onDismiss)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            ) {
                OptionEditorList(
                    options = task.options,
                    locked = locked,
                    onSetOption = onSetOption,
                    carded = true,
                )
                task.description?.takeIf { it.isNotBlank() }?.let { description ->
                    MaaCard(title = stringResource(R.string.tasks_description_title)) {
                        MaaDescriptionPanel {
                            MaaMarkdown(
                                text = description,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 点选即切换；与设置页共用 SelectResource */
@Composable
fun ServerSwitchSheet(
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
            MaaSheetHeader(title = stringResource(R.string.server_switch_title), onClose = onDismiss)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                items(candidates, key = { it.name }) { resource ->
                    val selected = resource.name == currentName
                    ServerRowCard(
                        resource = resource,
                        selected = selected,
                        onClick = {
                            onDismiss()
                            if (!selected) onSelect(resource.name)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerRowCard(
    resource: ResolvedResource,
    selected: Boolean,
    onClick: () -> Unit,
) {
    MaaCardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .maaClickable(onClick = onClick),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            width = if (selected) 1.dp else MaaDesignTokens.Separator.thickness,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MaaDesignTokens.Spacing.md,
                vertical = MaaDesignTokens.Spacing.md,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .border(
                        width = 2.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        shape = CircleShape,
                    )
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    ),
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
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

