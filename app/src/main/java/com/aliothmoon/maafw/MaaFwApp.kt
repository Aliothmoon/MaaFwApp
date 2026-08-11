package com.aliothmoon.maafw

import android.app.Application
import com.aliothmoon.maafw.constant.AppFiles
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.di.AppCoroutineScope
import com.aliothmoon.maafw.di.appModule
import com.aliothmoon.maafw.log.AppLogWriter
import com.aliothmoon.maafw.log.CrashHandler
import com.aliothmoon.maafw.log.plantLogTrees
import com.aliothmoon.maafw.overlay.OverlayController
import com.aliothmoon.maafw.overlay.screensaver.ScreenSaverOverlayManager
import com.aliothmoon.maafw.privileged.LogcatServiceManager
import com.aliothmoon.maafw.privileged.PermissionManager
import com.aliothmoon.maafw.privileged.RemoteServiceManager
import com.aliothmoon.maafw.settings.AppSettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
     * 挂在 Application 上而不是让 Koin 造：种树必须早于 `startKoin`（Koin 自身与各 single
     * 的构造日志也要落盘），而同一个文件只能有一个写入者，Koin 再造一份就是两个流写同一个文件
     */
    lateinit var logWriter: AppLogWriter
        private set

    override fun onCreate() {
        super.onCreate()
        AppPaths.init(this)
        logWriter = AppLogWriter()
        plantLogTrees(logWriter)
        CrashHandler(crashDir = { File(AppPaths.logDir, AppFiles.CRASH_DIR) }).install()
        val app = this
        val koin = startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE)
            androidContext(app)
            modules(appModule)
        }.koin
        val settings = koin.get<AppSettingsManager>()
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
        stopLogcatCaptureWhenDebugOff(koin.get(), koin.get(named<AppCoroutineScope>()))
    }

    /**
     * 关掉调试模式就停抓 logcat
     *
     * 抓取是在 `MaaFrameworkRunnerPort` 里按 `debugMode()` 起的，但没有对称的停止点——
     * 不 unbind 的话 `:logcat` / `:root_logcat` 会一直跟着 pid 抓下去，用户以为关了其实没关
     *
     * 关闭这一档有意**不重启** app：`setup()` 每轮现读 debugMode，下一轮自然就是 false，
     * 而重启会把用户正在看的页面掀掉。开启那一档仍要重启（见 `SessionViewModel`）
     */
    private fun stopLogcatCaptureWhenDebugOff(settings: AppSettingsManager, scope: CoroutineScope) {
        settings.debugMode
            .onEach { enabled -> if (!enabled) LogcatServiceManager.unbind() }
            .launchIn(scope)
    }
}
