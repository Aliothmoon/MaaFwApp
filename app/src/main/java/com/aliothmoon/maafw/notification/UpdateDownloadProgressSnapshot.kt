package com.aliothmoon.maafw.notification

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.UiText

/** 与 `RunnerState -> RunProgressSnapshot` 一档:从 [DownloadState] 派生通知一帧所需的字段 */
data class UpdateDownloadProgressSnapshot(
    val titleRes: Int,
    val contentText: String,
    val shortCriticalText: String?,
    val progress: Int,
    val indeterminate: Boolean,
    val barColor: Int,
    val ongoing: Boolean,
    val autoCancel: Boolean,
    val channelId: String,
)

object UpdateDownloadProgressSnapshots {
    const val PROGRESS_MAX = 1_000

    /** 与 RunProgressSnapshot 的 BAR_COLOR 保持一致——同源进度色,进通知栏看上去是同一个系列 */
    const val BAR_COLOR = 0xFF2196F3.toInt()

    /**
     * 与 [DownloadState] 一一对应,穷尽 [DownloadState] 子类型
     *
     * 1Hz 节流在 [UpdateDownloadForegroundService] 一侧做,这里只算一帧;
     * 文案统一走 [UiText]，由调用方用 [resolve] 拿着 Context 展开成 String
     */
    fun from(
        state: DownloadState,
        versionLabel: (String) -> String,
        sizeLabel: (Long) -> String,
        resolve: (UiText) -> String,
        errorMessage: UiText? = null,
    ): UpdateDownloadProgressSnapshot = when (state) {
        DownloadState.Idle -> UpdateDownloadProgressSnapshot(
            // 不会到这里:_observe 里 Idle 直接 stopSelf
            titleRes = R.string.notification_update_download_title,
            contentText = "",
            shortCriticalText = null,
            progress = 0,
            indeterminate = true,
            barColor = BAR_COLOR,
            ongoing = false,
            autoCancel = false,
            channelId = CHANNEL_ID,
        )

        is DownloadState.Downloading -> {
            val totalKnown = state.totalBytes > 0L
            val pct = if (totalKnown) {
                val clamped = state.downloadedBytes.coerceIn(0L, state.totalBytes)
                (clamped * PROGRESS_MAX / state.totalBytes).toInt()
            } else 0
            UpdateDownloadProgressSnapshot(
                titleRes = R.string.notification_update_download_title,
                contentText = contentForDownloading(state.downloadedBytes, state.totalBytes, sizeLabel),
                shortCriticalText = versionLabel(state.version),
                progress = pct,
                indeterminate = !totalKnown,
                barColor = BAR_COLOR,
                ongoing = true,
                autoCancel = false,
                channelId = CHANNEL_ID,
            )
        }

        is DownloadState.Complete -> UpdateDownloadProgressSnapshot(
            titleRes = R.string.notification_update_download_complete_title,
            contentText = errorMessage?.let(resolve) ?: "",
            shortCriticalText = versionLabel(state.version),
            progress = PROGRESS_MAX,
            indeterminate = false,
            barColor = BAR_COLOR,
            ongoing = false,
            autoCancel = true,
            channelId = CHANNEL_ID,
        )

        is DownloadState.Failed -> UpdateDownloadProgressSnapshot(
            titleRes = R.string.notification_update_download_failed_title,
            contentText = resolve(errorMessage ?: state.message),
            shortCriticalText = state.version?.let(versionLabel),
            progress = 0,
            indeterminate = true,
            barColor = BAR_COLOR,
            ongoing = false,
            autoCancel = true,
            channelId = CHANNEL_ID,
        )
    }

    private fun contentForDownloading(
        downloaded: Long,
        total: Long,
        sizeLabel: (Long) -> String,
    ): String =
        if (total > 0L) {
            "${sizeLabel(downloaded)} / ${sizeLabel(total)}"
        } else {
            sizeLabel(downloaded)
        }

    const val CHANNEL_ID = "maa_update_download"
}
