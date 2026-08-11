package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import com.aliothmoon.maafw.theme.MaaDesignTokens

/**
 * 列表/页面的空态：图标 + 标题 + 提示，可选一个行动按钮
 *
 * [hint] 必须 [TextAlign.Center]：容器只把整块 Text 居中，换行后每行仍会左对齐，
 * 提示一长就看着像没居中
 */
@Composable
fun MaaEmptyState(
    icon: ImageVector,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MaaDesignTokens.Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MaaDesignTokens.Alpha.disabled),
            modifier = Modifier.size(MaaDesignTokens.IconSize.xl),
        )
        Spacer(Modifier.size(MaaDesignTokens.Spacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(MaaDesignTokens.Spacing.xs))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.let {
            Spacer(Modifier.size(MaaDesignTokens.Spacing.lg))
            it()
        }
    }
}
