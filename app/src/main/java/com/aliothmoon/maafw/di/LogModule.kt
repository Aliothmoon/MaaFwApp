package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.log.AppLogWriter
import com.aliothmoon.maafw.log.LogExportService
import com.aliothmoon.maafw.settings.AppSettingsManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val logModule = module {
    single { AppLogWriter() }
    single {
        LogExportService(
            context = androidContext(),
            baseDir = { AppPaths.ROOT },
            roots = { listOf(AppPaths.LOG_DIR, AppPaths.DEBUG_DIR) },
            debugMode = get<AppSettingsManager>().debugMode::value,
        )
    }
}