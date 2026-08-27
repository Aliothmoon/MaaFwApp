package com.aliothmoon.maafw.notification

import kotlinx.coroutines.flow.StateFlow

/**
 * 应用更新下载进度的出口——按 [com.aliothmoon.maafw.settings.SettingsViewModel] 那一侧的
 * 生命周期四段起头，加一个 [cancel]。具体实施走 [UpdateDownloadProgressState] +
 * [com.aliothmoon.maafw.service.UpdateDownloadForegroundService]，跟运行时任务进度
 * 通知（[RunEventNotifier] / [com.aliothmoon.maafw.service.RunForegroundService]）
 * 是同一套组件
 *
 * 抽接口是因为 [SettingsViewModel] 已经在 `notificationModule` 里只看到接口，
 * JVM 单测里换成 noop 实现不需要 Android Context，也不需要启任何 service
 */
interface UpdateDownloadNotification {
    /** 通知前台 service 当前帧状态。Idle 时 service 直接 stopSelf */
    val state: StateFlow<DownloadState>

    /** 进入下载；[totalBytes] 未知传 `-1L` 用不定态进度条。会启前台 service */
    /** @return 前台通知服务是否成功提交；false 时调用方应中止下载并提示 */
    fun start(version: String, totalBytes: Long): Boolean

    /** 下载进度；多个回调，最后一次会覆盖之前的同 ID 通知 */
    fun progress(version: String, downloadedBytes: Long, totalBytes: Long)

    /** 下载成功；service 切静态条目 + autoCancel + stopSelf */
    fun complete(version: String)

    /** 下载失败；service 切静态错误条目 + stopSelf */
    fun failed(message: String)

    /** 协程取消或用户主动撤回；service 立刻 stopSelf */
    fun cancel()
}
