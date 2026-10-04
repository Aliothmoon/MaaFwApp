package com.aliothmoon.maafw.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.schedule.ScheduleHealthIssue
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaPromptDialog

@Composable
private fun ScheduleHealthIssue.title(backend: RemoteBackend): String = when (this) {
    ScheduleHealthIssue.BACKEND -> stringResource(R.string.schedule_health_backend, backend.display)
    ScheduleHealthIssue.BATTERY -> stringResource(R.string.schedule_health_battery)
    ScheduleHealthIssue.EXACT_ALARM -> stringResource(R.string.schedule_exact_alarm_blocked)
    ScheduleHealthIssue.NOTIFICATION -> stringResource(R.string.schedule_health_notification)
    ScheduleHealthIssue.OVERLAY -> stringResource(R.string.schedule_health_overlay)
    ScheduleHealthIssue.WAKE_CREDENTIAL -> stringResource(R.string.schedule_health_wake_credential)
}

@Composable
private fun ScheduleHealthIssue.description(): String = when (this) {
    ScheduleHealthIssue.BACKEND -> stringResource(R.string.schedule_health_backend_desc)
    ScheduleHealthIssue.BATTERY -> stringResource(R.string.schedule_health_battery_desc)
    ScheduleHealthIssue.EXACT_ALARM -> stringResource(R.string.schedule_exact_alarm_hint)
    ScheduleHealthIssue.NOTIFICATION -> stringResource(R.string.schedule_health_notification_desc)
    ScheduleHealthIssue.OVERLAY -> stringResource(R.string.schedule_health_overlay_desc)
    ScheduleHealthIssue.WAKE_CREDENTIAL -> stringResource(R.string.schedule_health_wake_credential_desc)
}

/** 调用方保证 [issues] 非空 */
@Composable
fun ScheduleHealthCard(
    issues: List<ScheduleHealthIssue>,
    backend: RemoteBackend,
    onFix: (ScheduleHealthIssue) -> Unit,
    modifier: Modifier = Modifier,
) {
    MaaCard(modifier = modifier, title = stringResource(R.string.schedule_health_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
            issues.forEach { issue ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
                    ) {
                        Text(text = issue.title(backend), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = issue.description(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onFix(issue) }) {
                        Text(stringResource(R.string.schedule_health_fix))
                    }
                }
            }
        }
    }
}

/**
 * 保存后的逐项引导：一次只摆一项，点「去设置」就翻到下一项
 *
 * 去了系统页不一定授成，但同一项回来再弹一遍只会让人原地打转；没授成的仍留在健康卡上。
 * [pending] 跟着环境现算，授好的项自己消失；全部走完或用户点「稍后」即收起
 */
@Composable
fun ScheduleSetupWizard(
    pending: List<ScheduleHealthIssue>,
    backend: RemoteBackend,
    onFix: (ScheduleHealthIssue) -> Unit,
    onDismiss: () -> Unit,
) {
    var visited by remember { mutableStateOf(emptySet<ScheduleHealthIssue>()) }
    val current = pending.firstOrNull { it !in visited }
    // 逐项走完、或者全都授好了：收起并摘掉 VM 的标记，免得日后哪项权限掉了它又凭空弹出来
    LaunchedEffect(current == null) {
        if (current == null) {
            visited = emptySet()
            onDismiss()
        }
    }
    current ?: return
    MaaPromptDialog(
        title = current.title(backend),
        message = current.description(),
        icon = Icons.Outlined.Schedule,
        confirmText = stringResource(R.string.schedule_go_to_settings),
        onConfirm = {
            visited = visited + current
            onFix(current)
        },
        dismissText = stringResource(R.string.common_later),
        onDismissRequest = {
            visited = emptySet()
            onDismiss()
        },
        dismissOnOutsideClick = true,
    )
}

