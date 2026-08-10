package com.aliothmoon.maafw.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaSwitch

/** 左段启停；右段运行选项仅 UI，不随 configurationLocked */
@Composable
internal fun RunnerToggleButton(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = state.runner.phase
    var extraOptionsExpanded by remember { mutableStateOf(false) }
    var muteGame by rememberSaveable { mutableStateOf(false) }
    var screensaverAutoplay by rememberSaveable { mutableStateOf(false) }
    val outer = MaaDesignTokens.CornerRadius.inner
    val inner = MaaDesignTokens.CornerRadius.segment
    BoxWithConstraints(modifier = modifier) {
        val menuWidth = maxWidth
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(outer),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs)) {
                val toggleShape = RoundedCornerShape(
                    topStart = outer,
                    bottomStart = outer,
                    topEnd = inner,
                    bottomEnd = inner,
                )
                val toggleModifier = Modifier.weight(1f)
                if (phase.isBusy) {
                    OutlinedButton(
                        onClick = { onIntent(SessionIntent.Stop) },
                        enabled = phase != RunnerPhase.Stopping,
                        shape = toggleShape,
                        modifier = toggleModifier,
                    ) {
                        Text(
                            stringResource(
                                if (phase == RunnerPhase.Stopping) {
                                    R.string.runner_stopping
                                } else {
                                    R.string.runner_stop
                                },
                            ),
                        )
                    }
                } else {
                    Button(
                        onClick = { onIntent(SessionIntent.Start) },
                        enabled = state.canStart,
                        shape = toggleShape,
                        modifier = toggleModifier,
                    ) {
                        Text(stringResource(R.string.runner_start))
                    }
                }
                ExtraOptionsSegment(
                    shape = RoundedCornerShape(
                        topStart = inner,
                        bottomStart = inner,
                        topEnd = outer,
                        bottomEnd = outer,
                    ),
                    onClick = { extraOptionsExpanded = true },
                )
            }
        }
        DropdownMenu(
            expanded = extraOptionsExpanded,
            onDismissRequest = { extraOptionsExpanded = false },
            offset = DpOffset(x = 0.dp, y = -MaaDesignTokens.Spacing.sm),
            modifier = Modifier.width(menuWidth),
        ) {
            Text(
                text = stringResource(R.string.runner_extra_options),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    horizontal = MaaDesignTokens.Spacing.lg,
                    vertical = MaaDesignTokens.Spacing.sm,
                ),
            )
            Column(
                modifier = Modifier.padding(
                    start = MaaDesignTokens.Spacing.sm,
                    end = MaaDesignTokens.Spacing.sm,
                    bottom = MaaDesignTokens.Spacing.sm,
                ),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            ) {
                ExtraOptionSwitchRow(
                    label = stringResource(R.string.extra_mute_game),
                    checked = muteGame,
                    onCheckedChange = { muteGame = it },
                )
                ExtraOptionSwitchRow(
                    label = stringResource(R.string.extra_screensaver_autoplay),
                    checked = screensaverAutoplay,
                    onCheckedChange = { screensaverAutoplay = it },
                )
            }
        }
    }
}

/** 分段按钮右段固宽 64dp：只放一个图标，跟着左段一起伸缩会变形 */
private val ExtraOptionsSegmentWidth = 64.dp

@Composable
private fun ExtraOptionsSegment(
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        shape = shape,
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.width(ExtraOptionsSegmentWidth),
    ) {
        Icon(
            imageVector = Icons.Outlined.Tune,
            contentDescription = stringResource(R.string.runner_extra_options),
            modifier = Modifier.size(MaaDesignTokens.IconSize.md),
        )
    }
}

@Composable
private fun ExtraOptionSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(MaaDesignTokens.CornerRadius.inner),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaaDesignTokens.Spacing.lg,
                    vertical = MaaDesignTokens.Spacing.sm,
                ),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            MaaSwitch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
