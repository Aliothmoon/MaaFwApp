package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.config.UserConfigurationStore
import com.aliothmoon.maafw.config.passwordPlaintexts
import com.aliothmoon.maafw.constant.AppFiles
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.log.AppLogWriter
import com.aliothmoon.maafw.log.DeviceInfoCollector
import com.aliothmoon.maafw.log.DeviceInfoText
import com.aliothmoon.maafw.log.LogExportCollector
import com.aliothmoon.maafw.log.LogExportService
import com.aliothmoon.maafw.log.PrivilegedDeathForensics
import com.aliothmoon.maafw.log.ProcessExitRecorder
import com.aliothmoon.maafw.log.ProcessForensics
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.settings.AppSettingsManager
import org.koin.android.ext.koin.androidContext
import kotlinx.coroutines.flow.first
import org.koin.dsl.module
import java.io.File

val logModule = module {
    single { AppLogWriter() }
    single { ProcessExitRecorder(androidContext(), get<PrivilegedServicePort>()) }
    single {
        ProcessForensics(
            servicePort = get(),
            recorder = get(),
            forensics = PrivilegedDeathForensics(PrivilegedDeathForensics.PrefsStore(androidContext())),
            dir = { File(AppPaths.LOG_DIR, AppFiles.PRIVILEGED_DEATH_DIR) },
            processPrefix = "${BuildConfig.APPLICATION_ID}:",
        )
    }
    single {
        val context = androidContext()
        val configurationStore = get<UserConfigurationStore>()
        LogExportService(
            context = context,
            baseDir = { AppPaths.ROOT },
            roots = { listOf(AppPaths.LOG_DIR, AppPaths.DEBUG_DIR) },
            piLogs = {
                LogExportCollector.PiLogs(File(AppPaths.ROOT, AppFiles.PI_DIR), BuildConfig.MAFW_PI_LOG_INCLUDE.toList())
            },
            debugMode = get<AppSettingsManager>().debugMode::value,
            deviceInfo = {
                DeviceInfoText.render(DeviceInfoCollector.collect(context, AppPaths.ROOT))
            },
            secrets = { configurationStore.data.first().passwordPlaintexts() },
        )
    }
}
