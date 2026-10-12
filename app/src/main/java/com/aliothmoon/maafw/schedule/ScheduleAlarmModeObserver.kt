package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.settings.AppSettingsGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * 运行模式变化后重排定时闹钟
 *
 * 闹钟的提前量在 scheduleNext 那一刻写死；模式切换后不重排，已挂上的闹钟仍会按旧模式
 * 提前或准时触发，而执行阶段读的是当前模式，两边就会错位
 */
class ScheduleAlarmModeObserver(
    private val appSettings: AppSettingsGateway,
    private val storeLoaded: StateFlow<Boolean>,
    private val strategies: StateFlow<List<ScheduleStrategy>>,
    private val alarms: ScheduleAlarmManager,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            appSettings.runMode.drop(1).collect {
                val ready = withTimeoutOrNull(STORE_READY_TIMEOUT_MS) {
                    appSettings.loaded.first { it }
                    storeLoaded.first { it }
                }
                if (ready == null) {
                    Timber.w("Timed out reading settings or schedule rules after run mode change")
                    return@collect
                }
                alarms.rescheduleAll(strategies.value)
            }
        }
    }

    private companion object {
        const val STORE_READY_TIMEOUT_MS = 5_000L
    }
}
