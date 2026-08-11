package com.aliothmoon.maafw.ui.logs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.log.AppLogDetailViewModel
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import org.koin.androidx.compose.koinViewModel

/**
 * 一份错误日志的正文（二级页面）
 *
 * 横向滚动挂在 **LazyColumn 上**而不是每一行上（对齐 MaaMeow 的 `ErrorLogDetailView`）：
 * 挂在行上的话即使共用同一个 ScrollState 也是各滚各的，长短行会错位，读堆栈时对不上列
 *
 * 不换行、不截断：堆栈行很长，换行会把调用链拆得没法读；截尾则让用户看到残缺现场
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLogDetailScreen(
    fileName: String,
    onBack: () -> Unit,
    viewModel: AppLogDetailViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(fileName) { viewModel.load(fileName) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = { Text(fileName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state.lines.isEmpty()) {
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
                .padding(padding)
                .padding(horizontal = MaaDesignTokens.Spacing.lg)
                .horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
            contentPadding = PaddingValues(vertical = MaaDesignTokens.Spacing.lg),
        ) {
            // 行序固定（只读快照），索引就是稳定 key
            itemsIndexed(state.lines) { _, line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                    color = appLogLineColor(line),
                )
            }
        }
    }
}

/**
 * 按级别上色，只认本项目 `AppLogWriter` 那一种格式：`[时间] E tag: 正文`
 *
 * 不照抄 MaaMeow 的 `contains("[ERROR]")`——它的写入器打的是全词级别，我们打的是单字母，
 * 那个判据在这里一条也命不中。堆栈的续行没有时间戳，取不到级别就跟着默认色
 */
@Composable
private fun appLogLineColor(line: String): Color {
    val marker = line.indexOf(LEVEL_MARKER)
    if (marker < 0 || line.length <= marker + LEVEL_MARKER.length + 1) return Color.Unspecified
    if (line[marker + LEVEL_MARKER.length + 1] != ' ') return Color.Unspecified
    return when (line[marker + LEVEL_MARKER.length]) {
        'E', 'A' -> MaterialTheme.colorScheme.error
        'W' -> MaaTheme.palette.warning.content
        else -> Color.Unspecified
    }
}

private const val LEVEL_MARKER = "] "
