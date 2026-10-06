package com.aliothmoon.maafw.notification.live

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import com.aliothmoon.maafw.settings.AppSettingsManager
import com.xzakota.hyper.notification.focus.util.FocusUtils

/**
 * 读本机对三种展示方式的支持情况，并按用户选择定出实际生效的那一档
 *
 * 进度通知 1 秒一刷都会来问一次，贵的那项（焦点通知权限是一次 ContentProvider 往返）带缓存
 */
class LiveCapabilityProbe(
    context: Context,
    private val appSettings: AppSettingsManager,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    /** 协议版本与岛特性是只读系统属性，进程内不会变 */
    private val islandLikely: Boolean by lazy {
        // 只发 V3 岛模板：协议 < 3 又没有岛特性时系统直接忽略
        runCatching {
            FocusUtils.getFocusProtocolVersion(appContext) >= 3 || FocusUtils.isSupportIsland()
        }.getOrDefault(false)
    }

    @Volatile
    private var islandGrantCache: Pair<Long, Boolean>? = null

    fun snapshot(): LiveCapability {
        val likely = islandLikely
        val islandGranted = likely && islandGranted()
        val liveUpdateGranted = liveUpdateGranted()
        return LiveCapability(
            backend = LiveBackends.resolve(
                preferred = appSettings.liveBackend.value,
                islandAvailable = islandGranted,
                liveUpdateGranted = liveUpdateGranted,
            ),
            postNotifications = NotificationManagerCompat.from(appContext).areNotificationsEnabled(),
            liveUpdateAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA,
            liveUpdateGranted = liveUpdateGranted,
            islandLikely = likely,
            islandGranted = islandGranted,
        )
    }

    /** 用户可能刚从系统设置回来，丢掉缓存重读 */
    fun refresh(): LiveCapability {
        islandGrantCache = null
        return snapshot()
    }

    private fun islandGranted(): Boolean {
        val now = SystemClock.elapsedRealtime()
        islandGrantCache?.let { (at, value) -> if (now - at < ISLAND_GRANT_CACHE_MS) return value }
        val granted = runCatching { FocusUtils.hasFocusPermission(appContext) }.getOrDefault(false)
        islandGrantCache = now to granted
        return granted
    }

    private fun liveUpdateGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        // 三星的 One UI 没给这项开关，查询恒为 false，但请求照样生效（对齐 MaaMeow）
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return true
        return runCatching { manager.canPostPromotedNotifications() }.getOrDefault(false)
    }

    private companion object {
        const val ISLAND_GRANT_CACHE_MS = 30_000L
    }
}
