package com.aliothmoon.maafw.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.ResolvedConfiguredTask
import com.aliothmoon.maafw.domain.TaskCatalogGroup
import com.aliothmoon.maafw.domain.TaskCatalogItem
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.options.OptionEditorDivider
import com.aliothmoon.maafw.ui.options.OptionEditorList

/**
 * 任务目录选择器：按 PI group 分组线性平铺，多选保持点选顺序。
 * draft 只存在 UI 态，只有确认才发 ConfirmAddTasks（契约 §6）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTasksSheet(
    catalog: List<TaskCatalogGroup>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    // 有序 draft：同一 taskName 跨分组共享选中态
    val selected = remember { mutableStateListOf<String>() }

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
                .padding(top = MaaDesignTokens.Spacing.lg)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
        ) {
            SheetHeader(title = "添加任务", onClose = onDismiss)
            Text(
                text = "勾选的任务将按点选顺序追加到当前配置末尾",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
            ) {
                catalog.forEach { group ->
                    item(key = "group-${group.groupName}") {
                        Text(
                            text = group.groupName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = MaaDesignTokens.Spacing.sm),
                        )
                    }
                    items(group.tasks, key = { "${group.groupName}/${it.taskName}" }) { item ->
                        CatalogRow(
                            item = item,
                            checked = item.taskName in selected,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    if (item.taskName !in selected) selected.add(item.taskName)
                                } else {
                                    selected.remove(item.taskName)
                                }
                            },
                        )
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
                Text("确认添加（${selected.size}）")
            }
        }
    }
}

@Composable
private fun CatalogRow(
    item: TaskCatalogItem,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked || item.alreadyAdded,
            onCheckedChange = onCheckedChange,
            enabled = !item.alreadyAdded,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (item.applicable) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            when {
                item.alreadyAdded -> Text(
                    text = "已添加",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                item.unavailableReason != null -> Text(
                    text = item.unavailableReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
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
                .padding(top = MaaDesignTokens.Spacing.lg, bottom = MaaDesignTokens.Spacing.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
        ) {
            SheetHeader(title = task.label, onClose = onDismiss)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
            ) {
                OptionEditorList(
                    options = task.options,
                    locked = locked,
                    onSetOption = onSetOption,
                )
                task.description?.takeIf { it.isNotBlank() }?.let { description ->
                    OptionEditorDivider()
                    Text(
                        text = "任务说明",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = cleanProjectText(description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = "关闭")
        }
    }
}

/** 项目文本粗清洗：HTML 换行转 \n、去掉 markdown 图片引用。 */
private fun cleanProjectText(raw: String): String = raw
    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    .replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "")
    .trim()
