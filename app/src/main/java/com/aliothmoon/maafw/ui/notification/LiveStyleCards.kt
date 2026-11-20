package com.aliothmoon.maafw.ui.notification

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.notification.RunProgressSnapshots
import com.aliothmoon.maafw.notification.live.LiveChipContent
import com.aliothmoon.maafw.notification.live.LiveColorScheme
import com.aliothmoon.maafw.notification.live.LiveStyle
import com.aliothmoon.maafw.notification.live.LiveTrackerIcon
import com.aliothmoon.maafw.notification.live.parseHexColor
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaIcons
import com.aliothmoon.maafw.ui.components.MaaAppIcon
import com.aliothmoon.maafw.ui.components.MaaButton
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaFieldLabel
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import com.aliothmoon.maafw.ui.components.MaaSingleChoiceFlow

private val LiveChipContent.labelRes: Int
    get() = when (this) {
        LiveChipContent.BOTH -> R.string.live_style_chip_both
        LiveChipContent.PROGRESS -> R.string.live_style_chip_progress
        LiveChipContent.TASK -> R.string.live_style_chip_task
        LiveChipContent.LOG -> R.string.live_style_chip_log
        LiveChipContent.NONE -> R.string.live_style_chip_none
    }

private val LiveColorScheme.labelRes: Int
    get() = when (this) {
        LiveColorScheme.DEFAULT -> R.string.live_style_color_default
        LiveColorScheme.GREEN -> R.string.live_style_color_green
        LiveColorScheme.ORANGE -> R.string.live_style_color_orange
        LiveColorScheme.PURPLE -> R.string.live_style_color_purple
        LiveColorScheme.PINK -> R.string.live_style_color_pink
        LiveColorScheme.TEAL -> R.string.live_style_color_teal
        LiveColorScheme.CUSTOM -> R.string.live_style_color_custom
    }

private val LiveTrackerIcon.labelRes: Int
    get() = when (this) {
        LiveTrackerIcon.DEFAULT -> R.string.live_style_icon_default
        LiveTrackerIcon.APP -> R.string.live_style_icon_app
        LiveTrackerIcon.CUSTOM -> R.string.live_style_icon_custom
    }

// 自定义色板：只放预设单色之外的补充色
private class ColorSwatch(argb: Long, @param:StringRes val labelRes: Int) {
    val color = Color(argb)
    val hex = "#%06X".format(argb and 0xFFFFFF)
}

private val ColorSwatches = listOf(
    ColorSwatch(0xFFF44336, R.string.live_style_swatch_red),
    ColorSwatch(0xFF8BC34A, R.string.live_style_swatch_light_green),
    ColorSwatch(0xFF00BCD4, R.string.live_style_swatch_cyan),
    ColorSwatch(0xFFFFC107, R.string.live_style_swatch_amber),
    ColorSwatch(0xFF795548, R.string.live_style_swatch_brown),
    ColorSwatch(0xFF607D8B, R.string.live_style_swatch_blue_grey),
)

