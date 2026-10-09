package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.config.backup.ConfigBackupService
import com.aliothmoon.maafw.di.AppCoroutineScope
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager
import com.aliothmoon.maafw.schedule.ScheduleAlarmModeObserver
import com.aliothmoon.maafw.schedule.ScheduleStrategyStore
import com.aliothmoon.maafw.schedule.ScheduleTriggerLog
import com.aliothmoon.maafw.schedule.UnlockGestureStore
import com.aliothmoon.maafw.schedule.WakeUnlockEngine
import com.aliothmoon.maafw.settings.AppSettingsManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.koin.core.qualifier.named

val scheduleModule = module {
    // 定时：两个 receiver 与 FGS 都从 GlobalContext 取，必须是 single
    single { ScheduleStrategyStore(androidContext()) }
    single { ScheduleAlarmManager(androidContext(), get<AppSettingsManager>().runMode::value) }
    single {
        ScheduleAlarmModeObserver(
            appSettings = get(),
            storeLoaded = get<ScheduleStrategyStore>().isLoaded,
            strategies = get<ScheduleStrategyStore>().strategies,
            alarms = get(),
            scope = get(named<AppCoroutineScope>()),
        )
    }
    single { ScheduleTriggerLog() }
    // 手势：解锁 hook（进程级）与唤醒解锁页共用一份，录完回来两边立刻看到
    single { UnlockGestureStore(androidContext()) }
    single { WakeUnlockEngine(get()) }
    // 配置导入导出横跨运行配置、定时与 app 设置三处存储
    single {
        ConfigBackupService(
            context = androidContext(),
            configurationStore = get(),
            scheduleStore = get(),
            alarms = get(),
            appSettings = get(),
            projectRepository = get(),
            runnerPort = get(),
        )
    }
}
