package com.aliothmoon.maafw.ui.notification

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.notification.NotificationIntent
import com.aliothmoon.maafw.notification.NotificationUiState
import com.aliothmoon.maafw.notification.live.LiveBackend
import com.aliothmoon.maafw.settings.search.SettingAnchors
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaButton
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaFieldLabel
import com.aliothmoon.maafw.ui.components.MaaLabeledControlRow
import com.aliothmoon.maafw.ui.components.MaaSingleChoiceFlow
import com.aliothmoon.maafw.ui.settings.search.SettingSearchTarget
import timber.log.Timber

private val LiveBackend.labelRes: Int
    get() = when (this) {
        LiveBackend.HYPER_ISLAND -> R.string.notification_live_style_island
        LiveBackend.LIVE_UPDATE -> R.string.notification_live_style_live_update
        LiveBackend.PLAIN -> R.string.notification_live_style_plain
    }

private val LiveBackend.noteRes: Int
    get() = when (this) {
        LiveBackend.HYPER_ISLAND -> R.string.notification_live_note_island
        LiveBackend.LIVE_UPDATE -> R.string.notification_live_note_live_update
        LiveBackend.PLAIN -> R.string.notification_live_note_plain
    }

/**
 * 运行通知的展示方式（移植自 MaaMeow 通知设置页的「运行通知」一节）
 *
 * 只列本机有的那几档；选中档还差哪项系统开关就在下面列出来，点了去应用的通知设置页开。
 * 选的档眼下用不了时写明实际会用哪档，免得以为选了就生效
 */
@Composable
internal fun RunNotificationCard(state: NotificationUiState, onIntent: (NotificationIntent) -> Unit) {
    // 焦点通知、实时更新、通知权限都在系统设置里改，回到这页就重读一遍
    LifecycleResumeEffect(Unit) {
        onIntent(NotificationIntent.RefreshLiveCapability)
        onPauseOrDispose { }
    }
    val live = state.live ?: return
    val context = LocalContext.current
    val selected = state.livePreference?.takeIf { it in live.offered } ?: live.backend
    MaaCard(title = stringResource(R.string.notification_section_live)) {
        SettingSearchTarget(SettingAnchors.NOTIFY_LIVE_STYLE) {
            Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                MaaFieldLabel(stringResource(R.string.notification_live_style))
                MaaSingleChoiceFlow(
                    options = live.offered.map { it to stringResource(it.labelRes) },
                    selected = selected,
                    onSelect = { if (it != state.livePreference) onIntent(NotificationIntent.SetLiveBackend(it)) },
                )
                Text(
                    text = stringResource(selected.noteRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        RequirementRow(
            label = stringResource(R.string.notification_live_permission),
            granted = live.postNotifications,
            onFix = { openAppNotificationSettings(context) },
        )
        when (selected) {
            LiveBackend.HYPER_ISLAND -> RequirementRow(
                label = stringResource(R.string.notification_live_focus),
                granted = live.islandGranted,
                onFix = { openAppNotificationSettings(context) },
            )
            LiveBackend.LIVE_UPDATE -> RequirementRow(
                label = stringResource(R.string.notification_live_promoted),
                granted = live.liveUpdateGranted,
                onFix = { openAppNotificationSettings(context) },
            )
            LiveBackend.PLAIN -> Unit
        }
        if (live.backend != selected) {
            Text(
                text = stringResource(R.string.notification_live_fallback, stringResource(live.backend.labelRes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaaTheme.palette.warning.content,
            )
        }
        val title = stringResource(R.string.notification_live_test_title)
        val body = stringResource(R.string.notification_live_test_text)
        MaaButton(
            onClick = { onIntent(NotificationIntent.SendLiveTest(title, body)) },
            enabled = live.postNotifications,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.notification_send_test))
        }
    }
}

@Composable
private fun RequirementRow(label: String, granted: Boolean, onFix: () -> Unit) {
    MaaLabeledControlRow(
        label = label,
        trailing = {
            if (granted) {
                Text(
                    text = stringResource(R.string.notification_live_granted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaaTheme.palette.success.content,
                )
            } else {
                TextButton(onClick = onFix) { Text(stringResource(R.string.notification_live_open_settings)) }
            }
        },
    )
}

/** 焦点通知与实时更新的开关都在应用自己的通知设置页里 */
private fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { Timber.w(it, "No activity handles the app notification settings") }
}
