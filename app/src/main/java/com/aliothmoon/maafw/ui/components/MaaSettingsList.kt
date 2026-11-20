package com.aliothmoon.maafw.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaIcons
import com.aliothmoon.maafw.theme.MaaTheme

/*
 * 设置主页的分组列表（行形态对齐 MaaMeow 的 SettingRow / SettingsDividedColumn）
 *
 * 表头落在卡片内顶部，折叠后收成一张标准 56dp 折叠白卡，与同页「任务设置」及首页折叠卡视觉一致；
 * 展开时表头下方一道通栏细分隔线，卡内一列行首单色图标的设置项，相邻行之间按标题列对齐分隔。
 * 只给设置主页这种「一页全是设置项」的列表用：内容卡里夹着的零散开关仍走 MaaSwitchRow / MaaNavigationRow
 */

/** 标题与说明从这里起排；分隔线、行下方的控件都对齐到它 */
private val TextInset: Dp = MaaDesignTokens.IconSize.rowLeading + MaaDesignTokens.Spacing.md

/**
 * 展开态只活在本次会话，同 [MaaCard]；[revealToken] 非空且变了就展开一次（设置搜索定位用）
 *
 * [info] 挂在表头标题旁的问号钮里，点开在表头下方展开，不触发表头折叠；
 * [dividerInset] 默认按设置行标题列缩进，无行首固定图标的分组（如「任务设置」）可传 `0.dp`
 */
@Composable
fun MaaSettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    info: String? = null,
    revealToken: Any? = null,
    dividerInset: Dp = TextInset,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var infoExpanded by rememberSaveable { mutableStateOf(false) }
    if (revealToken != null) {
        LaunchedEffect(revealToken) { expanded = true }
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = MaaTheme.style.cardElevation),
        border = BorderStroke(MaaDesignTokens.Separator.thickness, MaterialTheme.colorScheme.outline),
    ) {
        CompositionLocalProvider(LocalCardRowBleed provides MaaDesignTokens.Card.innerPadding) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .maaClickable(pressScale = false) { expanded = !expanded }
                        .semantics { role = Role.Button }
                        .heightIn(min = MaaDesignTokens.RowHeight.setting)
                        .padding(
                            horizontal = MaaDesignTokens.Card.innerPadding,
                            vertical = MaaDesignTokens.Spacing.sm,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (info != null) {
                            SettingInfoToggle(
                                expanded = infoExpanded,
                                onToggle = { infoExpanded = !infoExpanded },
                            )
                        }
                    }
                    MaaExpandChevron(expanded)
                }
                if (info != null) {
                    AnimatedVisibility(visible = infoExpanded, enter = CardExpand, exit = CardCollapse) {
                        Text(
                            text = info,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = MaaDesignTokens.Card.innerPadding,
                                    end = MaaDesignTokens.Card.innerPadding,
                                    bottom = MaaDesignTokens.Spacing.md,
                                ),
                        )
                    }
                }
                AnimatedVisibility(visible = expanded, enter = CardExpand, exit = CardCollapse) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        HorizontalDivider(
                            thickness = MaaDesignTokens.Separator.thickness,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        // 竖直内边距收到 xs：行自己带 56dp 高，再给 16dp 首尾就太空
                        MaaSettingsDividedColumn(
                            modifier = Modifier.padding(
                                PaddingValues(
                                    horizontal = MaaDesignTokens.Card.innerPadding,
                                    vertical = MaaDesignTokens.Spacing.xs,
                                ),
                            ),
                            dividerInset = dividerInset,
                            content = content,
                        )
                    }
                }
            }
        }
    }
}

/** 相邻两个非空子项之间画分隔线；条件不满足没渲染的项高度为 0，不会多出一道 */
@Composable
fun MaaSettingsDividedColumn(
    modifier: Modifier = Modifier,
    dividerInset: Dp = TextInset,
    content: @Composable () -> Unit,
) {
    val color = MaterialTheme.colorScheme.outlineVariant
    // 布局阶段写、绘制阶段读，绘制总在布局之后
    val lines = remember { ArrayList<Int>() }
    Layout(
        content = content,
        modifier = modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                val inset = dividerInset.toPx()
                val thickness = MaaDesignTokens.Separator.thickness.toPx()
                lines.forEach { y ->
                    drawRect(
                        color = color,
                        topLeft = Offset(inset, y.toFloat()),
                        size = Size(size.width - inset, thickness),
                    )
                }
            },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        layout(constraints.maxWidth, placeables.sumOf { it.height }) {
            lines.clear()
            var y = 0
            placeables.forEach { placeable ->
                if (y > 0 && placeable.height > 0) lines += y
                placeable.place(0, y)
                y += placeable.height
            }
        }
    }
}

