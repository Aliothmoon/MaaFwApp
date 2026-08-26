package com.aliothmoon.maafw.notification

/**
 * 应用更新下载进度的出口：四段生命周期一个语义入口
 *
 * 单独抽接口是测试便利——[UpdateDownloadNotifier] 直接拿 `Context`，JVM 单测里不方便构造
 * 一个 android 通知，接口让 [com.aliothmoon.maafw.settings.SettingsViewModel] 可以拿到一个
 * 内存 fake / no-op，把 P0 的回退/安装流程跟「要不要弹通知」解耦
 */
interface UpdateDownloadNotification {
    /** 进入下载；[totalBytes] 未知传 `-1L` 用不定态进度条 */
    fun start(version: String, totalBytes: Long)

    /** 下载进度；多次回调，每次重新覆盖同一条通知 */
    fun progress(version: String, downloadedBytes: Long, totalBytes: Long)

    /** 下载成功；impl 应让位给系统安装界面（autoCancel） */
    fun complete(version: String)

    /** 下载失败；impl 应留一条静态错误条目等用户自己滑掉 */
    fun failed(message: String)

    /** 协程取消等异常路径，撤回任何已发的通知 */
    fun cancel()
}
