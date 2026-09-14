package com.aliothmoon.maafw.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.maaClickable

/** 悬浮窗行：inner 圆角、8/6 内边，不是任务页那张内容卡 */
@Composable
internal fun OverlayTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaaTheme.style.radii.inner),
        color = if (selected) scheme.primaryContainer else scheme.surface,
        border = BorderStroke(
            MaaDesignTokens.Separator.thickness,
            if (selected) scheme.primary.copy(alpha = 0.5f) else scheme.outlineVariant,
        ),
        contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .maaClickable(enabled = enabled, onClick = onClick)
                .padding(
                    horizontal = MaaDesignTokens.Spacing.sm,
                    vertical = MaaDesignTokens.Overlay.gap,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            content = content,
        )
    }
}

@Composable
internal fun OverlayModeRow(
    selected: Boolean,
    enabled: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaaTheme.style.radii.inner),
        color = if (selected) scheme.primary else scheme.surface,
        border = BorderStroke(
            MaaDesignTokens.Separator.thickness,
            if (selected) scheme.primary else scheme.outlineVariant,
        ),
        contentColor = if (selected) scheme.onPrimary else scheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .maaClickable(enabled = enabled, onClick = onClick)
                .padding(
                    horizontal = MaaDesignTokens.Spacing.sm,
                    vertical = MaaDesignTokens.Overlay.gap,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                tint = if (selected) scheme.onPrimary else scheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun OverlayBarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(MaaTheme.style.radii.inner)
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(MaaDesignTokens.Overlay.bar),
            shape = shape,
            color = when {
                !enabled && filled -> scheme.primary.copy(alpha = MaaDesignTokens.Alpha.disabled)
                filled -> scheme.primary
                else -> scheme.surface
            },
            contentColor = when {
                filled -> scheme.onPrimary
                else -> scheme.onSurface
            },
            border = if (filled) {
                null
            } else {
                BorderStroke(MaaDesignTokens.Separator.thickness, scheme.outline)
            },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaaDesignTokens.Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                content = content,
            )
        }
    }
}

@Composable
internal fun OverlayCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = modifier.size(MaaDesignTokens.Overlay.checkbox),
        )
    }
}

@Composable
internal fun OverlayIconHit(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        modifier = modifier
            .size(MaaDesignTokens.Overlay.iconHit)
            .maaClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint.copy(
                alpha = if (enabled) 1f else MaaDesignTokens.Alpha.disabledContent,
            ),
            modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
        )
    }
}

@Composable
internal fun OverlayField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    enabled: Boolean = true,
    onDone: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val style: TextStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurface)
    Surface(
        modifier = modifier.height(MaaDesignTokens.Overlay.field),
        shape = RoundedCornerShape(MaaTheme.style.radii.inner),
        color = scheme.surface,
        border = BorderStroke(MaaDesignTokens.Separator.thickness, scheme.outline),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = MaaDesignTokens.Spacing.sm),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty() && hint.isNotEmpty()) {
                Text(text = hint, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
