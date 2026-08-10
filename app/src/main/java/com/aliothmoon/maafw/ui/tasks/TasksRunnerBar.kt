package com.aliothmoon.maafw.ui.tasks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaSwitch

/**
 * 底部启停条
 *
 * 次级按钮按内容取宽而不参与等分：它只是入口，剩下的宽度都该留给主操作
 * 面板的开合态由调用方持有——面板要盖满整屏，得挂在 Screen 的根 Box 上
 */
@Composable
internal fun RunnerToggleButton(
    state: SessionUiState,
    quickOptionsOpen: Boolean,
    onIntent: (SessionIntent) -> Unit,
    onToggleQuickOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = state.runner.phase
    val shape = RoundedCornerShape(MaaTheme.style.radii.inner)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
    ) {
        val toggleModifier = Modifier.weight(1f)
        if (phase.isBusy) {
            OutlinedButton(
                onClick = { onIntent(SessionIntent.Stop) },
                enabled = phase != RunnerPhase.Stopping,
                shape = shape,
                modifier = toggleModifier,
            ) {
                Text(
                    stringResource(
                        if (phase == RunnerPhase.Stopping) R.string.runner_stopping else R.string.runner_stop,
                    ),
                )
            }
        } else {
            // 前台模式灰显但仍可点：点了走 Start，由 SessionViewModel 拦下并给提示
            val foregroundBlocked = state.runMode == RunMode.FOREGROUND
            Button(
                onClick = { onIntent(SessionIntent.Start) },
                enabled = state.canStart,
                shape = shape,
                colors = if (foregroundBlocked) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = toggleModifier,
            ) {
                Text(stringResource(R.string.runner_start))
            }
        }
        // 展开时描边与文字一起转 primary，让按钮自己表示面板的开合
        val accent = if (quickOptionsOpen) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        }
        OutlinedButton(
            onClick = onToggleQuickOptions,
            shape = shape,
            contentPadding = PaddingValues(horizontal = MaaDesignTokens.Spacing.md),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
            border = BorderStroke(
                MaaDesignTokens.Border.selected,
                if (quickOptionsOpen) accent else MaterialTheme.colorScheme.outline,
            ),
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = null,
                modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
            )
            Box(Modifier.size(MaaDesignTokens.Spacing.xs))
            Text(text = stringResource(R.string.runner_quick_options), maxLines = 1)
        }
    }
}

/**
 * 贴着启停条弹出的快捷操作面板
 *
 * 不用 [com.aliothmoon.maafw.ui.components.MaaSheet]：那是模态底部抽屉，用在选配置、看日志
 * 这类要占满注意力的场面。这里要的恰恰相反——一张贴着按钮的小卡，点外面就走
 *
 * 只放已经接通的动作。运行模式决定息屏与控制层只出现其中一个：两者互斥（docs/privileged-runtime.md §7.2）
 */
@Composable
internal fun TasksQuickOptionsPanel(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    onOpenRunLog: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(interactionSource = scrimInteraction, indication = null, onClick = onDismiss),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(
                    start = MaaDesignTokens.Spacing.lg,
                    end = MaaDesignTokens.Spacing.lg,
                    bottom = PanelBottomInset,
                )
                // 吞掉卡片上的点击，否则会穿到下面的 scrim 上把自己关掉
                .clickable(interactionSource = cardInteraction, indication = null, onClick = {}),
            shape = RoundedCornerShape(MaaTheme.style.radii.card),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(MaaDesignTokens.Separator.thickness, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = MaaTheme.style.cardElevation,
        ) {
            Column(
                modifier = Modifier.padding(MaaDesignTokens.Spacing.md),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            ) {
                GroupLabel(stringResource(R.string.quick_actions_title))
                Row(horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                    ActionTile(
                        icon = Icons.Outlined.History,
                        label = stringResource(R.string.run_log_title),
                        accent = MaterialTheme.colorScheme.primary,
                        onClick = {
                            onDismiss()
                            onOpenRunLog()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    when (state.runMode) {
                        RunMode.BACKGROUND -> ActionTile(
                            icon = Icons.Outlined.PowerSettingsNew,
                            label = stringResource(R.string.quick_action_screen_saver),
                            accent = MaterialTheme.colorScheme.tertiary,
                            onClick = {
                                onDismiss()
                                onIntent(SessionIntent.ShowScreenSaver)
                            },
                            modifier = Modifier.weight(1f),
                        )

                        RunMode.FOREGROUND -> ActionTile(
                            icon = Icons.Outlined.Layers,
                            label = stringResource(R.string.settings_overlay_show),
                            accent = MaterialTheme.colorScheme.tertiary,
                            onClick = {
                                onDismiss()
                                onIntent(SessionIntent.ShowOverlay)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                ActionTile(
                    icon = Icons.Outlined.Refresh,
                    label = stringResource(R.string.settings_reload_project),
                    accent = MaterialTheme.colorScheme.secondary,
                    enabled = !state.configurationLocked,
                    onClick = {
                        onDismiss()
                        onIntent(SessionIntent.ReloadProject)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                GroupLabel(stringResource(R.string.quick_settings_title))
                if (state.runMode == RunMode.BACKGROUND) {
                    SettingSwitchRow(
                        icon = Icons.Outlined.Nightlight,
                        label = stringResource(R.string.settings_screen_saver_auto),
                        checked = state.screenSaverEnabled,
                        onCheckedChange = { onIntent(SessionIntent.SetScreenSaverEnabled(it)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}

/** 面板离底的距离：让开下面那条启停条，否则会盖住自己的入口 */
private val PanelBottomInset = 56.dp

/** 动作块高度；比一行文字略高，两块并排时才不显得挤 */
private val ActionTileHeight = 36.dp

/**
 * 一格动作：底色与描边都从 accent 派生
 *
 * 不用 Button：它的 40dp 最小高度与填充色会让两三格并排时喧宾夺主，
 * 而这些是次级动作，主操作是旁边那颗开始
 */
@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tint = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(ActionTileHeight),
        shape = RoundedCornerShape(MaaTheme.style.radii.inner),
        color = tint.copy(alpha = 0.08f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(MaaDesignTokens.Separator.thickness, tint.copy(alpha = 0.2f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = MaaDesignTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                tint = tint,
            )
            Text(text = label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
    }
}

/** 整行可点，不要求用户去够右边那个小开关 */
@Composable
private fun SettingSwitchRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = MaaDesignTokens.Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        MaaSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
