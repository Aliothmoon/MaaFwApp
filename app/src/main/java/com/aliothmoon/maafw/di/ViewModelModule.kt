package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.log.AppLogDetailViewModel
import com.aliothmoon.maafw.log.AppLogViewModel
import com.aliothmoon.maafw.log.RunLogArchiveViewModel
import com.aliothmoon.maafw.log.RunLogDetailViewModel
import com.aliothmoon.maafw.notification.NotificationSettingsViewModel
import com.aliothmoon.maafw.schedule.ScheduleViewModel
import com.aliothmoon.maafw.session.SessionViewModel
import com.aliothmoon.maafw.settings.SettingsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule = module {
    viewModelOf(::RunLogArchiveViewModel)
    viewModelOf(::RunLogDetailViewModel)
    viewModelOf(::AppLogViewModel)
    viewModelOf(::AppLogDetailViewModel)
    viewModelOf(::NotificationSettingsViewModel)
    viewModelOf(::SessionViewModel)
    viewModelOf(::ScheduleViewModel)

    viewModel {
        SettingsViewModel(get())
    }
}