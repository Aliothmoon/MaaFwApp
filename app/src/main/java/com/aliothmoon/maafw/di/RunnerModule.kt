package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.i18n.LocalizedTextRenderer
import com.aliothmoon.maafw.overlay.screensaver.ScreenSaverOverlayManager
import com.aliothmoon.maafw.runner.AutoSleepHook
import com.aliothmoon.maafw.runner.CloseTargetAppHook
import com.aliothmoon.maafw.runner.CountdownHook
import com.aliothmoon.maafw.runner.FocusContentResolver
import com.aliothmoon.maafw.runner.FocusDispatcher
import com.aliothmoon.maafw.runner.ForegroundModePrecheck
import com.aliothmoon.maafw.runner.KeepAliveHook
import com.aliothmoon.maafw.runner.MaaFrameworkRunnerPort
import com.aliothmoon.maafw.runner.NotificationHook
import com.aliothmoon.maafw.runner.PreviewPort
import com.aliothmoon.maafw.runner.PrivilegedFocusContentResolver
import com.aliothmoon.maafw.runner.RemotePreviewPort
import com.aliothmoon.maafw.runner.RunKeepAlive
import com.aliothmoon.maafw.runner.RunLauncher
import com.aliothmoon.maafw.runner.RunJournal
import com.aliothmoon.maafw.runner.RunLogRecorder
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.RunScreenSaver
import com.aliothmoon.maafw.runner.RunSessionLogStore
import com.aliothmoon.maafw.runner.ScreenSaverHook
import com.aliothmoon.maafw.runner.SessionLogHook
import com.aliothmoon.maafw.runner.WakeUnlockHook
import com.aliothmoon.maafw.service.ForegroundRunKeepAlive
import com.aliothmoon.maafw.settings.AppSettingsManager
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

val runnerModule = module {
    // StubRunnerPort 保留给测试与 Preview，不再进 DI
    single<RunnerPort> {
        val context = androidContext()
        MaaFrameworkRunnerPort(
            installer = get(),
            apkPath = context.applicationInfo.sourceDir,
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
            runMode = get<AppSettingsManager>().runMode::value,
            resolutionPreference = get<AppSettingsManager>().resolutionPreference::value,
            debugMode = get<AppSettingsManager>().debugMode::value,
            scope = get(named<AppCoroutineScope>()),
            servicePort = get(),
        )
    }

    single<FocusContentResolver> {
        PrivilegedFocusContentResolver(
            installer = get(),
            servicePort = get(),
        )
    }

    // notification 那一档要在 Activity 销毁后仍然响
    single {
        FocusDispatcher(
            projectRepository = get(),
            resolver = get(),
            runnerPort = get(),
            scope = get(named<AppCoroutineScope>()),
        )
    }

    single { RunSessionLogStore() }

    single {
        RunLogRecorder(
            runnerPort = get(),
            focusDispatcher = get(),
            store = get(),
            renderText = get<LocalizedTextRenderer>()::render,
            includeDetails = get<AppSettingsManager>().debugMode::value,
            scope = get(named<AppCoroutineScope>()),
        )
    }
    single<RunJournal> { get<RunLogRecorder>() }

    single<PreviewPort> {
        RemotePreviewPort(
            scope = get(named<AppCoroutineScope>()),
            servicePort = get(),
            touchPreviewEnabled = get<AppSettingsManager>().touchPreviewEnabled,
        )
    }

    single<RunKeepAlive> { ForegroundRunKeepAlive(androidContext()) }

    single<RunScreenSaver> {
        val manager = get<ScreenSaverOverlayManager>()
        object : RunScreenSaver {
            override suspend fun show(): Boolean = manager.show()
            override suspend fun hide() = manager.hide()
        }
    }

    single {
        RunLauncher(
            projectRepository = get(),
            configurationStore = get(),
            runnerPort = get(),
            prechecks = listOf(ForegroundModePrecheck),
            hooks = listOf(
                SessionLogHook(get()),
                NotificationHook(get()),
                AutoSleepHook(get()),
                WakeUnlockHook(get(), get<AppSettingsManager>()),
                ScreenSaverHook(get<AppSettingsManager>(), get()),
                CloseTargetAppHook(get(), get<AppSettingsManager>()),
                CountdownHook,
                KeepAliveHook(get()),
            ),
            runMode = get<AppSettingsManager>().runMode::value,
            scope = get(named<AppCoroutineScope>()),
            journal = get(),
        )
    }
}