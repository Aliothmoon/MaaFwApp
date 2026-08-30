package com.aliothmoon.maafw.notification

import com.aliothmoon.maafw.i18n.UiText

/**
 * 应用更新下载进度的状态机
 *
 * 用 sealed 而非 enum 是因为 [Downloading] 是带 payload 的载体——一个普通的 enum + 一个并排的
 * data class 让外部写起来不优雅（要么塞进 hash map 里，要么往 enum 上塞可空字段）。
 * 走 sealed 让 [UpdateDownloadForegroundService] 的 `when` 一处穷尽
 *
 * 状态在 [UpdateDownloadProgressState] 里以 [kotlinx.coroutines.flow.MutableStateFlow] 持有，
 * service 端用 [kotlinx.coroutines.flow.collectLatest] 订阅
 */
sealed interface DownloadState {
    /** 还没开始,或者上一次结束之后的清零状态。service 用这个触发 stopSelf */
    data object Idle : DownloadState

    /** [totalBytes] 未知 (-1L) 时走不定态进度条;已知后切定态 */
    data class Downloading(
        val version: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : DownloadState

    /** 下载成功,准备进系统安装器 */
    data class Complete(val version: String) : DownloadState

    /** 下载失败,通知里留一条静态错误条目 */
    data class Failed(val version: String?, val message: UiText) : DownloadState
}
