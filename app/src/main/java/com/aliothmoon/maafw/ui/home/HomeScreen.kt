package com.aliothmoon.maafw.ui.home

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.domain.OverlayControlMode
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.privileged.SystemPermission
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.privileged.PrivilegedServiceState
import com.aliothmoon.maafw.session.ServiceStatus
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.StatusTone
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.ui.i18n.diagnosticsSummaryUiText
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaDiagnosticList
import com.aliothmoon.maafw.ui.components.MaaInfoRow
import com.aliothmoon.maafw.ui.components.MaaLabeledControlRow
import com.aliothmoon.maafw.ui.components.MaaSingleChoiceFlow
import com.aliothmoon.maafw.ui.components.SelectableChipGroup
import com.aliothmoon.maafw.ui.components.MaaSwitch
import com.aliothmoon.maafw.ui.components.maaClickable

/**
 * 首页版面对齐 MaaMeow：概览 -> 资源 -> 运行模式 -> 权限 -> 服务入口 -> 诊断
 * 资源选择与运行模式从设置页迁来，避免与任务页重复
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
        ResourceCard(state, onIntent)
        RunModeCard(state, onIntent)
        PermissionCard(state, onIntent)
        ServiceActionButtons(state, onIntent)
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
            trailing = { ServiceStatusIndicator(status = state.serviceStatus) },
        )
    }
}

/**
 * 状态点 + 文案 + 忙时转圈
 *
 * 语气到颜色的映射只此一处；`StatusTone.Warning` 在 M3 里没有对应槽位，
 * 用 tertiary 而不是自造颜色——深色主题下自造色多半会撞背景
 */
@Composable
private fun ServiceStatusIndicator(status: ServiceStatus) {
    val color = when (status.tone) {
        StatusTone.Primary -> MaterialTheme.colorScheme.primary
        StatusTone.Warning -> MaterialTheme.colorScheme.tertiary
        StatusTone.Error -> MaterialTheme.colorScheme.error
        StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
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
            text = status.text.asString(),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
        if (status.busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(MaaDesignTokens.IconSize.xs),
                strokeWidth = MaaDesignTokens.Border.marker,
                color = color,
            )
        }
    }
}

/**
 * 折叠态只剩主授权行：后端选择、保活两项、说明文字都收进展开区
 *
 * 只列这两项系统权限，是因为悬浮窗与无障碍平时由特权进程代授
 * （见 PermissionManager.grantViaPrivileged），没有手点的必要
 */
