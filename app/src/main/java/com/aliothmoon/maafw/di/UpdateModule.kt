package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.update.GitHubUpdateSourceChecker
import com.aliothmoon.maafw.update.MirrorChyanUpdateSourceChecker
import com.aliothmoon.maafw.update.OkHttpUpdateHttpGateway
import com.aliothmoon.maafw.update.OkHttpUpdateDownloader
import com.aliothmoon.maafw.update.SystemUpdateInstaller
import com.aliothmoon.maafw.update.UpdateCheckApi
import com.aliothmoon.maafw.update.UpdateCheckCoordinator
import com.aliothmoon.maafw.update.UpdateDownloadApi
import com.aliothmoon.maafw.update.UpdateDownloadFiles
import com.aliothmoon.maafw.update.UpdateHttpGateway
import com.aliothmoon.maafw.update.UpdateInstallApi
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val updateModule = module {
    single<UpdateHttpGateway> { OkHttpUpdateHttpGateway() }
    single {
        MirrorChyanUpdateSourceChecker(
            gateway = get(),
            userAgent = "MaaFwApp/${BuildConfig.VERSION_NAME} Android",
        )
    }
    single { GitHubUpdateSourceChecker(get()) }
    single<UpdateCheckApi> {
        UpdateCheckCoordinator(
            listOf(
                get<MirrorChyanUpdateSourceChecker>(),
                get<GitHubUpdateSourceChecker>(),
            ),
        )
    }

    single<UpdateDownloadApi> {
        OkHttpUpdateDownloader(
            directory = UpdateDownloadFiles.directory(androidContext().cacheDir),
            userAgent = "MaaFwApp/${BuildConfig.VERSION_NAME} Android",
        )
    }

    single<UpdateInstallApi> {
        val context = androidContext()
        SystemUpdateInstaller(
            context = context,
            downloadDirectory = UpdateDownloadFiles.directory(context.cacheDir),
        )
    }
}
