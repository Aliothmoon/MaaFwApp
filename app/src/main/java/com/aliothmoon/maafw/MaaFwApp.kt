package com.aliothmoon.maafw

import com.aliothmoon.maafw.i18n.AppLocales
import android.content.res.Configuration
import android.app.Application
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.di.AppCoroutineScope
import com.aliothmoon.maafw.di.coreModule
import com.aliothmoon.maafw.di.logModule
import com.aliothmoon.maafw.di.notificationModule
import com.aliothmoon.maafw.di.overlayModule
import com.aliothmoon.maafw.di.privilegedModule
import com.aliothmoon.maafw.di.projectModule
import com.aliothmoon.maafw.di.runnerModule
import com.aliothmoon.maafw.di.scheduleModule
import com.aliothmoon.maafw.di.updateModule
import com.aliothmoon.maafw.di.viewModelModule
import com.aliothmoon.maafw.log.AppLogWriter
import com.aliothmoon.maafw.log.CrashHandler
import com.aliothmoon.maafw.log.LogTreeHolder
import com.aliothmoon.maafw.log.ProcessForensics
import com.aliothmoon.maafw.overlay.OverlayController
import com.aliothmoon.maafw.overlay.screensaver.ScreenSaverOverlayManager
import com.aliothmoon.maafw.privileged.PermissionManager
import com.aliothmoon.maafw.privileged.RemoteServiceManager
import com.aliothmoon.maafw.runner.RunSessionLogStore
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager
import com.aliothmoon.maafw.schedule.ScheduleMissedReporter
import com.aliothmoon.maafw.schedule.ShizukuDownMonitor
import com.aliothmoon.maafw.schedule.resyncScheduleAlarms
import com.aliothmoon.maafw.settings.AppSettingsManager
import com.aliothmoon.maafw.telemetry.TelemetryController
import com.aliothmoon.maafw.ui.SessionMessagePresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.core.qualifier.named
import timber.log.Timber

class MaaFwApp : Application() {

    private val writer by inject<AppLogWriter>()
    private val settings by inject<AppSettingsManager>()

    override fun onCreate() {
        super.onCreate()
        // 重启中转等子进程不初始化
        if (getProcessName() != packageName) return
        AppPaths.init(this)
        AppLocales.init(this)
        CrashHandler().install()
        val app = this
        val koin = startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE)
            androidContext(app)
            modules(
                coreModule,
                projectModule,
                privilegedModule,
                runnerModule,
                logModule,
                notificationModule,
                overlayModule,
                scheduleModule,
                updateModule,
                viewModelModule,
            )
        }.koin
        writer.setup()
        LogTreeHolder(writer, settings.debugMode::value).setup()
        if (AppPaths.rootFallback) Timber.w("getExternalFilesDir returned null, falling back to %s", AppPaths.ROOT)
        // PermissionManager 在 postCreate 里才建，但它的授权观察器构造期就可能 bind()，
        // 连接器的 context 必须先行注入（backendProvider 依赖设置加载，只能在 postCreate 里给）
        RemoteServiceManager.initializeConnectors(this)
        val appScope = koin.get<CoroutineScope>(named<AppCoroutineScope>())
        appScope.launch {
            settings.loaded.first { it }
            withContext(Dispatchers.Main) { postCreate(koin) }
        }
        // 每次进程启动都重排一遍：不少 ROM 不给没开自启的 app 发开机广播，撤销精确闹钟权限
        // 也会杀进程、清闹钟。只靠 ScheduleBootReceiver，这些情况下定时会一直哑到下次覆盖安装
        appScope.launch {
            resyncScheduleAlarms(koin.get(), koin.get(), reason = "process start")
            // 开机广播、导入备份那几次重排判出来的漏触发也在这里取走；onStart 收上个进程没来得及补记的
            val reporter = koin.get<ScheduleMissedReporter>()
            koin.get<ScheduleAlarmManager>().missedQueued
                .onStart { emit(Unit) }
                .collect { reporter.reportMissed() }
        }
        // 只靠历史页的清理入口的话，不进那一页的用户会一直攒下去
        appScope.launch {
            runCatching { koin.get<RunSessionLogStore>().cleanup() }
                .onFailure { Timber.w(it, "run session log cleanup failed") }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 系统配置一变，Application 的 Resources 就被重置回系统语言
        AppLocales.syncApplicationResources(this)
    }

    fun postCreate(koin: Koin) {
        koin.get<PermissionManager>()
        val provider = koin.get<AppSettingsManager>().startupBackend::value
        RemoteServiceManager.initialize(this, provider)
        koin.get<OverlayController>().setup()
        koin.get<SessionMessagePresenter>().setup()
        koin.get<ScreenSaverOverlayManager>().setup()
        koin.get<TelemetryController>().setup()
        // 要在 RemoteServiceManager.initialize 之后：状态监听挂上了，Shizuku 掉线才看得见
        koin.get<ShizukuDownMonitor>().start(koin.get(named<AppCoroutineScope>()))
        // 同上：特权进程的死讯与重连都要看着
        koin.get<ProcessForensics>().start(koin.get(named<AppCoroutineScope>()))
    }
}