@Composable
internal fun LiveChipCard(style: LiveStyle, island: Boolean, onSelect: (LiveChipContent) -> Unit) {
    val titleRes = if (island) R.string.live_style_chip_label_island else R.string.live_style_chip_label
    MaaCard(title = stringResource(titleRes)) {
        MaaSingleChoiceFlow(
            options = LiveChipContent.entries.map { it to stringResource(it.labelRes) },
            selected = style.chipContent,
            onSelect = { if (it != style.chipContent) onSelect(it) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LiveColorCard(
    style: LiveStyle,
    onSelectScheme: (LiveColorScheme) -> Unit,
    onSelectCustom: (String) -> Unit,
) {
    MaaCard(title = stringResource(R.string.live_style_color_label)) {
        MaaSingleChoiceFlow(
            options = LiveColorScheme.entries.map { it to stringResource(it.labelRes) },
            selected = style.colorScheme,
            onSelect = { if (it != style.colorScheme) onSelectScheme(it) },
            leading = { ColorDot(schemePreviewColor(it, style.customColor)) },
        )
        if (style.colorScheme == LiveColorScheme.CUSTOM) {
            MaaFieldLabel(
                text = stringResource(R.string.live_style_color_custom_hint),
                modifier = Modifier.padding(top = MaaDesignTokens.Spacing.xs),
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            ) {
                ColorSwatches.forEach { swatch ->
                    val selected = style.customColor.equals(swatch.hex, ignoreCase = true)
                    val label = stringResource(swatch.labelRes)
                    Box(
                        modifier = Modifier
                            .size(MaaDesignTokens.IconContainer.sm)
                            .clip(CircleShape)
                            .background(swatch.color)
                            .then(
                                if (selected) {
                                    Modifier.border(
                                        MaaDesignTokens.Border.marker,
                                        MaterialTheme.colorScheme.onSurface,
                                        CircleShape,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .selectable(selected = selected, role = Role.RadioButton) {
                                onSelectCustom(swatch.hex)
                            }
                            .semantics { contentDescription = label },
                    )
                }
            }
        }
    }
}

/** DEFAULT 显示本项目的默认进度色；CUSTOM 显示已选的自定义色，还没选就是中性灰 */
@Composable
private fun schemePreviewColor(scheme: LiveColorScheme, customColor: String): Color {
    val argb = when (scheme) {
        LiveColorScheme.DEFAULT -> RunProgressSnapshots.BAR_COLOR
        LiveColorScheme.CUSTOM -> parseHexColor(customColor)
        else -> scheme.argb
    }
    return argb?.let(::Color) ?: MaterialTheme.colorScheme.outline
}

@Composable
private fun ColorDot(color: Color) {
    Box(
        modifier = Modifier
            .size(MaaDesignTokens.IconSize.xs)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
internal fun LiveIconCard(
    style: LiveStyle,
    icon: Bitmap?,
    island: Boolean,
    onSelect: (LiveTrackerIcon) -> Unit,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    val titleRes = if (island) R.string.live_style_icon_label_island else R.string.live_style_icon_label
    MaaCard(title = stringResource(titleRes)) {
        MaaSingleChoiceFlow(
            options = LiveTrackerIcon.entries.map { it to stringResource(it.labelRes) },
            selected = style.trackerIcon,
            onSelect = { if (it != style.trackerIcon) onSelect(it) },
            leading = { IconPreview(it) },
        )
        if (style.trackerIcon == LiveTrackerIcon.CUSTOM) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
                modifier = Modifier.padding(top = MaaDesignTokens.Spacing.xs),
            ) {
                icon?.let { bitmap ->
                    val image = remember(bitmap) { bitmap.asImageBitmap() }
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        modifier = Modifier
                            .size(MaaDesignTokens.IconContainer.md)
                            .clip(MaterialTheme.shapes.small)
                            // 中灰底：白色或透明的图标才看得见
                            .background(MaterialTheme.colorScheme.outline),
                    )
                }
                Text(
                    text = stringResource(R.string.live_style_icon_custom_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            // 与自定义背景页的选图同一副形态：主按钮选图，有图时并排一个描边按钮清掉
            Row(horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                MaaButton(onClick = onPick, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.live_style_icon_pick))
                }
                if (style.customTrackerPath.isNotEmpty()) {
                    MaaOutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.live_style_icon_clear))
                    }
                }
            }
        }
    }
}

@Composable
private fun IconPreview(icon: LiveTrackerIcon) {
    val modifier = Modifier
        .size(MaaDesignTokens.IconSize.sm)
        .clip(CircleShape)
    when (icon) {
        // 白色游标在浅色底上看不见，预览底用中灰
        LiveTrackerIcon.DEFAULT -> Image(
            painter = painterResource(R.drawable.ic_progress_tracker),
            contentDescription = null,
            modifier = modifier.background(MaterialTheme.colorScheme.outline),
        )
        LiveTrackerIcon.APP -> MaaAppIcon(
            packageName = LocalContext.current.packageName,
            size = MaaDesignTokens.IconSize.sm,
        )
        LiveTrackerIcon.CUSTOM -> Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.background(MaterialTheme.colorScheme.outline),
        ) {
            Icon(
                imageVector = MaaIcons.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(MaaDesignTokens.IconSize.xs),
            )
        }
    }
}
