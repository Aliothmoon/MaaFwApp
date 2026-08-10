package com.aliothmoon.maafw.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import timber.log.Timber

/**
 * 开机与覆盖安装后重排全部闹钟
 *
 * 系统不保留跨重启的闹钟，覆盖安装也会把已注册的 PendingIntent 清掉——不补这一步，
 * 用户设完定时重启一次手机就再也不响，且没有任何提示
 */
class ScheduleBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        Timber.i("重排定时闹钟，触发源: %s", intent.action)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val koin = GlobalContext.get()
                val store: ScheduleStrategyStore = koin.get()
                val alarms: ScheduleAlarmManager = koin.get()
                val loaded = withTimeoutOrNull(STORE_READY_TIMEOUT_MS) {
                    store.isLoaded.first { it }
                }
                if (loaded == null) {
                    Timber.w("定时规则读取超时，本次不重排")
                    return@launch
                }
                alarms.rescheduleAll(store.strategies.value)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val STORE_READY_TIMEOUT_MS = 5_000L
    }
}
