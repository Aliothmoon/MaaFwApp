package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.log.AppLogWriter
import com.aliothmoon.maafw.log.DeviceInfoCollector
import com.aliothmoon.maafw.log.DeviceInfoText
import com.aliothmoon.maafw.log.LogExportService
import com.aliothmoon.maafw.settings.AppSettingsManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val logModule = module {
    single { AppLogWriter() }
    single {
        val context = androidContext()
        LogExportService(
            context = context,
            baseDir = { AppPaths.ROOT },
            roots = { listOf(AppPaths.LOG_DIR, AppPaths.DEBUG_DIR) },
            debugMode = get<AppSettingsManager>().debugMode::value,
            deviceInfo = {
                DeviceInfoText.render(DeviceInfoCollector.collect(context, AppPaths.ROOT))
            },
        )
    }
}