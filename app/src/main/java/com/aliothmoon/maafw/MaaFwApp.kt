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
import com.aliothmoon.maafw.project.AssetPiPackage
import com.aliothmoon.maafw.project.DefaultProjectRepository
import com.aliothmoon.maafw.project.InstalledProjectSource
import com.aliothmoon.maafw.project.PI_ASSET_ROOT
import com.aliothmoon.maafw.project.PiInstaller
import com.aliothmoon.maafw.project.ProjectLoader
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectSource
import com.aliothmoon.maafw.project.readPiFingerprint
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.StubRunnerPort
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

class MaaFwApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE)
            androidContext(this@MaaFwApp)
            modules(appModule)
        }
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

    // 接入 JNI 后替换为 MaaFrameworkRunnerPort
    single<RunnerPort> { StubRunnerPort(scope = get(named<AppCoroutineScope>())) }

    viewModel {
        SessionViewModel(
            projectRepository = get(),
            configurationStore = get(),
            runnerPort = get(),
            localeController = AppLocales,
        )
    }
}