@Composable
private fun PermissionCard(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val access = state.remoteAccess
    MaaCard(title = stringResource(R.string.permission_section)) {
        PermissionRow(
            label = access.configuredBackend.display,
            granted = state.remoteAccessGranted,
            loading = state.remoteAccessGranting,
            onRequest = { onIntent(SessionIntent.RequestRemoteAccess) },
        )

        ExpandToggle(expanded = expanded, onToggle = { expanded = !expanded })

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                PermissionRow(
                    label = stringResource(R.string.permission_overlay),
                    granted = state.systemPermissions.overlay,
                    onRequest = {
                        onIntent(SessionIntent.RequestSystemPermission(SystemPermission.Overlay))
                    },
                )
                PermissionRow(
                    label = stringResource(R.string.permission_storage),
                    granted = state.systemPermissions.storage,
                    onRequest = {
                        onIntent(SessionIntent.RequestSystemPermission(SystemPermission.Storage))
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
                PermissionRow(
                    label = stringResource(R.string.permission_accessibility),
                    granted = state.systemPermissions.accessibility,
                    onRequest = {
                        onIntent(SessionIntent.RequestSystemPermission(SystemPermission.Accessibility))
                    },
                )
                PermissionRow(
                    label = stringResource(R.string.permission_notification),
                    granted = state.systemPermissions.notification,
                    onRequest = {
                        onIntent(SessionIntent.RequestSystemPermission(SystemPermission.Notification))
                    },
                )
                Text(
                    text = stringResource(R.string.permission_keepalive_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md)) {
        val connected = state.privilegedServiceConnected
        OutlinedButton(
            onClick = { onIntent(SessionIntent.TogglePrivilegedService) },
            // 连接中不给点：这时候再发一次 bind 只会把状态搅乱
            enabled = state.privilegedService != PrivilegedServiceState.Connecting,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (connected) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = if (connected) Icons.Outlined.LinkOff else Icons.Outlined.Link,
                contentDescription = null,
                modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
            )
            Box(Modifier.size(MaaDesignTokens.Spacing.sm))
            Text(
                stringResource(
                    if (connected) R.string.home_service_disconnect else R.string.home_service_connect,
                ),
            )
        }
        if (state.remoteAccess.configuredBackend == RemoteBackend.SHIZUKU) {
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
                    text = diagnosticsSummaryUiText(diagnostics.size, errors).asString(),
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

/**
 * 运行模式：开关式（对齐 MaaMeow）——开关 ON=后台虚拟屏（默认），OFF=前台主屏
 */
@Composable
private fun RunModeCard(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    MaaCard(title = stringResource(R.string.settings_run_mode)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    if (state.runMode == RunMode.BACKGROUND) R.string.settings_run_mode_background
                    else R.string.settings_run_mode_foreground
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            MaaSwitch(
                checked = state.runMode == RunMode.BACKGROUND,
                enabled = !state.configurationLocked,
                onCheckedChange = { background ->
                    onIntent(
                        SessionIntent.SetRunMode(
                            if (background) RunMode.BACKGROUND else RunMode.FOREGROUND
                        )
                    )
                },
            )
        }
    }
    // 两张卡按模式二选一：控制层只有前台用得上，屏保只有后台用得上
    when (state.runMode) {
        RunMode.FOREGROUND -> OverlayModeCard(state, onIntent)
        RunMode.BACKGROUND -> ScreenSaverCard(state, onIntent)
    }
}

@Composable
private fun OverlayModeCard(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    MaaCard(title = stringResource(R.string.settings_overlay_mode)) {
        MaaSingleChoiceFlow(
            options = listOf(
                OverlayControlMode.FLOAT_BALL to stringResource(R.string.settings_overlay_mode_ball),
                OverlayControlMode.ACCESSIBILITY to stringResource(R.string.settings_overlay_mode_accessibility),
            ),
            selected = state.overlayControlMode,
            enabled = !state.configurationLocked,
            onSelect = { onIntent(SessionIntent.SetOverlayControlMode(it)) },
        )
        Text(
            text = when (state.overlayControlMode) {
                OverlayControlMode.FLOAT_BALL -> stringResource(R.string.settings_overlay_mode_hint_ball)
                OverlayControlMode.ACCESSIBILITY ->
                    stringResource(R.string.settings_overlay_mode_hint_accessibility)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { onIntent(SessionIntent.ShowOverlay) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_overlay_show))
        }
    }
}

@Composable
private fun ScreenSaverCard(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    MaaCard(title = stringResource(R.string.settings_screen_saver)) {
        MaaLabeledControlRow(
            label = stringResource(R.string.settings_screen_saver_auto),
            trailing = {
                MaaSwitch(
                    checked = state.screenSaverEnabled,
                    onCheckedChange = { onIntent(SessionIntent.SetScreenSaverEnabled(it)) },
                )
            },
        )
        Text(
            text = stringResource(R.string.settings_screen_saver_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { onIntent(SessionIntent.ShowScreenSaver) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_screen_saver_show))
        }
    }
}

/**
 * 资源选择：SelectableChipGroup（直接引入自 MaaMeow，后面再对齐设计 token）
 */
@Composable
private fun ResourceCard(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    MaaCard(title = stringResource(R.string.settings_resource)) {
        val environment = state.environment
        val candidates = environment?.resourceCandidates.orEmpty()
        if (candidates.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_no_resources),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SelectableChipGroup(
                label = "",
                selectedValue = environment?.resource?.name ?: "",
                options = candidates.map { it.name to it.label },
                onSelected = { onIntent(SessionIntent.SelectResource(it)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.configurationLocked,
            )
        }
    }
}
