package com.aliothmoon.maafw.ui.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.log.RunLogArchiveIntent
import com.aliothmoon.maafw.log.RunLogArchiveViewModel
import com.aliothmoon.maafw.runner.RunSessionLogFile
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaCardSurface
import com.aliothmoon.maafw.ui.components.MaaPromptDialog
import com.aliothmoon.maafw.ui.components.maaClickable
import org.koin.androidx.compose.koinViewModel

/**
 * 历史运行日志的文件列表（二级页面）
 *
 * 每轮运行落一份，按开始时间倒序。摘要全部来自文件名与 stat，不读内容——
 * 为了显示一行摘要去解析每个文件的头，列表一多就卡
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunLogArchiveScreen(
    onBack: () -> Unit,
    onOpen: (fileName: String) -> Unit,
    viewModel: RunLogArchiveViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<RunSessionLogFile?>(null) }

    pendingDelete?.let { target ->
        MaaPromptDialog(
            title = stringResource(R.string.log_archive_delete_title),
            message = stringResource(
                R.string.log_archive_delete_message,
                logTimestamp(target.startedAt),
            ),
            icon = Icons.Outlined.DeleteOutline,
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.dialog_cancel),
            onConfirm = {
                viewModel.onIntent(RunLogArchiveIntent.Delete(target.fileName))
                pendingDelete = null
            },
            onDismissRequest = { pendingDelete = null },
            dismissOnOutsideClick = true,
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                // AppRoot 的 Scaffold 已吃掉状态栏顶部 inset，这里不能再加一次
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.log_archive_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    if (state.files.isNotEmpty()) {
                        TextButton(onClick = { viewModel.onIntent(RunLogArchiveIntent.CleanupOld) }) {
                            Text(stringResource(R.string.log_archive_cleanup))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(MaaDesignTokens.Spacing.lg),
                contentAlignment = Alignment.TopStart,
            ) {
                Text(
                    text = stringResource(
                        if (state.loading) R.string.common_loading else R.string.log_archive_empty,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(MaaDesignTokens.Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            items(state.files, key = { it.fileName }) { file ->
                ArchiveRow(
                    file = file,
                    onClick = { onOpen(file.fileName) },
                    onDelete = { pendingDelete = file },
                )
            }
        }
    }
}

@Composable
private fun ArchiveRow(
    file: RunSessionLogFile,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    MaaCardSurface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .maaClickable(onClick = onClick)
                .padding(MaaDesignTokens.Card.innerPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
            ) {
                Text(
                    text = logTimestamp(file.startedAt),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(
                        R.string.log_archive_meta,
                        file.taskCount,
                        formatFileSize(file.sizeBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(MaaDesignTokens.IconSize.md),
                )
            }
        }
    }
}
