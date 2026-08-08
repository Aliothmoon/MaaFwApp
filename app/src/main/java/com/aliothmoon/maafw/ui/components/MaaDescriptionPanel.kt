package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aliothmoon.maafw.theme.MaaDesignTokens

/**
 * 描述面板：secondaryContainer 浅底色 + 内圆角的静态容器，
 * 用于 option 描述、任务说明等常驻说明文本；
 * ExpandableTipContent 的展开面板复用同一视觉，保证全局描述观感一致
 */
@Composable
fun MaaDescriptionPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(MaaDesignTokens.CornerRadius.inner),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier.padding(
                horizontal = MaaDesignTokens.Spacing.sm,
                vertical = MaaDesignTokens.Spacing.xs,
            ),
        ) {
            content()
        }
    }
}
