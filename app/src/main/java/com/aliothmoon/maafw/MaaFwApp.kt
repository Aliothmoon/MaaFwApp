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
import com.aliothmoon.maafw.project.AssetProjectSource
import com.aliothmoon.maafw.project.DefaultProjectRepository
import com.aliothmoon.maafw.project.ProjectLoader
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectSource
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.StubRunnerPort
import com.aliothmoon.maafw.session.SessionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import timber.log.Timber

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
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    single<ProjectSource> { AssetProjectSource(androidContext(), root = "sample") }
    single { ProjectLoader(get()) }
    single<ProjectRepository> { DefaultProjectRepository(get(), Dispatchers.IO) }

    single<DataStore<UserConfiguration>> {
        DataStoreFactory.create(
            serializer = UserConfigurationSerializer,
            // 信封级损坏数据：重置为未初始化，重走首次初始化（docs/persistence-diagnostics.md §9）
            corruptionHandler = ReplaceFileCorruptionHandler { UserConfiguration() },
            produceFile = { androidContext().dataStoreFile("user_configuration.json") },
        )
    }
    single<UserConfigurationStore> { DataStoreUserConfigurationStore(get()) }

    // UI 阶段绑定 StubRunnerPort；接入 JNI 后替换为 MaaFrameworkRunnerPort
    single<RunnerPort> { StubRunnerPort(scope = get()) }

    viewModel { SessionViewModel(get(), get(), get()) }
}
