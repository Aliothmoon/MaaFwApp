package com.aliothmoon.maafw.di

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.aliothmoon.maafw.config.DataStoreUserConfigurationStore
import com.aliothmoon.maafw.config.UserConfigurationSerializer
import com.aliothmoon.maafw.config.UserConfigurationStore
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.i18n.AppLocales
import com.aliothmoon.maafw.overlay.OverlayController
import com.aliothmoon.maafw.overlay.OverlayViewModelOwner
import com.aliothmoon.maafw.overlay.border.BorderOverlayManager
import com.aliothmoon.maafw.overlay.screensaver.ScreenSaverOverlayManager
import com.aliothmoon.maafw.privileged.PermissionGateway
import com.aliothmoon.maafw.privileged.PermissionManager
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.privileged.RemoteAccessCoordinator
import com.aliothmoon.maafw.privileged.RemoteAccessPort
import com.aliothmoon.maafw.privileged.RemoteServiceManager
import com.aliothmoon.maafw.project.AssetPiPackage
import com.aliothmoon.maafw.project.DefaultProjectRepository
import com.aliothmoon.maafw.project.InstalledProjectSource
import com.aliothmoon.maafw.project.PI_ASSET_ROOT
import com.aliothmoon.maafw.project.PiInstaller
import com.aliothmoon.maafw.project.ProjectLoader
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectSource
import com.aliothmoon.maafw.project.readPiFingerprint
import com.aliothmoon.maafw.runner.AutoSleepHook
import com.aliothmoon.maafw.runner.CloseTargetAppHook
import com.aliothmoon.maafw.runner.CountdownHook
import com.aliothmoon.maafw.runner.FocusContentResolver
import com.aliothmoon.maafw.runner.FocusDispatcher
import com.aliothmoon.maafw.runner.ForegroundModePrecheck
import com.aliothmoon.maafw.runner.KeepAliveHook
import com.aliothmoon.maafw.runner.MaaFrameworkRunnerPort
import com.aliothmoon.maafw.runner.PreviewPort
import com.aliothmoon.maafw.runner.PrivilegedFocusContentResolver
import com.aliothmoon.maafw.runner.RemotePreviewPort
import com.aliothmoon.maafw.runner.RunKeepAlive
import com.aliothmoon.maafw.runner.RunLauncher
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.RunScreenSaver
import com.aliothmoon.maafw.runner.ScreenSaverHook
import com.aliothmoon.maafw.runner.WakeUnlockHook
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager
import com.aliothmoon.maafw.schedule.ScheduleStrategyStore
import com.aliothmoon.maafw.schedule.ScheduleTriggerLog
import com.aliothmoon.maafw.schedule.ScheduleViewModel
import com.aliothmoon.maafw.service.ForegroundRunKeepAlive
import com.aliothmoon.maafw.session.SessionViewModel
import com.aliothmoon.maafw.settings.AppSettingsGateway
import com.aliothmoon.maafw.settings.AppSettingsManager
import com.aliothmoon.maafw.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import timber.log.Timber

/** 进程级 scope 限定符，避免与其它 CoroutineScope 绑定冲突 */
object AppCoroutineScope

