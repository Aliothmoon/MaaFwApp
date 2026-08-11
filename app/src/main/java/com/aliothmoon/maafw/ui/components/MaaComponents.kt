package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.theme.MaaTone
import com.aliothmoon.maafw.ui.i18n.asUiText

@Composable
fun MaaCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    // 默认参数不能读 CompositionLocal；innerPadding 两风格同值，静态回落安全
    contentPadding: PaddingValues = PaddingValues(MaaDesignTokens.Card.innerPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = MaaTheme.style.cardElevation),
        border = BorderStroke(MaaDesignTokens.Separator.thickness, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            when {
                trailing != null -> MaaLabeledControlRow(
                    label = title.orEmpty(),
                    labelStyle = MaterialTheme.typography.titleMedium,
                    trailing = trailing,
                )

                title != null -> Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            content()
        }
    }
}

/** 尾控件固宽，标签占剩余并换行，避免长 label 挤掉 Switch/Icon */
@Composable
fun MaaLabeledControlRow(
    label: String,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    labelColor: Color = Color.Unspecified,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
    ) {
        Text(
            text = label,
            style = labelStyle,
            color = labelColor,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier.wrapContentWidth(),
            contentAlignment = Alignment.Center,
        ) {
            trailing()
        }
    }
}

/**
 * 点进二级页面的一行：标题 + 说明 + 右侧箭头
 *
 * 与 [MaaLabeledControlRow] 分开：那个的尾部是控件、点的是控件本身；这个整行可点，
 * 语义是「离开当前页」
 */
@Composable
fun MaaNavigationRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .maaClickable(onClick = onClick)
            .padding(vertical = MaaDesignTokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(MaaDesignTokens.IconSize.md),
        )
    }
}

/** 卡片配方 Surface；普通内容卡用 [MaaCard] */
@Composable
fun MaaCardSurface(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    border: BorderStroke = BorderStroke(MaaDesignTokens.Separator.thickness, MaterialTheme.colorScheme.outline),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = color,
        shadowElevation = MaaTheme.style.cardElevation,
        border = border,
        content = content,
    )
}

/** 未选中态走调色板的 `switchOff`：outline 与轨道同色会糊，onSurfaceVariant 又压得慌 */
@Composable
fun MaaSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            uncheckedThumbColor = MaaTheme.palette.switchOff,
            uncheckedBorderColor = MaaTheme.palette.switchOff,
        ),
    )
}

@Composable
fun MaaDiagnosticList(
    diagnostics: List<Diagnostic>,
    showSeverity: Boolean = false,
) {
    Column {
        diagnostics.forEach {
            val prefix = if (showSeverity) "[${it.severity.asUiText().asString()}] " else ""
            Text(
                text = "$prefix${it.source}: ${it.message.asString()}",
                style = MaterialTheme.typography.bodySmall,
                color = if (it.severity == DiagnosticSeverity.Error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * 可选卡片：卡片配方 + 点击 + 选中态描边与底色
 *
 * 选中怎么表现集中在这里，各 Screen 不再各写一份 `if (selected) primaryContainer else surface`
 * [selected] 恒为 false 时就是一张普通可点卡片
 */
@Composable
fun MaaSelectableCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaaCardSurface(
        modifier = modifier
            .fillMaxWidth()
            .maaClickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else MaaDesignTokens.Alpha.disabled),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            width = if (selected) MaaDesignTokens.Border.selected else MaaDesignTokens.Separator.thickness,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
        ),
        content = content,
    )
}

/** [MaaSelectableCard] 行首的单选标记；选中填实并打勾 */
@Composable
fun MaaSelectionMarker(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(MaaDesignTokens.IconContainer.xs)
            .clip(CircleShape)
            .border(
                width = MaaDesignTokens.Border.marker,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                shape = CircleShape,
            )
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(MaaDesignTokens.IconSize.xs),
            )
        }
    }
}

/** 图标外的一层圆或圆角方底；[shape] 默认圆角方，行尾的收起钮传 CircleShape */
@Composable
fun MaaIconBadge(
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    containerSize: Dp = MaaDesignTokens.IconContainer.md,
    shape: Shape? = null,
) {
    val badgeShape = shape ?: RoundedCornerShape(MaaTheme.style.radii.button)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(containerSize)
            .clip(badgeShape)
            .background(containerColor),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
        )
    }
}

@Composable
fun MaaInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.lg),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.6f),
        )
    }
}

@Composable
fun MaaToneBadge(
    text: String,
    tone: MaaTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(MaaTheme.style.radii.inner))
            .background(tone.container)
            .padding(
                horizontal = MaaDesignTokens.Spacing.sm,
                vertical = MaaDesignTokens.Spacing.xxs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tone.content,
                modifier = Modifier.size(MaaDesignTokens.IconSize.xs),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tone.content,
        )
    }
}
