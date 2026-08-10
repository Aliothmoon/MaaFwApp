package com.aliothmoon.maafw.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.privileged.ShizukuReadiness
import com.aliothmoon.maafw.privileged.ShizukuReadinessStage

/**
 * Shizuku 就绪引导弹窗
 *
 * 纯展示：按 [ShizukuReadiness.stage] 渲染对应的标题/正文/按钮，动作一律上抛
 * [ShizukuReadiness.needsGuidance] 为 false 时不渲染
 */
@Composable
fun ShizukuReadinessDialog(
    readiness: ShizukuReadiness,
    onInstall: () -> Unit,
    onOpenApp: () -> Unit,
    onRequestAuth: () -> Unit,
    onDismiss: () -> Unit,
    onSwitchToRoot: () -> Unit,
    isRequesting: Boolean = false,
) {
    if (!readiness.needsGuidance) return

    val switchToRootText = stringResource(R.string.dialog_shizuku_switch_to_root)
        .takeIf { readiness.canSwitchToRoot }
    val skipText = stringResource(R.string.dialog_shizuku_skip_check)

    when (readiness.stage) {
        ShizukuReadinessStage.NotInstalled -> MaaPromptDialog(
            title = stringResource(R.string.dialog_shizuku_not_installed_title),
            message = stringResource(R.string.dialog_shizuku_not_installed_message),
            icon = Icons.Outlined.WarningAmber,
            confirmText = stringResource(R.string.dialog_shizuku_install_confirm),
            onConfirm = onInstall,
            neutralText = switchToRootText,
            onNeutralClick = onSwitchToRoot,
            dismissText = skipText,
            onDismissRequest = onDismiss,
        )

        ShizukuReadinessStage.NotRunning -> MaaPromptDialog(
            title = stringResource(R.string.dialog_shizuku_not_running_title),
            message = stringResource(R.string.dialog_shizuku_not_running_message),
            icon = Icons.Outlined.Build,
            confirmText = stringResource(R.string.dialog_shizuku_open_app),
            onConfirm = onOpenApp,
            neutralText = switchToRootText,
            onNeutralClick = onSwitchToRoot,
            dismissText = skipText,
            onDismissRequest = onDismiss,
        )

        // Sui 以 Root 身份跑，兼容性未知；只告知一次，不给出口
        ShizukuReadinessStage.SuiAvailable -> MaaPromptDialog(
            title = stringResource(R.string.dialog_sui_detected_title),
            message = stringResource(R.string.dialog_sui_detected_message),
            icon = Icons.Outlined.Info,
            confirmText = stringResource(R.string.common_got_it),
            onConfirm = onDismiss,
            onDismissRequest = onDismiss,
        )

        ShizukuReadinessStage.NeedAuth -> MaaPromptDialog(
            title = stringResource(R.string.dialog_shizuku_need_auth_title),
            message = stringResource(R.string.dialog_shizuku_need_auth_message),
            icon = Icons.Outlined.Build,
            confirmText = stringResource(
                if (isRequesting) R.string.permission_requesting else R.string.permission_request,
            ),
            onConfirm = onRequestAuth,
            neutralText = switchToRootText,
            onNeutralClick = onSwitchToRoot,
            dismissText = skipText,
            onDismissRequest = onDismiss,
        )

        ShizukuReadinessStage.Ready -> Unit
    }
}