val appModule = module {
    single(named<AppCoroutineScope>()) {
        val handler = CoroutineExceptionHandler { _, throwable ->
            Timber.e(throwable, "AppCoroutineScope uncaught")
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
    }

    single {
        val context = androidContext()
        PiInstaller(
            pkg = AssetPiPackage(context, root = PI_ASSET_ROOT),
            fingerprint = readPiFingerprint(context),
        )
    }
    single<ProjectSource> { InstalledProjectSource(get()) }
    single { ProjectLoader(get(), localeProvider = AppLocales::currentProjectTag) }
    single<ProjectRepository> { DefaultProjectRepository(get()) }

    single<DataStore<UserConfiguration>> {
        DataStoreFactory.create(
            serializer = UserConfigurationSerializer,
            // 信封损坏 → 未初始化，重走首次初始化（docs/persistence-diagnostics.md §9）
            corruptionHandler = ReplaceFileCorruptionHandler { UserConfiguration() },
            produceFile = { androidContext().dataStoreFile("user_configuration.json") },
        )
    }
    single<UserConfigurationStore> { DataStoreUserConfigurationStore(get()) }

    // 两个提权面的进程级单例；object 保留做实现，装配在这里，调用点只认接口
    single<PrivilegedServicePort> { RemoteServiceManager }
    single<RemoteAccessPort> { RemoteAccessCoordinator }

    // StubRunnerPort 保留给测试与 Preview，不再进 DI
    single<RunnerPort> {
        val context = androidContext()
        MaaFrameworkRunnerPort(
            installer = get(),
            // 两条路径都取自 app 的 applicationInfo：特权进程只有 FakeContext，自己解析不如这里直给
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

    // 进程级：notification 那一档要在 Activity 销毁后仍然响
    single {
        FocusDispatcher(
            projectRepository = get(),
            resolver = get(),
            runnerPort = get(),
            scope = get(named<AppCoroutineScope>()),
        )
    }

    single<PreviewPort> {
        RemotePreviewPort(
            scope = get(named<AppCoroutineScope>()),
            servicePort = get(),
        )
    }

    // 发起一轮执行是进程级用例：定时触发落在 Service 里，拿不到 Activity 作用域的 VM
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
            // 两份都写成显式有序列表而不是 getAll()：顺序即语义，得在一处看得见
            prechecks = listOf(ForegroundModePrecheck),
            // engage 顺序由各自的 order 定，这里的书写顺序不算数（见 EnvironmentHooks.kt）
            hooks = listOf(
                AutoSleepHook(get()),
                WakeUnlockHook(get(), get<AppSettingsManager>()),
                ScreenSaverHook(get<AppSettingsManager>(), get()),
                CloseTargetAppHook(get()),
                CountdownHook,
                KeepAliveHook(get()),
            ),
            runMode = get<AppSettingsManager>().runMode::value,
            scope = get(named<AppCoroutineScope>()),
        )
    }

    // app 设置与运行配置分开存：前者不该被 UserConfiguration 的 schema 重置波及
    single { AppSettingsManager(androidContext()) }
    single<AppSettingsGateway> { get<AppSettingsManager>() }

    // 前台模式的控制层；后台虚拟屏模式下 setup 里会把它整层卸掉
    single { BorderOverlayManager(androidContext()) }
    single { OverlayViewModelOwner() }
    single {
        OverlayController(
            context = androidContext() as android.app.Application,
            runnerPort = get(),
            appSettings = get(),
            borderOverlayManager = get(),
            viewModelOwner = get(),
        )
    }

    // 后台模式的运行期屏保；与控制层互为反面，只在后台模式下起作用
    single {
        ScreenSaverOverlayManager(
            context = androidContext(),
            runnerPort = get(),
            appSettings = get(),
        )
    }

    // 定时：两个 receiver 与 FGS 都从 GlobalContext 取，必须是 single
    single { ScheduleStrategyStore(androidContext()) }
    single { ScheduleAlarmManager(androidContext()) }
    single {
        ScheduleTriggerLog()
    }
    single { PermissionManager(androidContext(), get(), get(), get()) }
    single<PermissionGateway> { get<PermissionManager>() }

    viewModel {
        SessionViewModel(
            projectRepository = get(),
            configurationStore = get(),
            runnerPort = get(),
            runLauncher = get(),
            previewPort = get(),
            permissionGateway = get(),
            appSettings = get(),
            localeController = AppLocales,
            focusDispatcher = get(),
        )
    }

    viewModel {
        ScheduleViewModel(
            store = get(),
            alarms = get(),
            triggerLog = get(),
            configurationStore = get(),
        )
    }

    viewModel {
        SettingsViewModel(permissionGateway = get())
    }
}
