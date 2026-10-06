package com.aliothmoon.maafw.notification.live

/**
 * 运行通知的三种展示方式（移植自 MaaMeow 的 LiveBackend）
 *
 * - [HYPER_ISLAND]：小米焦点通知 / 超级岛，不看系统版本，要 HyperOS 支持岛且开了焦点通知
 * - [LIVE_UPDATE]：Android 16 的实时更新（promoted ongoing），状态栏胶囊 + 进度条
 * - [PLAIN]：标准通知栏，哪都能用
 */
enum class LiveBackend { HYPER_ISLAND, LIVE_UPDATE, PLAIN }

/**
 * 本机此刻能用什么
 *
 * @param backend 实际生效的那一档：用户选的不可用时按 [LiveBackends.resolve] 往下退
 */
data class LiveCapability(
    val backend: LiveBackend,
    val postNotifications: Boolean,
    /** 系统有没有实时更新这套 API（16+） */
    val liveUpdateAvailable: Boolean,
    /** 实时更新开没开（16+ 的系统开关；三星不给开关，视为开着） */
    val liveUpdateGranted: Boolean,
    /** 看起来是支持超级岛的 HyperOS */
    val islandLikely: Boolean,
    /** 焦点通知权限开没开 */
    val islandGranted: Boolean,
) {
    /** 设置页列出来的选项：本机根本没有的那档不给选 */
    val offered: List<LiveBackend>
        get() = buildList {
            if (islandLikely) add(LiveBackend.HYPER_ISLAND)
            if (liveUpdateAvailable) add(LiveBackend.LIVE_UPDATE)
            add(LiveBackend.PLAIN)
        }
}

object LiveBackends {

    /**
     * 从首选档往下取第一档可用的：超级岛 > 实时更新 > 标准通知栏
     *
     * @param preferred 用户的选择；null 是没选过，按超级岛起步（对齐 MaaMeow 的默认）
     */
    fun resolve(preferred: LiveBackend?, islandAvailable: Boolean, liveUpdateGranted: Boolean): LiveBackend {
        val start = preferred ?: LiveBackend.HYPER_ISLAND
        return when {
            start == LiveBackend.HYPER_ISLAND && islandAvailable -> LiveBackend.HYPER_ISLAND
            start != LiveBackend.PLAIN && liveUpdateGranted -> LiveBackend.LIVE_UPDATE
            else -> LiveBackend.PLAIN
        }
    }
}
