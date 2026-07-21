package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.theme.MaaDesignTokens

/**
 * 统一的 modal sheet 脚手架：固定 3/5 高度、跳过半展开、禁用手势拖拽
 * （内容区滚动不牵动 sheet，关闭走标题栏按钮/遮罩/返回键）、无拖拽把手。
 * content 收到的 Modifier 已带高度/水平边距/顶部间距/导航栏 inset，
 * 调用方按需追加 imePadding、底部间距等。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaaModalSheet(
    onDismiss: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        dragHandle = null,
    ) {
        content(
            Modifier
                .fillMaxHeight(MaaDesignTokens.Sheet.heightFraction)
                .padding(horizontal = MaaDesignTokens.Spacing.lg)
                .padding(top = MaaDesignTokens.Spacing.sm)
                .navigationBarsPadding(),
        )
    }
}

/** 无拖拽把手 sheet 的统一标题栏：可选返回键 + 标题 + 关闭钮（M3 默认 ≥48dp 触控）。 */
@Composable
fun MaaSheetHeader(
    title: String,
    onClose: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.common_close))
        }
    }
}