@Composable
fun MaaSettingIcon(icon: ImageVector, enabled: Boolean = true) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (enabled) tint else tint.copy(alpha = MaaDesignTokens.Alpha.disabledContent),
        modifier = Modifier.size(MaaDesignTokens.IconSize.rowLeading),
    )
}

/**
 * 设置列表的一行：图标 + 标题（+ 可选说明）+ 行尾，[footer] 挂在下面、与标题左对齐（chip、滑块、输入框）
 *
 * [info] 默认收起，标题旁给一个问号钮，点开在整项下方展开（对齐 MaaMeow 的 SettingRow）；
 * [description] 用于必须常显的短副标题，设置主页尽量少用。
 * [onClick] 非空时整行可点并铺满卡片两边；[toggled] 非空时读屏按一个开关报。
 * [value] 是当前值，只跟标题同一行：放进 [trailing] 会贴在多行说明右边，把说明挤成窄条
 */
@Composable
fun MaaSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    description: String? = null,
    info: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    toggled: Boolean? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val titleColor = MaterialTheme.colorScheme.onSurface
    val descriptionColor = MaterialTheme.colorScheme.onSurfaceVariant
    val alpha = if (enabled) 1f else MaaDesignTokens.Alpha.disabledContent
    var infoExpanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick == null) Modifier
                    else Modifier.cardRowClickable(LocalCardRowBleed.current, enabled = enabled, onClick = onClick),
                )
                .then(
                    if (toggled == null) Modifier
                    else Modifier.semantics(mergeDescendants = true) {
                        role = Role.Switch
                        toggleableState = ToggleableState(toggled)
                    },
                )
                .heightIn(min = MaaDesignTokens.RowHeight.setting)
                .padding(vertical = MaaDesignTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
        ) {
            icon?.let { MaaSettingIcon(it, enabled) }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = titleColor.copy(alpha = alpha),
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (info != null) {
                            SettingInfoToggle(
                                expanded = infoExpanded,
                                onToggle = { infoExpanded = !infoExpanded },
                            )
                        }
                    }
                    value?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = descriptionColor.copy(alpha = alpha),
                        )
                    }
                }
                description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = descriptionColor.copy(alpha = alpha),
                    )
                }
            }
            trailing?.invoke()
        }
        footer?.let {
            Box(
                modifier = Modifier
                    .then(if (icon != null) Modifier.padding(start = TextInset) else Modifier)
                    .padding(bottom = MaaDesignTokens.Spacing.md),
            ) {
                it()
            }
        }
        if (info != null) {
            AnimatedVisibility(
                visible = infoExpanded,
                enter = CardExpand,
                exit = CardCollapse,
            ) {
                Text(
                    text = info,
                    style = MaterialTheme.typography.bodySmall,
                    color = descriptionColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (icon != null) Modifier.padding(start = TextInset) else Modifier)
                        .padding(bottom = MaaDesignTokens.Spacing.md),
                )
            }
        }
    }
}

@Composable
private fun SettingInfoToggle(expanded: Boolean, onToggle: () -> Unit) {
    val label = stringResource(R.string.settings_row_info)
    Box(
        modifier = Modifier
            .size(MaaDesignTokens.IconContainer.sm)
            .clip(CircleShape)
            .clickable(onClickLabel = label, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MaaIcons.Help,
            contentDescription = label,
            tint = if (expanded) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
        )
    }
}

/** 整行点了切换，开关只显示状态，同 [MaaSwitchRow]；[footer] 在可点区域之外，点它不会切开关 */
@Composable
fun MaaSettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    description: String? = null,
    info: String? = null,
    enabled: Boolean = true,
    footer: (@Composable () -> Unit)? = null,
) {
    MaaSettingRow(
        title = title,
        modifier = modifier,
        icon = icon,
        description = description,
        info = info,
        enabled = enabled,
        toggled = checked,
        onClick = { onCheckedChange(!checked) },
        trailing = { MaaSwitch(checked = checked, onCheckedChange = null, enabled = enabled) },
        footer = footer,
    )
}

/** 点进二级页或离开本应用；[external] 为 true 时行尾是 ↗，同 [MaaNavigationRow] */
@Composable
fun MaaSettingNavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    description: String? = null,
    info: String? = null,
    external: Boolean = false,
    enabled: Boolean = true,
) {
    MaaSettingRow(
        title = title,
        modifier = modifier,
        icon = icon,
        description = description,
        info = info,
        enabled = enabled,
        onClick = onClick,
        trailing = {
            Icon(
                imageVector = if (external) MaaIcons.ExternalLink else MaaIcons.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MaaDesignTokens.IconSize.md),
            )
        },
    )
}
