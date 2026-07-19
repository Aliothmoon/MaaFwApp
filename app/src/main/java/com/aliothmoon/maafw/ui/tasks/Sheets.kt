package com.aliothmoon.maafw.ui.tasks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.ResolvedConfiguredTask
import com.aliothmoon.maafw.domain.TaskCatalogGroup
import com.aliothmoon.maafw.domain.TaskCatalogItem
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaMotion
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.theme.MaaTone
import com.aliothmoon.maafw.ui.components.ExpandableTipContent
import com.aliothmoon.maafw.ui.components.ExpandableTipIcon
import com.aliothmoon.maafw.ui.components.MaaDescriptionPanel
import com.aliothmoon.maafw.ui.components.MaaMarkdown
import com.aliothmoon.maafw.ui.components.maaClickable
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.options.OptionEditorList

/**
 * 任务目录选择器：按 PI group 分组线性平铺，checkbox 多选后一次确认。
 * draft 只存在 UI 态，保持点选顺序，只有确认才发 ConfirmAddTasks；
 * 不标记也不限制已在配置中的任务，确认后一律追加为独立实例。
 * 支持搜索过滤。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTasksSheet(
    catalog: List<TaskCatalogGroup>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    // 有序 draft：同一 taskName 跨分组共享选中态
    val selected = remember(catalog) { mutableStateListOf<String>() }
    var query by rememberSaveable { mutableStateOf("") }
    // 顶部分组页签选中的组；目录变化时回落到首组
    var selectedGroupName by rememberSaveable(catalog) {
        mutableStateOf(catalog.firstOrNull()?.groupName.orEmpty())
    }
    // 分组色调按完整目录固定，过滤时不跳色
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 跳过半展开态，避免两段上拉
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // 内容区滚动不牵动 sheet，防止下滑误关；关闭走标题栏按钮/遮罩/返回键
        sheetGesturesEnabled = false,
        dragHandle = null,
    ) {
        Column(
            // 固定 3/5 高度，内容不足时由列表区留白 + 底部提示填充
            modifier = Modifier
                .fillMaxHeight(SHEET_HEIGHT_FRACTION)
                .padding(horizontal = MaaDesignTokens.Spacing.lg)
                .padding(top = MaaDesignTokens.Spacing.sm)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            SheetHeader(title = "添加任务", onClose = onDismiss)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索任务名称或说明") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "清除搜索")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "勾选的任务将按点选顺序追加到配置末尾，同一任务可重复添加",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val toggleTask: (String, Boolean) -> Unit = { taskName, checked ->
                if (checked) {
                    if (taskName !in selected) selected.add(taskName)
                } else {
                    selected.remove(taskName)
                }
            }
            when {
                filteredCatalog.isEmpty() -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "没有匹配「${query.trim()}」的任务",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 搜索态：跨全部分组过滤，保留组头标识来源
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
                                onCheckedChange = { toggleTask(item.taskName, it) },
                            )
                        }
                    }
                }

                // 浏览态：顶部分组页签 + 当前组任务列表
                else -> {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs)) {
                        items(catalog, key = { it.groupName }) { group ->
                            GroupTabChip(
                                label = group.label,
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
                                onCheckedChange = { toggleTask(item.taskName, it) },
                            )
                        }
                    }
                }
            }
            Button(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = MaaDesignTokens.Spacing.lg),
            ) {
                Text(if (selected.isEmpty()) "确认添加" else "确认添加（${selected.size}）")
            }
        }
    }

}

/** 分组页签：色点 + 组名，选中态主色容器底 + 主色描边。 */
@Composable
private fun GroupTabChip(
    label: String,
    tone: MaaTone,
    selected: Boolean,
    onClick: () -> Unit,
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
        modifier = Modifier.maaClickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MaaDesignTokens.Spacing.sm,
                vertical = MaaDesignTokens.Spacing.xs,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tone.content),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** 分组标题：accents 循环色点 + 同色组名（docs：分组徽章）。 */
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
            text = group.label,
            style = MaterialTheme.typography.titleSmall,
            color = tone.content,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 目录任务行：checkbox 多选，整行可点切换；不标记已添加状态。 */
@Composable
private fun CatalogRow(
    item: TaskCatalogItem,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    // 就地展开态：列表回收后收起即可，无需跨会话保存
    var expanded by remember(item.taskName) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .maaClickable(onClick = { onCheckedChange(!checked) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
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
                    text = item.unavailableReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                // 收起时不显示描述，说明只经 tip 图标展开（同 MaaMeow 语义）
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

/** 单个任务的 option 编辑 sheet：承载该任务全部 option 及活动子树。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskOptionSheet(
    task: ResolvedConfiguredTask,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 跳过半展开态，避免两段上拉
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // 内容区滚动不牵动 sheet，防止下滑误关；关闭走标题栏按钮/遮罩/返回键
        sheetGesturesEnabled = false,
        dragHandle = null,
    ) {
        Column(
            // 固定 3/5 高度；option 不足一屏时用任务说明填充剩余空间
            modifier = Modifier
                .fillMaxHeight(SHEET_HEIGHT_FRACTION)
                .padding(horizontal = MaaDesignTokens.Spacing.lg)
                .padding(top = MaaDesignTokens.Spacing.sm, bottom = MaaDesignTokens.Spacing.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            SheetHeader(title = task.label, onClose = onDismiss)
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
                    MaaCard(title = "任务说明") {
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

/** sheet 固定高度：屏幕的 3/5。 */
private const val SHEET_HEIGHT_FRACTION = 0.6f

/** 无拖拽把手后的统一标题栏：标题 + 显式关闭按钮。 */
@Composable
private fun SheetHeader(title: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        // 40dp 紧凑触控区：默认 48dp IconButton 会把标题行撑出多余空腔
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.Close, contentDescription = "关闭")
        }
    }
}

