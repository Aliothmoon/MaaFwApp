package com.aliothmoon.maafw.notification

import android.content.Context
import android.content.Intent
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
 */
class UpdateDownloadProgressState(context: Context) : UpdateDownloadNotification {

    private val appContext: Context = context.applicationContext

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    override val state: StateFlow<DownloadState> = _state.asStateFlow()

    override fun start(version: String, totalBytes: Long) {
        _state.value = DownloadState.Downloading(
            version = version,
            downloadedBytes = 0L,
            totalBytes = totalBytes.coerceAtLeast(-1L),
        )
        runCatching {
            appContext.startForegroundService(
                Intent(appContext, UpdateDownloadForegroundService::class.java),
            )
        }
    }

    override fun progress(version: String, downloadedBytes: Long, totalBytes: Long) {
        _state.update { current ->
            if (current is DownloadState.Downloading && current.version == version) {
                current.copy(
                    downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                    totalBytes = totalBytes.coerceAtLeast(-1L),
                )
            } else current
        }
    }

    override fun complete(version: String) {
        _state.update { current ->
            // 已经走了的话不重复置位——避免 progress() 重新跑进来的尾巴覆盖 Complete
            if (current is DownloadState.Complete && current.version == version) current
            else DownloadState.Complete(version)
        }
    }

    override fun failed(message: String) {
        _state.update { current ->
            when (current) {
                is DownloadState.Downloading -> DownloadState.Failed(current.version, message)
                else -> DownloadState.Failed(null, message)
            }
        }
    }

    override fun cancel() {
        _state.value = DownloadState.Idle
    }
}
