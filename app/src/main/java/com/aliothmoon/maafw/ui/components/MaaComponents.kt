package com.aliothmoon.maafw.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import com.aliothmoon.maafw.theme.MaaMotion
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.theme.MaaTone
import com.aliothmoon.maafw.ui.i18n.asUiText

/**
 * [collapsible] 要求有 [title]：折叠靠点标题行，没标题就没有可点的表头
 *
 * 展开态只活在本次会话（[rememberSaveable]）——收起是临时整理视线，不是配置，不该进 DataStore
 */
@Composable
fun MaaCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    collapsible: Boolean = false,
    // 默认参数不能读 CompositionLocal；innerPadding 两风格同值，静态回落安全
    contentPadding: PaddingValues = PaddingValues(MaaDesignTokens.Card.innerPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val canCollapse = collapsible && title != null
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MaaMotion.enter(MaaMotion.DURATION_SHORT),
        label = "chevron",
    )

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
            val chevron: @Composable () -> Unit = {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(MaaDesignTokens.IconSize.sm)
                        .rotate(chevronRotation),
                )
            }
            when {
                // trailing 已占住表头右侧，此时只有箭头本身可点，避免与开关抢同一片区域
                trailing != null -> MaaLabeledControlRow(
                    label = title.orEmpty(),
                    labelStyle = MaterialTheme.typography.titleMedium,
                    trailing = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                        ) {
                            trailing()
                            if (canCollapse) {
                                Box(Modifier.maaClickable { expanded = !expanded }) { chevron() }
                            }
                        }
                    },
                )

                title != null -> MaaLabeledControlRow(
                    label = title,
                    labelStyle = MaterialTheme.typography.titleMedium,
                    modifier = if (canCollapse) {
                        Modifier.maaClickable { expanded = !expanded }
                    } else {
                        Modifier
                    },
                    trailing = { if (canCollapse) chevron() },
                )
            }
            if (canCollapse) {
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                        content = content,
                    )
                }
            } else {
                content()
            }
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
 * 按钮的统一形状：走 `MaaTheme.style.radii.button`
 *
 * M3 的 `Button` 默认形状取自它自己的 token（`CornerFull` → 胶囊），**不经过主题的 `Shapes`**，
 * 所以换主题风格也压不到它。全仓的按钮一律走这两个 wrapper，别直接用 M3 的
 *
 * 只包了形状的**默认值**，其余参数原样透传：颜色与内边距按场景各不相同，包死反而要再开一堆口子。
 * [shape] 仍可覆盖，但只在「要跟旁边的输入框或卡片对齐」时才该覆盖，且写清理由
 */
@Composable
fun MaaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(MaaTheme.style.radii.button),
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun MaaOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(MaaTheme.style.radii.button),
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        border = border,
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * 最常见的那种设置行：标签 + 开关
 *
 * [MaaLabeledControlRow] 的尾部什么控件都能放，这个只固定成开关——省掉每处再写一遍
 * `trailing = { MaaSwitch(...) }`。尾部不是开关（状态点、转圈、下拉）时仍用前者
 */
@Composable
fun MaaSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    MaaLabeledControlRow(
        label = label,
        modifier = modifier,
        trailing = {
            MaaSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        },
    )
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

/** 卡片内一组控件的小标题；一张卡装多组时靠它区分，没有它几排 chip 分不清谁是谁 */
@Composable
fun MaaFieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
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
