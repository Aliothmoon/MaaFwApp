package com.aliothmoon.maafw.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.notification.NotificationCenter
import com.aliothmoon.maafw.privileged.RemoteAccessPort
import com.aliothmoon.maafw.settings.AppSettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Shizuku 没在跑就提前提醒，别等到点才在触发日志里记一条「未能开始」
 *
 * adb 模式的 Shizuku 一重启手机就没了，运行中也可能被停；两种都先留宽限期再查——Root 起的
 * Shizuku 开机自启、用户手动重启 Shizuku 都要点时间。到点仍不可用、后端选的是 Shizuku、
 * 且有启用的规则才提醒。只认 Shizuku：Root 没有「没在运行」一说
 *
 * 宽限期用闹钟排而不是协程 delay：进程进了缓存会被冻结，delay 到不了点
 */
class ShizukuDownMonitor(
    private val context: Context,
    private val access: RemoteAccessPort,
    private val appSettings: AppSettingsManager,
    private val store: ScheduleStrategyStore,
    private val notifications: NotificationCenter,
) {
    private val alarmManager by lazy { context.getSystemService(AlarmManager::class.java) }

    /** 本进程见过 Shizuku 活着之后它没了才算「停了」；冷启动时 binder 还没到不算 */
    fun start(scope: CoroutineScope) {
        scope.launch {
            var seenAlive = false
            access.state
                .map { it.shizukuAvailable }
                .distinctUntilChanged()
                .collect { available ->
                    if (available) {
                        seenAlive = true
                        disarm()
                        notifications.withdrawShizukuDown()
                    } else if (seenAlive) {
                        Timber.i("Shizuku stopped, check again in %ds", STOPPED_GRACE_MS / 1000)
                        arm(afterBoot = false, STOPPED_GRACE_MS)
                    }
                }
        }
    }

    fun armAfterBoot() = arm(afterBoot = true, BOOT_GRACE_MS)

    suspend fun check(afterBoot: Boolean) {
        val ready = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
            appSettings.loaded.first { it }
            store.isLoaded.first { it }
        } != null
        if (!ready) return
        if (appSettings.startupBackend.value != RemoteBackend.SHIZUKU) return
        val enabled = store.strategies.value.count { it.enabled }
        if (enabled == 0) return
        if (awaitAvailable()) return
        Timber.w("Shizuku down with %d enabled schedule(s), afterBoot=%s", enabled, afterBoot)
        notifications.notifyShizukuDown(afterBoot)
    }

    /** 闹钟拉起的冷进程，binder 可能还在路上 */
    private suspend fun awaitAvailable(): Boolean = withTimeoutOrNull(BINDER_WAIT_MS) {
        while (!access.refresh().shizukuAvailable) delay(POLL_INTERVAL_MS)
    } != null

    // 不用精确闹钟：晚几分钟提醒无妨，也不必占精确闹钟权限
    private fun arm(afterBoot: Boolean, delayMs: Long) {
        val intent = checkIntent(afterBoot, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                intent,
            )
        }.onFailure { Timber.w(it, "Failed to arm the Shizuku check") }
    }

    private fun disarm() {
        val intent = checkIntent(afterBoot = false, PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager.cancel(intent)
        intent.cancel()
    }

    // extras 不参与 PendingIntent 判等：开机与停止共用一个闹钟，后排的覆盖先排的
    private fun checkIntent(afterBoot: Boolean, flag: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ShizukuCheckReceiver::class.java)
            .putExtra(ShizukuCheckReceiver.EXTRA_AFTER_BOOT, afterBoot),
        flag or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val BOOT_GRACE_MS = 3 * 60_000L
        const val STOPPED_GRACE_MS = 60_000L
        const val BINDER_WAIT_MS = 3_000L
        const val POLL_INTERVAL_MS = 250L
        const val LOAD_TIMEOUT_MS = 3_000L
    }
}
