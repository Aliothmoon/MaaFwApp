package com.aliothmoon.maafw.ui.tasks

import com.aliothmoon.maafw.settings.search.SettingAnchors
import com.aliothmoon.maafw.ui.settings.search.SettingSearchTarget
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.aliothmoon.maafw.privileged.RemoteServiceManager
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
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Subject
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaButton
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import com.aliothmoon.maafw.ui.components.MaaSemanticOutlinedButton
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaCheckbox

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
    /** 全部实例页多选（≥2 路）时非空：点开始走批量启动；null 时走单路 Start */
    multiStartSlots: List<Int>? = null,
    modifier: Modifier = Modifier,
) {
    val phase = state.runner.phase
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
    ) {
        val toggleModifier = Modifier.weight(1f)
        if (phase.isBusy) {
            // 停止是破坏性动作，语义色取 error；对齐 HomeScreen 服务开关那套描边配比
            MaaSemanticOutlinedButton(
                onClick = { onIntent(SessionIntent.Stop) },
                semantic = MaterialTheme.colorScheme.error,
                enabled = phase != RunnerPhase.Stopping,
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
            MaaButton(
                onClick = {
                    val slots = multiStartSlots
                    if (slots != null) {
                        onIntent(SessionIntent.StartMultiple(slots))
                    } else {
                        onIntent(SessionIntent.Start())
                    }
                },
                enabled = state.canStart,
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
                Text(
                    if (multiStartSlots != null) {
                        stringResource(R.string.runner_start_multi, multiStartSlots.size)
                    } else {
                        stringResource(R.string.runner_start)
                    },
                )
            }
        }
        // 展开时描边与文字一起转 primary，让按钮自己表示面板的开合
        val accent = if (quickOptionsOpen) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        }
        MaaOutlinedButton(
            onClick = onToggleQuickOptions,
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
    onDismiss: () -> Unit,
    onShowAllInstances: () -> Unit,
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
                // 多开时才有得选：只有一路时这块不出现，面板与单路版一模一样
                val slots = RemoteServiceManager.activeSlots
                val activeSlot by RemoteServiceManager.activeSlot.collectAsState()
                if (slots.size > 1) {
                    GroupLabel(stringResource(R.string.quick_group_instances))
                    // 一排最多五格：五开时正好铺满一行，再多就换一行
                    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                        slots.chunked(InstancesPerRow).forEach { rowSlots ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                            ) {
                                rowSlots.forEach { slot ->
                                    InstanceChip(
                                        label = stringResource(
                                            R.string.quick_instance_label_short,
                                            instanceOrdinal(slot + 1),
                                        ),
                                        selected = slot == activeSlot,
                                        onClick = {
                                            RemoteServiceManager.setActiveSlot(slot)
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }

                GroupLabel(stringResource(R.string.quick_actions_title))
                // 运行日志不在这儿：入口已经在配置行右侧，同一个动作不留两处
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                ) {
                    when (state.runMode) {
                        // 前台模式没有虚拟屏，屏保与「关目标应用」两格都无从谈起
                        RunMode.BACKGROUND -> {
                            ActionTile(
                                icon = Icons.AutoMirrored.Outlined.ExitToApp,
                                label = stringResource(R.string.quick_action_close_app),
                                accent = MaterialTheme.colorScheme.error,
                                onClick = {
                                    onIntent(SessionIntent.CloseTargetApp)
                                },
                                modifier = Modifier.weight(1f),
                            )
                            ActionTile(
                                icon = Icons.Outlined.PowerSettingsNew,
                                label = stringResource(R.string.quick_action_screen_saver),
                                accent = MaterialTheme.colorScheme.tertiary,
                                onClick = {
                                    onIntent(SessionIntent.ShowScreenSaver)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        RunMode.FOREGROUND -> ActionTile(
                            icon = Icons.Outlined.Layers,
                            label = stringResource(R.string.settings_overlay_show),
                            accent = MaterialTheme.colorScheme.tertiary,
                            onClick = {
                                onIntent(SessionIntent.ShowOverlay)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                ) {
                    ActionTile(
                        icon = Icons.Outlined.Refresh,
                        label = stringResource(R.string.settings_reload_project),
                        accent = MaterialTheme.colorScheme.secondary,
                        enabled = !state.configurationLocked,
                        onClick = {
                            onIntent(SessionIntent.ReloadProject)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    // 并排这一格除了是自己的入口，也让「重新加载项目」回到半宽，
                    // 不再是一格占满整行的那种突兀尺寸
                    ActionTile(
                        icon = Icons.Outlined.GridView,
                        label = stringResource(R.string.quick_action_all_instances),
                        accent = MaterialTheme.colorScheme.secondary,
                        onClick = {
                            onDismiss()
                            onShowAllInstances()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (state.debugMode && state.runMode == RunMode.BACKGROUND) {
                        ActionTile(
                            icon = Icons.Outlined.PhotoCamera,
                            label = stringResource(R.string.quick_action_screenshot),
                            accent = MaterialTheme.colorScheme.secondary,
                            onClick = {
                                onIntent(SessionIntent.CaptureVirtualDisplay)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                GroupLabel(stringResource(R.string.quick_settings_title))
                if (state.runMode == RunMode.BACKGROUND) {
                    SettingSearchTarget(SettingAnchors.SCREEN_SAVER) {
                        SettingToggleRow(
                            icon = Icons.Outlined.Nightlight,
                            label = stringResource(R.string.settings_screen_saver_auto),
                            checked = state.screenSaverEnabled,
                            onCheckedChange = { onIntent(SessionIntent.SetScreenSaverEnabled(it)) },
                        )
                    }
                    SettingSearchTarget(SettingAnchors.CLOSE_APP_AFTER_TASK) {
                        SettingToggleRow(
                            icon = Icons.Outlined.Cancel,
                            label = stringResource(R.string.quick_setting_close_app_after_task),
                            checked = state.closeAppAfterTask,
                            onCheckedChange = { onIntent(SessionIntent.SetCloseAppAfterTask(it)) },
                        )
                    }
                }
                SettingSearchTarget(SettingAnchors.TOUCH_PREVIEW) {
                    SettingToggleRow(
                        icon = Icons.Outlined.TouchApp,
                        label = stringResource(R.string.quick_setting_touch_preview),
                        checked = state.touchPreviewEnabled,
                        onCheckedChange = { onIntent(SessionIntent.SetTouchPreviewEnabled(it)) },
                    )
                }
                // 下面两项是「全部实例」那一屏的排布与内容，与运行模式无关，不裹在 BACKGROUND 里
                SettingToggleRow(
                    icon = Icons.Outlined.GridView,
                    label = stringResource(R.string.quick_setting_instances_two_column),
                    checked = state.instancesTwoColumn,
                    onCheckedChange = { onIntent(SessionIntent.SetInstancesTwoColumn(it)) },
                )
                SettingToggleRow(
                    icon = Icons.Outlined.Subject,
                    label = stringResource(R.string.quick_setting_show_instance_logs),
                    checked = state.showInstanceLogs,
                    onCheckedChange = { onIntent(SessionIntent.SetShowInstanceLogs(it)) },
                )
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

/** 一排最多放几个实例格：五开时正好铺满一行，再多就换一行 */
private const val InstancesPerRow = 5

/** 实例序号的中文写法：实例一、实例二…超出就退回阿拉伯数字 */
private val InstanceOrdinals = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")

/** 设置页与快捷面板共用一套实例名，避免两处各写一套对不上 */
internal fun instanceOrdinal(index: Int): String =
    InstanceOrdinals.getOrNull(index - 1) ?: index.toString()

/**
 * 一格「第 N 路」：多开时切 UI 操作的是哪一路
 *
 * 用 chip 而不是按钮：它表达的是「当前视角」这种单选状态，
 * 不是「按一下做一件事」的动作，与旁边的 ActionTile 区分开
 */
@Composable
private fun InstanceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(MaaTheme.style.radii.card),
        color = if (selected) scheme.primaryContainer else scheme.surfaceVariant,
        contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        border = BorderStroke(
            MaaDesignTokens.Separator.thickness,
            if (selected) scheme.primary else scheme.outlineVariant,
        ),
    ) {
        // 宽度由外层的 weight 等分决定，与文字长短无关；文字在自己的格子里居中，
        // 免得短标签（「一」）贴着左边显得偏
        Text(
            text = label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
    }
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

/** 勾选框缩到 20dp 并解除 48dp 最小触控：整行都可点，触控目标由行本身提供 */
private val CompactCheckboxSize = 20.dp

/**
 * 整行可点，不要求用户去够右边那个小方框
 *
 * 这里用 Checkbox 而设置页同一项用 Switch——面板是密排的快捷入口，Switch 的宽度会把
 * 每行撑高一档。破了「一个领域动作只有一副形态」（docs/design-system.md §4.4），是有意的
 */
@Composable
private fun SettingToggleRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = MaaDesignTokens.Spacing.xs),
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
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            MaaCheckbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.size(CompactCheckboxSize),
            )
        }
    }
}
