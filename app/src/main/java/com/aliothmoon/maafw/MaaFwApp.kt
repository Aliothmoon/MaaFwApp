package com.aliothmoon.maafw

import android.app.Application
import com.aliothmoon.maafw.constant.AppFiles
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.di.AppCoroutineScope
import com.aliothmoon.maafw.di.appModule
import com.aliothmoon.maafw.log.AppLogWriter
import com.aliothmoon.maafw.log.CrashHandler
import com.aliothmoon.maafw.log.LogTreeHolder
import com.aliothmoon.maafw.overlay.OverlayController
import com.aliothmoon.maafw.overlay.screensaver.ScreenSaverOverlayManager
import com.aliothmoon.maafw.privileged.PermissionManager
import com.aliothmoon.maafw.privileged.RemoteServiceManager
import com.aliothmoon.maafw.settings.AppSettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.core.qualifier.named
import java.io.File

class MaaFwApp : Application() {

    /**
     * 落盘那棵树的出口
     *
     * 挂在 Application 上而不是让 Koin 造：进程一起来就要能记（构造时先打一段环境信息），
     * 比 Koin 早；而同一个文件只能有一个写入者，Koin 再造一份就是两个流写同一个文件
     */
    lateinit var logWriter: AppLogWriter
        private set

    override fun onCreate() {
        super.onCreate()
        AppPaths.init(this)
        logWriter = AppLogWriter()
        CrashHandler(crashDir = { File(AppPaths.LOG_DIR, AppFiles.CRASH_DIR) }).install()
        val app = this
        val koin = startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE)
            androidContext(app)
            modules(appModule)
        }.koin
        val settings = koin.get<AppSettingsManager>()
        // 排在 Koin 之后才拿得到调试模式；这中间只隔一个 AppSettingsManager 的构造，不打日志
        LogTreeHolder(logWriter, settings.debugMode::value).setup()
        koin.get<CoroutineScope>(named<AppCoroutineScope>()).launch {
            settings.loaded.first { it }
            withContext(Dispatchers.Main) { postCreate(koin) }
        }
    }

    fun postCreate(koin: Koin) {
        koin.get<PermissionManager>()
        val provider = koin.get<AppSettingsManager>().startupBackend::value
        RemoteServiceManager.initialize(this, provider)
        koin.get<OverlayController>().setup()
        koin.get<ScreenSaverOverlayManager>().setup()
    }
}
