package com.aliothmoon.maafw.di

import android.app.ActivityManager
import android.app.KeyguardManager
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.config.UserConfigurationStore
import com.aliothmoon.maafw.config.passwordPlaintexts
import com.aliothmoon.maafw.i18n.LocalizedTextRenderer
import com.aliothmoon.maafw.overlay.OverlayController
import com.aliothmoon.maafw.overlay.screensaver.ScreenSaverOverlayManager
import com.aliothmoon.maafw.runner.AppBlacklist
import com.aliothmoon.maafw.runner.AppBlacklistHook
import com.aliothmoon.maafw.runner.AppBlacklistPrecheck
import com.aliothmoon.maafw.runner.AutoSleepHook
import com.aliothmoon.maafw.runner.IdleProbeHook
import com.aliothmoon.maafw.runner.BackgroundModePrecheck
import com.aliothmoon.maafw.runner.CloseTargetAppHook
import com.aliothmoon.maafw.runner.CollapseAfterRunHook
import com.aliothmoon.maafw.runner.CompletionScreenshotHook
import com.aliothmoon.maafw.runner.CountdownHook
import com.aliothmoon.maafw.runner.DisplayHazardNoticeHook
import com.aliothmoon.maafw.runner.DisplayHazardPrecheck
import com.aliothmoon.maafw.runner.DisplayHazardProbe
import com.aliothmoon.maafw.runner.FocusContentResolver
import com.aliothmoon.maafw.runner.FocusDispatcher
import com.aliothmoon.maafw.runner.GameFpsHook
import com.aliothmoon.maafw.runner.GameFpsWatcher
import com.aliothmoon.maafw.runner.RemoteGameFpsReader
import com.aliothmoon.maafw.telemetry.TelemetryController
import com.aliothmoon.maafw.telemetry.TelemetryHook
import com.aliothmoon.maafw.runner.ForegroundModePrecheck
import com.aliothmoon.maafw.runner.PrivilegedAccessPrecheck
import com.aliothmoon.maafw.runner.PrivilegedConnectHook
import com.aliothmoon.maafw.runner.OverlayClearHook
import com.aliothmoon.maafw.runner.KeepAliveHook
import com.aliothmoon.maafw.runner.MaaFrameworkRunnerPort
import com.aliothmoon.maafw.runner.NotificationHook
import com.aliothmoon.maafw.runner.PreviewPort
import com.aliothmoon.maafw.runner.PrivilegedFocusContentResolver
import com.aliothmoon.maafw.runner.RemotePreviewPort
import com.aliothmoon.maafw.runner.RunDurationLimitHook
import com.aliothmoon.maafw.runner.RunKeepAlive
import com.aliothmoon.maafw.runner.RunLauncher
import com.aliothmoon.maafw.runner.RunJournal
import com.aliothmoon.maafw.runner.RunLogRecorder
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.RunScreenSaver
import com.aliothmoon.maafw.runner.ScreenSaverShow
import com.aliothmoon.maafw.runner.RunSessionLogStore
import com.aliothmoon.maafw.runner.ScreenPowerOffHook
import com.aliothmoon.maafw.runner.ScreenSaverHook
import com.aliothmoon.maafw.runner.SessionLogHook
import com.aliothmoon.maafw.runner.SystemDisplayHazardProbe
import com.aliothmoon.maafw.schedule.UnlockGestureStore
import com.aliothmoon.maafw.privileged.PermissionGateway
import com.aliothmoon.maafw.runner.WakeUnlockHook
import com.aliothmoon.maafw.runner.WatchdogNoticeHook
import com.aliothmoon.maafw.service.AccessibilityTextInputSink
import com.aliothmoon.maafw.service.ForegroundRunKeepAlive
import com.aliothmoon.maafw.settings.AppSettingsManager
import com.aliothmoon.maafw.util.LauncherApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
            resolutionPreset = get<AppSettingsManager>().resolutionPreset::value,
            forceRestartApp = get<AppSettingsManager>().forceRestartApp::value,
            debugMode = get<AppSettingsManager>().debugMode::value,
            saveOnError = get<AppSettingsManager>().saveOnError::value,
            scope = get(named<AppCoroutineScope>()),
            servicePort = get(),
            textInputSink = { get<AccessibilityTextInputSink>() },
        )
    }

    single {
        AccessibilityTextInputSink(
            journal = get(),
            activeExecutionId = { get<RunnerPort>().state.value.activeExecution?.executionId },
        )
    }

    single<FocusContentResolver> {
        PrivilegedFocusContentResolver(
            source = get(),
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

    single {
        val configurationStore = get<UserConfigurationStore>()
        TelemetryController(
            context = androidContext(),
            projectRepository = get(),
            settings = get(),
            runnerPort = get(),
            servicePort = get(),
            scope = get(named<AppCoroutineScope>()),
            secrets = { configurationStore.data.first().passwordPlaintexts() },
        )
    }

    single { RunSessionLogStore() }

    single {
        RunLogRecorder(
            focusDispatcher = get(),
            store = get(),
            renderText = get<LocalizedTextRenderer>()::render,
            includeDetails = get<AppSettingsManager>().debugMode::value,
            scope = get(named<AppCoroutineScope>()),
        )
    }
    single<RunJournal> { get<RunLogRecorder>() }

    single {
        GameFpsWatcher(
            reader = RemoteGameFpsReader(get()),
            journal = get(),
            scope = get(named<AppCoroutineScope>()),
        )
    }

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
            override suspend fun show(): ScreenSaverShow = manager.show()
            override suspend fun hide() = manager.hide()
        }
    }

    single<DisplayHazardProbe> { SystemDisplayHazardProbe(androidContext(), get()) }

    single {
        val keyguardLocked = {
            androidContext().getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        }
        val blacklist = AppBlacklist(
            servicePort = get(),
            blacklist = get<AppSettingsManager>().scheduleAppBlacklist::value,
            keyguardLocked = keyguardLocked,
            appLabel = { LauncherApps.label(androidContext(), it) ?: it },
        )
        RunLauncher(
            projectRepository = get(),
            configurationStore = get(),
            runnerPort = get(),
            prechecks = listOf(
                PrivilegedAccessPrecheck(get(), get()),
                AppBlacklistPrecheck(blacklist, get()),
                ForegroundModePrecheck,
                BackgroundModePrecheck(),
                DisplayHazardPrecheck(get()),
            ),
            hooks = listOf(
                CollapseAfterRunHook(
                    servicePort = get(),
                    // 调用时才取：此刻 RunLauncher 还在构造
                    runQueued = { get<RunLauncher>().hasWaiters },
                    closeUi = {
                        withContext(Dispatchers.Main) {
                            androidContext().getSystemService(ActivityManager::class.java)
                                ?.appTasks?.forEach { it.finishAndRemoveTask() }
                        }
                    },
                ),
                SessionLogHook(get()),
                TelemetryHook(get()),
                NotificationHook(get()),
                PrivilegedConnectHook(get()),
                IdleProbeHook(get(), keyguardLocked),
                AutoSleepHook(get()),
                AppBlacklistHook(blacklist, afterCountdown = false),
                WakeUnlockHook(
                    servicePort = get(),
                    settings = get<AppSettingsManager>(),
                    gestures = get<UnlockGestureStore>(),
                    keyguardLocked = keyguardLocked,
                    deviceSecure = {
                        androidContext().getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
                    },
                ),
                ScreenSaverHook(get<AppSettingsManager>(), get()),
                CloseTargetAppHook(get(), get<AppSettingsManager>()),
                CompletionScreenshotHook(get(), get()),
                CountdownHook,
                AppBlacklistHook(blacklist, afterCountdown = true),
                // 调用时才取：OverlayController 经 SessionViewModel 依赖 RunLauncher，构造期取会成环
                OverlayClearHook { get<OverlayController>().awaitPanelCleared() },
                KeepAliveHook(get()),
                ScreenPowerOffHook(get()),
                GameFpsHook(get()),
                DisplayHazardNoticeHook(get(), get()),
                WatchdogNoticeHook(
                    watchdogState = get<PermissionGateway>().watchdogState,
                    servicePort = get(),
                    journal = get(),
                    scope = get(named<AppCoroutineScope>()),
                    exitRecorder = get(),
                    notifications = get(),
                ),
                RunDurationLimitHook(
                    settings = get<AppSettingsManager>(),
                    runnerPort = get(),
                    scope = get(named<AppCoroutineScope>()),
                ),
            ),
            runMode = get<AppSettingsManager>().runMode::value,
            scope = get(named<AppCoroutineScope>()),
            journal = get(),
            renderText = get<LocalizedTextRenderer>()::render,
        )
    }
}
