package com.aliothmoon.maafw.ui.tasks

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.ResolvedConfiguredTask
import com.aliothmoon.maafw.domain.TaskCatalogGroup
import com.aliothmoon.maafw.domain.TaskCatalogItem
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.theme.MaaTone
import com.aliothmoon.maafw.ui.components.MaaButton
import com.aliothmoon.maafw.ui.components.ExpandableTipContent
import com.aliothmoon.maafw.ui.components.ExpandableTipIcon
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaChoiceChip
import com.aliothmoon.maafw.ui.components.MaaDescriptionPanel
import com.aliothmoon.maafw.ui.components.MaaMarkdown
import com.aliothmoon.maafw.ui.components.MaaModalSheet
import com.aliothmoon.maafw.ui.components.MaaPiIcon
import com.aliothmoon.maafw.ui.components.MaaSheetHeader
import com.aliothmoon.maafw.ui.i18n.asUiText
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
    MaaModalSheet(onDismiss = onDismiss) { sheetModifier ->
        AddTasksContent(
            catalog = catalog,
            locked = locked,
            onConfirm = onConfirm,
            onClose = onDismiss,
            modifier = sheetModifier,
        )
    }
}

/**
 * 添加任务的正文；任务页 sheet 用。悬浮窗有自己的紧凑目录，不走这里
 */
@Composable
internal fun AddTasksContent(
    catalog: List<TaskCatalogGroup>,
    locked: Boolean,
    onConfirm: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    val selected = remember(catalog) { mutableStateListOf<String>() }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedGroupName by rememberSaveable(catalog) {
        mutableStateOf(catalog.firstOrNull()?.groupName.orEmpty())
    }
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

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        if (onClose != null) {
            MaaSheetHeader(title = stringResource(R.string.tasks_add_task), onClose = onClose)
        }
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
                    verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
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
                                label = group.asUiText().asString(),
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
                        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
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
            MaaButton(
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
                    .size(MaaDesignTokens.IconSize.dotSm)
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
        if (group.icon != null) {
            MaaPiIcon(group.icon, MaaDesignTokens.IconSize.sm, null)
        } else {
            Box(
                modifier = Modifier
                    .size(MaaDesignTokens.IconSize.dotMd)
                    .clip(CircleShape)
                    .background(tone.content),
            )
        }
        Text(
            text = group.asUiText().asString(),
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
    val hasDescription = !item.description.isNullOrBlank()
    // 说明不嵌在选项行里：点 i 后在整行下方另起一块展开，与可选行并列
    Column(
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxsLg),
    ) {
        TaskPickRow(
            label = item.label,
            icon = item.icon,
            checked = checked,
            enabled = enabled,
            onToggle = onCheckedChange,
            labelColor = if (item.applicable) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            trailing = if (hasDescription) {
                {
                    ExpandableTipIcon(expanded = expanded, onExpandedChange = { expanded = it })
                }
            } else {
                null
            },
            belowLabel = item.unavailableReason?.let { reason ->
                {
                    Text(
                        text = reason.asString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
        if (hasDescription) {
            ExpandableTipContent(visible = expanded) {
                MaaMarkdown(
                    text = item.description.orEmpty(),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
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
        TaskOptionContent(
            task = task,
            locked = locked,
            onSetOption = onSetOption,
            onClose = onDismiss,
            modifier = sheetModifier.padding(bottom = MaaDesignTokens.Spacing.xl),
        )
    }
}

@Composable
internal fun TaskOptionContent(
    task: ResolvedConfiguredTask,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        if (onClose != null) {
            MaaSheetHeader(title = task.label, onClose = onClose)
        } else {
            Text(text = task.label, style = MaterialTheme.typography.titleMedium)
        }
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

