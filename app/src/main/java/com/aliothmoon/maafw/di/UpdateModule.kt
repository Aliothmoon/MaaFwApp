package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.update.GitHubReleasesApi
import com.aliothmoon.maafw.update.GitHubUpdateClient
import com.aliothmoon.maafw.update.MirrorChyanLatestApi
import com.aliothmoon.maafw.update.MirrorChyanUpdateClient
import com.aliothmoon.maafw.update.OkHttpUpdateDownloader
import com.aliothmoon.maafw.update.OkHttpUpdateHttpGateway
import com.aliothmoon.maafw.update.UpdateDownloadFiles
import com.aliothmoon.maafw.update.UpdateService
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val updateModule = module {
    single<OkHttpUpdateHttpGateway> { OkHttpUpdateHttpGateway() }
    single {
        MirrorChyanLatestApi(
            gateway = get(),
            userAgent = "MaaFwApp/${BuildConfig.VERSION_NAME} Android",
        )
    }
    single { GitHubReleasesApi(get()) }
    single { MirrorChyanUpdateClient(get()) }
    single { GitHubUpdateClient(get()) }
    single {
        UpdateService(
            clients = listOf(
                get<MirrorChyanUpdateClient>(),
                get<GitHubUpdateClient>(),
            ),
        )
    }

    single<OkHttpUpdateDownloader> {
        OkHttpUpdateDownloader(
            directory = UpdateDownloadFiles.directory(androidContext().cacheDir),
            userAgent = "MaaFwApp/${BuildConfig.VERSION_NAME} Android",
        )
    }
}
