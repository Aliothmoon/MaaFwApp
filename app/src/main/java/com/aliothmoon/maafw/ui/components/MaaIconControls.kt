package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaIcons
import com.aliothmoon.maafw.theme.MaaTheme

/**
 * 列表里成排出现的图标按钮
 *
 * 不用 M3 IconButton：它内部写死原生 ripple()，主题里的 LocalIndication 管不到；
 * 原生 ripple 借窗口级宿主视图池，同一窗口里按过的组件一多，按下反馈就会串到别的组件上
 * （见 [MaaPressIndication]）。尺寸与 M3 一致：40dp 可视区、48dp 触控区
 */
@Composable
fun MaaIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(MaaDesignTokens.IconContainer.lg)
            .semantics { role = Role.Button }
            .maaClickable(enabled = enabled, shape = CircleShape, onClick = onClick)
            .alpha(if (enabled) 1f else MaaDesignTokens.Alpha.disabledContent),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * 复选框：外观仍是 M3 Checkbox，点击交给外层 toggleable，按下反馈走 [MaaPressIndication]
 *
 * 理由同 [MaaIconButton]：M3 Checkbox 可点击时内部写死原生 ripple()。
 * 触控区沿用 LocalMinimumInteractiveComponentSize，紧凑处照旧用 Dp.Unspecified 收起
 */
@Composable
fun MaaCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 不画勾选框，同一位置换成警示色的跳过标记，不可点 */
    skipped: Boolean = false,
) {
    val toggle = if (onCheckedChange != null && !skipped) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Checkbox,
            interactionSource = null,
            indication = MaaPressIndication(CircleShape),
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .then(toggle),
        contentAlignment = Alignment.Center,
    ) {
        if (skipped) {
            Icon(
                imageVector = MaaIcons.Skipped,
                contentDescription = null,
                tint = MaaTheme.palette.warning.content,
                modifier = Modifier.size(MaaDesignTokens.IconSize.checkboxMark),
            )
        } else {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}
