package com.aliothmoon.maafw.ui.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
 * 长行换行，不挂横向滚动：`LazyColumn` 外面套 `horizontalScroll` 时列表宽度取的是当前可见的
 * 最长一行，竖向一滑宽度就变、横向偏移跟着被夹一次，真机上卡到滑不动
 *
 * 不截断：截尾等于让用户看半个现场
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

        // 在列表外算一次：给 Text 传 fontFamily 会每行每次重组合成一份新 TextStyle
        val lineStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
        val errorColor = MaterialTheme.colorScheme.error
        val warningColor = MaaTheme.palette.warning.content

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = MaaDesignTokens.Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
            contentPadding = PaddingValues(vertical = MaaDesignTokens.Spacing.lg),
        ) {
            // 行序固定（只读快照），索引就是稳定 key
            itemsIndexed(state.lines) { _, line ->
                Text(
                    text = line,
                    style = lineStyle,
                    color = appLogLineColor(line, errorColor, warningColor),
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
private fun appLogLineColor(line: String, error: Color, warning: Color): Color {
    val marker = line.indexOf(LEVEL_MARKER)
    if (marker < 0 || line.length <= marker + LEVEL_MARKER.length + 1) return Color.Unspecified
    if (line[marker + LEVEL_MARKER.length + 1] != ' ') return Color.Unspecified
    return when (line[marker + LEVEL_MARKER.length]) {
        'E', 'A' -> error
        'W' -> warning
        else -> Color.Unspecified
    }
}

private const val LEVEL_MARKER = "] "
