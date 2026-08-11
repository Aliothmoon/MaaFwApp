package com.aliothmoon.maafw

import android.app.Application
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
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class MaaFwApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppPaths.init(this)
        // 先种树再启 Koin：Koin 自身与各 single 的构造日志也要落盘
        plantLogTrees(AppLogWriter())
        val app = this
        val koin = startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE)
            androidContext(app)
            modules(appModule)
        }
        postCreate(koin.koin)
    }

    fun postCreate(koin: Koin) {
        koin.get<PermissionManager>()
        val provider = koin.get<AppSettingsManager>().startupBackend::value
        RemoteServiceManager.initialize(this, provider)
        koin.get<OverlayController>().setup()
        koin.get<ScreenSaverOverlayManager>().setup()
    }

}
