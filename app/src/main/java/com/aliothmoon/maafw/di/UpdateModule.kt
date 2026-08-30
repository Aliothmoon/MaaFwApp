package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.update.GitHubReleasesApi
import com.aliothmoon.maafw.update.GitHubUpdateUrlResolver
import com.aliothmoon.maafw.update.GitHubUpdateVersionChecker
import com.aliothmoon.maafw.update.MirrorChyanLatestApi
import com.aliothmoon.maafw.update.MirrorChyanUpdateUrlResolver
import com.aliothmoon.maafw.update.MirrorChyanUpdateVersionChecker
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
    single { MirrorChyanUpdateVersionChecker(get()) }
    single { GitHubUpdateVersionChecker(get()) }
    single { MirrorChyanUpdateUrlResolver(get()) }
    single { GitHubUpdateUrlResolver(get()) }
    single {
        UpdateService(
            checkers = listOf(
                get<MirrorChyanUpdateVersionChecker>(),
                get<GitHubUpdateVersionChecker>(),
            ),
            resolvers = listOf(
                get<MirrorChyanUpdateUrlResolver>(),
                get<GitHubUpdateUrlResolver>(),
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
