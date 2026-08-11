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
import com.aliothmoon.maafw.log.AppLogFileInfo
import com.aliothmoon.maafw.log.AppLogIntent
import com.aliothmoon.maafw.log.AppLogViewModel
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaCardSurface
import com.aliothmoon.maafw.ui.components.MaaPromptDialog
import com.aliothmoon.maafw.ui.components.maaClickable
import org.koin.androidx.compose.koinViewModel

/**
 * 错误日志的文件列表（二级页面）：app 自身的警告与错误，由 `AppLogWriter` 落盘
 *
 * 与运行历史同一形态的两级页；版面对齐 MaaMeow 的 `ErrorLogView`
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLogScreen(
    onBack: () -> Unit,
    onOpen: (fileName: String) -> Unit,
    viewModel: AppLogViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        MaaPromptDialog(
            title = stringResource(R.string.app_log_clear_title),
            message = stringResource(R.string.app_log_clear_message),
            icon = Icons.Outlined.DeleteOutline,
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.dialog_cancel),
            onConfirm = {
                viewModel.onIntent(AppLogIntent.ClearAll)
                confirmClear = false
            },
            onDismissRequest = { confirmClear = false },
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
                title = { Text(stringResource(R.string.app_log_title)) },
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
                        TextButton(onClick = { confirmClear = true }) {
                            Text(
                                text = stringResource(R.string.app_log_clear),
                                color = MaterialTheme.colorScheme.error,
                            )
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
                        if (state.loading) R.string.common_loading else R.string.app_log_empty,
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
            items(state.files, key = { it.name }) { file ->
                AppLogFileRow(file = file, onClick = { onOpen(file.name) })
            }
        }
    }
}

@Composable
private fun AppLogFileRow(file: AppLogFileInfo, onClick: () -> Unit) {
    MaaCardSurface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .maaClickable(onClick = onClick)
                .padding(MaaDesignTokens.Card.innerPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
            ) {
                Text(text = file.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(
                        R.string.app_log_meta,
                        formatFileSize(file.sizeBytes),
                        logTimestamp(file.lastModified),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
