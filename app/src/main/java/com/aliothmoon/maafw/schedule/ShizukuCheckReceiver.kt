package com.aliothmoon.maafw.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aliothmoon.maafw.MaaDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext

/** [ShizukuDownMonitor] 排的复查闹钟 */
class ShizukuCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val afterBoot = intent.getBooleanExtra(EXTRA_AFTER_BOOT, false)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + MaaDispatchers.IO + scheduleReceiverExceptionHandler).launch {
            try {
                // goAsync 只给 10 秒
                withTimeoutOrNull(CHECK_TIMEOUT_MS) {
                    GlobalContext.get().get<ShizukuDownMonitor>().check(afterBoot)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_AFTER_BOOT = "after_boot"
        private const val CHECK_TIMEOUT_MS = 9_000L
    }
}
