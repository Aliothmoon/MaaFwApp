package com.aliothmoon.maafw

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
import com.aliothmoon.maafw.overlay.OverlayController
import com.aliothmoon.maafw.overlay.screensaver.ScreenSaverOverlayManager
import com.aliothmoon.maafw.ui.SessionMessagePresenter
import com.aliothmoon.maafw.privileged.PermissionManager
import com.aliothmoon.maafw.privileged.RemoteServiceManager
import com.aliothmoon.maafw.settings.AppSettingsManager
import com.aliothmoon.maafw.telemetry.TelemetryController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.core.qualifier.named

class MaaFwApp : Application() {

    private val writer by inject<AppLogWriter>()
    private val settings by inject<AppSettingsManager>()

    override fun onCreate() {
        super.onCreate()
        AppPaths.init(this)
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
        koin.get<SessionMessagePresenter>().setup()
        koin.get<ScreenSaverOverlayManager>().setup()
        koin.get<TelemetryController>().setup()
    }
}
