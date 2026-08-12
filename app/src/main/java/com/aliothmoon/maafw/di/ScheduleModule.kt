package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.schedule.ScheduleAlarmManager
import com.aliothmoon.maafw.schedule.ScheduleStrategyStore
import com.aliothmoon.maafw.schedule.ScheduleTriggerLog
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val scheduleModule = module {
    // 定时：两个 receiver 与 FGS 都从 GlobalContext 取，必须是 single
    single { ScheduleStrategyStore(androidContext()) }
    single { ScheduleAlarmManager(androidContext()) }
    single { ScheduleTriggerLog() }
}