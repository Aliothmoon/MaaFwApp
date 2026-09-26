package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.config.UserConfigurationStore
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.log.ExportSnapshots
import com.aliothmoon.maafw.log.AppLogWriter
import com.aliothmoon.maafw.log.DeviceInfoCollector
import com.aliothmoon.maafw.log.DeviceInfoText
import com.aliothmoon.maafw.log.LogExportService
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.settings.AppSettingsManager
import kotlinx.coroutines.flow.first
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val logModule = module {
    single { AppLogWriter() }
    single {
        val context = androidContext()
        val appSettings = get<AppSettingsManager>()
        val configurationStore = get<UserConfigurationStore>()
        val projectRepository = get<ProjectRepository>()
        LogExportService(
            context = context,
            baseDir = { AppPaths.ROOT },
            roots = { listOf(AppPaths.LOG_DIR, AppPaths.DEBUG_DIR) },
            debugMode = appSettings.debugMode::value,
            deviceInfo = {
                DeviceInfoText.render(DeviceInfoCollector.collect(context, AppPaths.ROOT))
            },
            settingsSnapshot = {
                ExportSnapshots.settings(
                    appSettings.settings.first(),
                    redactSecrets = !appSettings.debugMode.value,
                )
            },
            piConfigSnapshot = {
                ExportSnapshots.piConfig(
                    configurationStore.data.first(),
                    (projectRepository.state.value as? ProjectState.Ready)?.definition,
                    redactSecrets = !appSettings.debugMode.value,
                )
            },
        )
    }
}
