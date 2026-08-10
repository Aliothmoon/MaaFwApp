package com.aliothmoon.maafw.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.privileged.SystemPermission
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.ExecutionResult
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaMotion
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaDiagnosticList
import com.aliothmoon.maafw.ui.components.MaaInfoRow
import com.aliothmoon.maafw.ui.components.MaaLabeledControlRow
import com.aliothmoon.maafw.ui.components.MaaSingleChoiceFlow
import com.aliothmoon.maafw.ui.components.maaClickable

/**
 * 首页版面对齐 MaaMeow：概览 -> 权限 -> 服务入口 -> 业务卡
 * MaaMeow 的运行模式卡与更新卡本项目没有对应功能，不占位
 */
@Composable
fun HomeScreen(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(MaaDesignTokens.Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.lg),
    ) {
        OverviewCard(state)
        PermissionCard(state, onIntent)
        ServiceActionButtons(state, onIntent)
        ConfigurationCard(state)
        RunnerCard(state)
        ProjectDiagnosticsCard(state)
    }
}

@Composable
private fun OverviewCard(state: SessionUiState) {
    MaaCard(title = stringResource(R.string.home_overview)) {
        val project = state.projectState
        MaaInfoRow(
            stringResource(R.string.home_name),
            (project as? ProjectState.Ready)?.definition?.name
                ?: stringResource(R.string.home_none),
        )
        MaaInfoRow(
            stringResource(R.string.home_display_resolution),
            state.previewResolution?.let { "${it.width} × ${it.height}" }
                ?: stringResource(R.string.home_none),
        )
        MaaInfoRow(
            stringResource(R.string.home_resource),
            state.environment?.resource?.label ?: stringResource(R.string.home_none),
        )
        MaaInfoRow(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
        MaaLabeledControlRow(
            label = stringResource(R.string.home_service_status),
            labelStyle = MaterialTheme.typography.bodyMedium,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            trailing = { ServiceStatusIndicator(connected = state.privilegedServiceConnected) },
        )
    }
}

/** 状态点 + 文案；颜色是唯一区分，文案只做补充 */
@Composable
private fun ServiceStatusIndicator(connected: Boolean) {
    val color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(MaaDesignTokens.IconSize.dotMd)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = stringResource(
                if (connected) {
                    R.string.home_service_status_connected
                } else {
                    R.string.home_service_status_disconnected
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

/**
 * 提权后端是主行常驻，保活那两项收在展开区里（同 MaaMeow）
 *
 * 只列 manifest 里真正声明的：MaaMeow 的悬浮窗/无障碍/存储对应它的前台模式，本项目没有
 */
@Composable
private fun PermissionCard(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val access = state.remoteAccess
    MaaCard(title = stringResource(R.string.permission_section)) {
        MaaSingleChoiceFlow(
            options = RemoteBackend.entries.map { entry ->
                val suffix = if (access.isAvailable(entry)) {
                    ""
                } else {
                    "（${stringResource(R.string.permission_backend_unavailable)}）"
                }
                entry to "${entry.display}$suffix"
            },
            selected = access.configuredBackend,
            enabled = !state.configurationLocked,
            onSelect = { onIntent(SessionIntent.SetRemoteBackend(it)) },
        )

        PermissionRow(
            label = stringResource(R.string.permission_backend),
            granted = state.remoteAccessGranted,
            loading = state.remoteAccessGranting,
            onRequest = { onIntent(SessionIntent.RequestRemoteAccess) },
        )

        ExpandToggle(expanded = expanded, onToggle = { expanded = !expanded })

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                PermissionRow(
                    label = stringResource(R.string.permission_notification),
                    granted = state.systemPermissions.notification,
                    onRequest = {
                        onIntent(SessionIntent.RequestSystemPermission(SystemPermission.Notification))
                    },
                )
                PermissionRow(
                    label = stringResource(R.string.permission_battery),
                    granted = state.systemPermissions.batteryWhitelist,
                    grantedText = stringResource(R.string.permission_battery_added),
                    onRequest = {
                        onIntent(
                            SessionIntent.RequestSystemPermission(SystemPermission.BatteryWhitelist),
                        )
                    },
                )
                Text(
                    text = stringResource(R.string.permission_keepalive_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = stringResource(R.string.permission_backend_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    onRequest: () -> Unit,
    loading: Boolean = false,
    grantedText: String = stringResource(R.string.permission_granted),
) {
    MaaLabeledControlRow(
        label = label,
        labelStyle = MaterialTheme.typography.bodyMedium,
        trailing = {
            when {
                loading -> CircularProgressIndicator(
                    modifier = Modifier.size(MaaDesignTokens.IconSize.md),
                    strokeWidth = MaaDesignTokens.Border.marker,
                )

                granted -> Text(
                    text = grantedText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )

                else -> TextButton(onClick = onRequest) {
                    Text(stringResource(R.string.permission_request))
                }
            }
        },
    )
}

@Composable
private fun ExpandToggle(expanded: Boolean, onToggle: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .maaClickable(onClick = onToggle)
            .padding(vertical = MaaDesignTokens.Spacing.xs),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                if (expanded) R.string.permission_collapse else R.string.permission_expand,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = tint,
        )
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
        )
    }
}

/** 服务入口；启动/停止特权服务的开关还没做（见 TODO） */
@Composable
private fun ServiceActionButtons(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    if (state.remoteAccess.configuredBackend != RemoteBackend.SHIZUKU) return
    OutlinedButton(
        onClick = { onIntent(SessionIntent.OpenShizuku) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = Icons.Outlined.Build,
            contentDescription = null,
            modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
        )
        Box(Modifier.size(MaaDesignTokens.Spacing.sm))
        Text(stringResource(R.string.permission_open_shizuku))
    }
}

@Composable
private fun ConfigurationCard(state: SessionUiState) {
    MaaCard(title = stringResource(R.string.home_current_config)) {
        val active = state.activeConfiguration
        if (active == null) {
            Text(
                text = stringResource(R.string.home_no_config_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            MaaInfoRow(stringResource(R.string.home_name), active.name)
            MaaInfoRow(
                stringResource(R.string.home_tasks),
                stringResource(R.string.home_count_items, active.tasks.size),
            )
            MaaInfoRow(
                stringResource(R.string.home_enabled),
                stringResource(R.string.home_enabled_count, active.enabledTaskCount, active.effectiveTaskCount),
            )
        }
    }
}

@Composable
private fun RunnerCard(state: SessionUiState) {
    MaaCard(title = stringResource(R.string.home_runner_status)) {
        val runner = state.runner
        val execution = runner.activeExecution
        MaaInfoRow(stringResource(R.string.home_state), phaseText(runner.phase))
        if (execution != null) {
            if (execution.totalTaskCount > 0) {
                val animatedProgress by animateFloatAsState(
                    targetValue = execution.completedTaskCount.toFloat() / execution.totalTaskCount,
                    animationSpec = MaaMotion.enter(),
                    label = "runnerProgress",
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            MaaInfoRow(
                stringResource(R.string.home_progress),
                "${execution.completedTaskCount} / ${execution.totalTaskCount}",
            )
            execution.currentTaskName?.let { MaaInfoRow(stringResource(R.string.home_current_task), it) }
        }
        runner.latestResult?.let { result ->
            MaaInfoRow(stringResource(R.string.home_latest_result), resultText(result))
            result.taskResults.filterNot { it.success }.forEach { failure ->
                Text(
                    text = failure.message
                        ?.let { stringResource(R.string.home_task_failed_with_message, failure.taskName, it) }
                        ?: stringResource(R.string.home_task_failed, failure.taskName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 项目加载失败或有诊断时才出现；正常态不占版面 */
@Composable
private fun ProjectDiagnosticsCard(state: SessionUiState) {
    val project = state.projectState
    val diagnostics = state.visibleDiagnostics
    if (project is ProjectState.Ready && diagnostics.isEmpty()) return
    MaaCard(title = stringResource(R.string.home_project)) {
        when (project) {
            is ProjectState.Loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(MaaDesignTokens.Spacing.xs))
                Text(stringResource(R.string.home_project_loading), style = MaterialTheme.typography.bodyMedium)
            }

            is ProjectState.Error -> {
                Text(
                    text = stringResource(R.string.home_project_load_failed),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                MaaDiagnosticList(project.diagnostics.take(5))
            }

            is ProjectState.Ready -> {
                val errors = diagnostics.count { it.severity == DiagnosticSeverity.Error }
                Text(
                    text = if (errors > 0) {
                        pluralStringResource(
                            R.plurals.home_diagnostics_summary_with_errors,
                            errors,
                            diagnostics.size,
                            errors,
                        )
                    } else {
                        stringResource(R.string.home_diagnostics_summary, diagnostics.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (errors > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun phaseText(phase: RunnerPhase): String = when (phase) {
    is RunnerPhase.Unavailable -> stringResource(R.string.phase_unavailable, phase.reason)
    RunnerPhase.Idle -> stringResource(R.string.phase_idle)
    RunnerPhase.Preparing -> stringResource(R.string.phase_preparing)
    RunnerPhase.Running -> stringResource(R.string.phase_running)
    RunnerPhase.Stopping -> stringResource(R.string.phase_stopping)
}

@Composable
private fun resultText(result: ExecutionResult): String = when (result) {
    is ExecutionResult.Completed -> pluralStringResource(
        R.plurals.result_completed,
        result.taskResults.size,
        result.taskResults.size,
    )

    is ExecutionResult.CompletedWithFailures ->
        result.taskResults.count { !it.success }.let { failures ->
            pluralStringResource(R.plurals.result_completed_with_failures, failures, failures)
        }

    is ExecutionResult.Cancelled -> stringResource(R.string.result_cancelled)
    is ExecutionResult.Failed -> stringResource(R.string.result_failed, result.reason)
}
