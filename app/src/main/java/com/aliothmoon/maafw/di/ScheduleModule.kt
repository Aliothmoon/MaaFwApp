package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.schedule.ScheduleAlarmManager
import com.aliothmoon.maafw.settings.AppSettingsManager
import com.aliothmoon.maafw.schedule.ScheduleStrategyStore
import com.aliothmoon.maafw.schedule.ScheduleTriggerLog
import com.aliothmoon.maafw.di.AppCoroutineScope
import com.aliothmoon.maafw.schedule.ScheduleAlarmModeObserver
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
}
