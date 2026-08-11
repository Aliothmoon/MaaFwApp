package com.aliothmoon.maafw

import android.app.Application
import com.aliothmoon.maafw.constant.AppFiles
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.di.appModule
import com.aliothmoon.maafw.log.AppLogWriter
import com.aliothmoon.maafw.log.plantLogTrees
import com.aliothmoon.maafw.overlay.OverlayController
import com.aliothmoon.maafw.overlay.screensaver.ScreenSaverOverlayManager
import com.aliothmoon.maafw.privileged.PermissionManager
import com.aliothmoon.maafw.privileged.RemoteServiceManager
import com.aliothmoon.maafw.settings.AppSettingsManager
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import java.io.File

class MaaFwApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppPaths.init(this)
        // 先种树再启 Koin：Koin 自身与各 single 的构造日志也要落盘
        plantLogTrees(
            AppLogWriter(
                logDir = File(getExternalFilesDir(null) ?: filesDir, AppFiles.LOG_DIR),
            ),
        )
        val koin = startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE)
            androidContext(this@MaaFwApp)
            modules(appModule)
        }.koin
        // 先建 PermissionManager：它 init 里注册的那几个观察者要早于下面 initialize 触发的首次 refresh，
        // 否则「授权到手就绑定」会漏掉启动时已授权的那一次
        koin.get<PermissionManager>()
        // 特权进程的进程级 bootstrap 落在组合根，不塞进 PermissionManager 的构造：
        // backendProvider 要现读盘上的后端选择，只有这里同时拿得到 Context 与 AppSettingsManager
        RemoteServiceManager.initialize(this, koin.get<AppSettingsManager>().startupBackend::value)
        // 控制层与屏保都跟着 runMode 装卸，得在进程起来时就开始观察
        koin.get<OverlayController>().setup()
        koin.get<ScreenSaverOverlayManager>().setup()
    }
}
