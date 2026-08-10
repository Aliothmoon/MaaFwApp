package com.aliothmoon.maafw.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.theme.MaaDesignTokens

/**
 * 就地展开的提示图标 + 内容区（交互参考 MaaMeow 同类控件）：
 * 小 Info 图标点击切换，内容在原位置垂直展开，不打断当前上下文
 */
@Composable
fun ExpandableTipIcon(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 视觉 16dp，触控区 ≥48dp（无障碍最小触控目标）
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(CircleShape)
            .maaClickable(onClick = { onExpandedChange(!expanded) }),
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = stringResource(R.string.tasks_description_title),
            tint = if (expanded) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
        )
    }
}

@Composable
fun ExpandableTipContent(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        MaaDescriptionPanel(content = content)
    }
}
