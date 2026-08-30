package com.aliothmoon.maafw.di

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.SystemApkInstaller
import com.aliothmoon.maafw.config.DataStoreUserConfigurationStore
import com.aliothmoon.maafw.config.UserConfigurationSerializer
import com.aliothmoon.maafw.config.UserConfigurationStore
import com.aliothmoon.maafw.constant.DataStoreFile
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.i18n.LocalizedTextRenderer
import com.aliothmoon.maafw.settings.AppSettingsGateway
import com.aliothmoon.maafw.settings.AppSettingsManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import timber.log.Timber

/** 进程级 scope 限定符，避免与其它 CoroutineScope 绑定冲突 */
object AppCoroutineScope

val coreModule = module {
    single { SystemApkInstaller(androidContext()) }

    single(named<AppCoroutineScope>()) {
        val handler = CoroutineExceptionHandler { _, throwable ->
            Timber.e(throwable, "AppCoroutineScope uncaught")
        }
        CoroutineScope(SupervisorJob() + MaaDispatchers.Default + handler)
    }

    single<DataStore<UserConfiguration>> {
        DataStoreFactory.create(
            serializer = UserConfigurationSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { UserConfiguration() },
            produceFile = {
                androidContext().dataStoreFile(DataStoreFile.USER_CONFIGRATION)
            },
        )
    }
    single<UserConfigurationStore> { DataStoreUserConfigurationStore(get()) }

    single { AppSettingsManager(androidContext()) }
    single<AppSettingsGateway> { get<AppSettingsManager>() }

    single { LocalizedTextRenderer(androidContext()) }
}