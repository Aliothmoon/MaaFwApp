package com.aliothmoon.maafw.schedule
import com.aliothmoon.maafw.MaaDispatchers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import timber.log.Timber

/**
 * 系统事件后重排全部闹钟
 *
 * - 开机、覆盖安装：系统不保留跨重启的闹钟，覆盖安装也会把已注册的 PendingIntent 清掉——
 *   不补这一步，用户设完定时重启一次手机就再也不响，且没有任何提示
 * - 改时区、改时间：闹钟存的是绝对时刻，按旧时区算出的「每天 8 点」换了时区就不再是 8 点
 * - 精确闹钟权限授予：之前可能只挂上了 inexact 闹钟，授权后换回 setExactAndAllowWhileIdle。
 *   撤销不用管：系统撤权会杀进程并清掉闹钟，下次进程启动由 `MaaFwApp` 重排
 */
class ScheduleBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESYNC_ACTIONS) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + MaaDispatchers.IO + scheduleReceiverExceptionHandler).launch {
            try {
                val koin = GlobalContext.get()
                resyncScheduleAlarms(koin.get(), koin.get(), reason = intent.action.orEmpty())
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val RESYNC_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            // AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED，API 31 才有常量
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}

/**
 * 闹钟相关 receiver 的 goAsync 协程兜底
 *
 * 写规则的 IOException、ROM 闹钟数上限之类漏到这里只记一笔；没人接会把进程带崩，
 * 而 finally 里的 pendingResult.finish() 照常执行
 */
internal val scheduleReceiverExceptionHandler = CoroutineExceptionHandler { _, e ->
    Timber.e(e, "Schedule receiver work failed")
}

/**
 * 等规则读盘后整批重排；读不出就跳过，等下一个重排时机
 *
 * 幂等，随便调：一条规则只有一个闹钟槽位，重排就是覆盖；挂着的重试与已投递的时刻
 * 由 [ScheduleAlarmManager.rescheduleAll] 照账本接回，不会被顶掉或重挂
 */
suspend fun resyncScheduleAlarms(
    store: ScheduleStrategyStore,
    alarms: ScheduleAlarmManager,
    reason: String,
) {
    val loaded = withTimeoutOrNull(STORE_READY_TIMEOUT_MS) {
        store.isLoaded.first { it }
    }
    if (loaded == null) {
        Timber.w("Timed out reading schedule rules; skipping reschedule (%s)", reason)
        return
    }
    val strategies = store.strategies.value
    alarms.rescheduleAll(strategies)
    Timber.i("Rescheduled %d schedule rules (%s)", strategies.size, reason)
}

private const val STORE_READY_TIMEOUT_MS = 5_000L
