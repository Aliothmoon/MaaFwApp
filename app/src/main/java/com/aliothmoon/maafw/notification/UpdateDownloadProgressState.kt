package com.aliothmoon.maafw.notification

import android.content.Context
import android.content.Intent
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import androidx.core.app.NotificationManagerCompat
import com.aliothmoon.maafw.service.UpdateDownloadForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 应用更新下载的状态机实现,被两处消费:
 *
 * - [com.aliothmoon.maafw.settings.SettingsViewModel] 写：[SettingsViewModel.downloadUpdate] 的
 *   四段生命周期（start/progress/complete/failed/cancel）映射到这里
 * - [UpdateDownloadForegroundService] 读：service onCreate 之后 `collect(state)`，每帧衍生
 *   一个 [UpdateDownloadProgressSnapshot] post 到通知栏
 *
 * 进 [DownloadState.Downloading] 同时 [start] 还会额外启一次前台服务——service 决定何时
 * stopSelf（看到 Idle / Complete / Failed 都退）
 *
 * 通知权限守卫：Android 13+ 的 POST_NOTIFICATIONS 是运行时权限，拒了之后 FGS 起来也只会被
 * 系统悄悄吞掉通知栏条目。这里在 [start] 触到 FGS 之前用
 * [NotificationManagerCompat.areNotificationsEnabled] 做一次同步判断；不让 start 就把
 * state 推到 [DownloadState.Failed]、不启 service，让 ViewModel 走到错误分支并提示用户。
 * 这样 SettingsViewModel 即便被绕过（比如未来直接复用 start 的别处入口），通知栏至少不会
 * 出现「FGS 在跑、通知栏啥都没有」的诡异状态。
 */
class UpdateDownloadProgressState(context: Context) {

    private val appContext: Context = context.applicationContext

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    fun start(version: String, totalBytes: Long): Boolean {
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            _state.value = DownloadState.Failed(
                version = null,
                message = uiTextOf(R.string.settings_update_notification_denied),
            )
            return false
        }
        _state.value = DownloadState.Downloading(
            version = version,
            downloadedBytes = 0L,
            totalBytes = totalBytes.coerceAtLeast(-1L),
        )
        val submitted = runCatching {
            appContext.startForegroundService(
                Intent(appContext, UpdateDownloadForegroundService::class.java),
            )
        }
        submitted.onFailure {
            _state.value = DownloadState.Failed(
                version = version,
                message = uiTextOf(R.string.settings_update_notification_service_failed),
            )
        }
        return submitted.isSuccess
    }

    fun progress(version: String, downloadedBytes: Long, totalBytes: Long) {
        _state.update { current ->
            if (current is DownloadState.Downloading && current.version == version) {
                current.copy(
                    downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                    totalBytes = totalBytes.coerceAtLeast(-1L),
                )
            } else current
        }
    }

    fun complete(version: String) {
        _state.update { current ->
            if (current is DownloadState.Complete && current.version == version) current
            else DownloadState.Complete(version)
        }
    }

    fun failed(message: UiText) {
        _state.update { current ->
            when (current) {
                is DownloadState.Downloading -> DownloadState.Failed(current.version, message)
                else -> DownloadState.Failed(null, message)
            }
        }
    }

    fun cancel() {
        _state.value = DownloadState.Idle
    }
}
