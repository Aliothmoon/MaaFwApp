package com.aliothmoon.maafw.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    // 紧凑行内用：视觉 16dp，外框 IconContainer.sm，不撑到 48dp 以免表格行被垫高
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(MaaDesignTokens.IconContainer.sm)
            .clip(CircleShape)
            .maaClickable(onClick = { onExpandedChange(!expanded) }),
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            tint = if (expanded) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
        )
    }
}

/**
 * [topSpacing] 与上方那行的间距放在动画里面：放在外层 spacedBy 里，收起后那段空白还占着，
 * 展开时又会先一下冒出来
 */
@Composable
fun ExpandableTipContent(
    visible: Boolean,
    modifier: Modifier = Modifier,
    topSpacing: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = CardExpand,
        exit = CardCollapse,
        modifier = modifier,
    ) {
        MaaDescriptionPanel(modifier = Modifier.padding(top = topSpacing), content = content)
    }
}
