package com.aliothmoon.maafw

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.aliothmoon.maafw.config.DataStoreUserConfigurationStore
import com.aliothmoon.maafw.config.UserConfigurationSerializer
import com.aliothmoon.maafw.config.UserConfigurationStore
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.i18n.AppLocales
import com.aliothmoon.maafw.log.AppLogWriter
import com.aliothmoon.maafw.log.plantLogTrees
import com.aliothmoon.maafw.overlay.OverlayController
import com.aliothmoon.maafw.overlay.OverlayViewModelOwner
import com.aliothmoon.maafw.overlay.border.BorderOverlayManager
import com.aliothmoon.maafw.project.AssetPiPackage
import com.aliothmoon.maafw.project.DefaultProjectRepository
import com.aliothmoon.maafw.project.InstalledProjectSource
import com.aliothmoon.maafw.project.PI_ASSET_ROOT
import com.aliothmoon.maafw.project.PiInstaller
import com.aliothmoon.maafw.project.ProjectLoader
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectSource
import com.aliothmoon.maafw.privileged.PermissionGateway
import com.aliothmoon.maafw.privileged.PermissionManager
import com.aliothmoon.maafw.privileged.RemoteServiceManager
import com.aliothmoon.maafw.settings.AppSettingsGateway
import com.aliothmoon.maafw.settings.AppSettingsManager
import com.aliothmoon.maafw.project.readPiFingerprint
import com.aliothmoon.maafw.runner.MaaFrameworkRunnerPort
import com.aliothmoon.maafw.runner.PreviewPort
import com.aliothmoon.maafw.runner.RemotePreviewPort
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager
import com.aliothmoon.maafw.schedule.ScheduleStrategyStore
import com.aliothmoon.maafw.schedule.ScheduleTriggerLog
import com.aliothmoon.maafw.schedule.ScheduleViewModel
import com.aliothmoon.maafw.session.SessionViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import timber.log.Timber

/** 进程级 scope 限定符，避免与其它 CoroutineScope 绑定冲突 */
object AppCoroutineScope

/** 外部私有目录下的 MaaFramework 落盘目录：maa.log 与 Screencap 动作的产物都在这 */
private const val MAA_LOG_DIR_NAME = "log"

class MaaFwApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 先种树再启 Koin：Koin 自身与各 single 的构造日志也要落盘
        plantLogTrees(
            AppLogWriter(
                logDir = { File(getExternalFilesDir(null) ?: filesDir, MAA_LOG_DIR_NAME) },
            ),
        )
        val koin = startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE)
            androidContext(this@MaaFwApp)
            modules(appModule)
        }.koin
        // PermissionManager 的构造里带 RemoteServiceManager.initialize，
        // 它要先读到盘上的后端选择，所以在这里显式建出来而不是等首次注入
        koin.get<PermissionManager>()
        // 控制层跟着 runMode 装卸，得在进程起来时就开始观察
        koin.get<OverlayController>().setup()
    }
}

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
            // 落外部私有目录而非 filesDir：特权进程是 shell 身份，进不去 0700 的 app 私有目录
            baseDir = {
                checkNotNull(context.getExternalFilesDir(null)) {
                    "外部私有目录不可用（外部存储未挂载），PI 无法解包"
                }
            },
            fingerprint = readPiFingerprint(context),
        )
    }
    single<ProjectSource> { InstalledProjectSource(get()) }
    single { ProjectLoader(get(), localeProvider = AppLocales::currentProjectTag) }
    single<ProjectRepository> { DefaultProjectRepository(get(), Dispatchers.IO) }

    single<DataStore<UserConfiguration>> {
        DataStoreFactory.create(
            serializer = UserConfigurationSerializer,
            // 信封损坏 → 未初始化，重走首次初始化（docs/persistence-diagnostics.md §9）
            corruptionHandler = ReplaceFileCorruptionHandler { UserConfiguration() },
            produceFile = { androidContext().dataStoreFile("user_configuration.json") },
        )
    }
    single<UserConfigurationStore> { DataStoreUserConfigurationStore(get()) }

    // StubRunnerPort 保留给测试与 Preview，不再进 DI
    single<RunnerPort> {
        val context = androidContext()
        MaaFrameworkRunnerPort(
            installer = get(),
            // 与 PI 同在外部私有目录：特权进程写得进，adb pull 也拿得到
            logDir = {
                File(
                    checkNotNull(context.getExternalFilesDir(null)) {
                        "外部私有目录不可用（外部存储未挂载）"
                    },
                    MAA_LOG_DIR_NAME,
                )
            },
            // 两条路径都取自 app 的 applicationInfo：特权进程只有 FakeContext，自己解析不如这里直给
            apkPath = context.applicationInfo.sourceDir,
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
            runMode = get<AppSettingsManager>().runMode::value,
            scope = get(named<AppCoroutineScope>()),
            ioDispatcher = Dispatchers.IO,
        )
    }

    single<PreviewPort> {
        RemotePreviewPort(
            scope = get(named<AppCoroutineScope>()),
            serviceManager = RemoteServiceManager,
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

    // 定时：两个 receiver 与 FGS 都从 GlobalContext 取，必须是 single
    single { ScheduleStrategyStore(androidContext()) }
    single { ScheduleAlarmManager(androidContext()) }
    single {
        val context = androidContext()
        ScheduleTriggerLog(
            logDir = {
                File(
                    checkNotNull(context.getExternalFilesDir(null)) {
                        "外部私有目录不可用（外部存储未挂载）"
                    },
                    MAA_LOG_DIR_NAME,
                )
            },
        )
    }
    single { PermissionManager(androidContext(), get()) }
    single<PermissionGateway> { get<PermissionManager>() }

    viewModel {
        SessionViewModel(
            projectRepository = get(),
            configurationStore = get(),
            runnerPort = get(),
            previewPort = get(),
            permissionGateway = get(),
            appSettings = get(),
            localeController = AppLocales,
        )
    }

    viewModel {
        ScheduleViewModel(
            store = get(),
            alarms = get(),
            triggerLog = get(),
        )
    }
}
