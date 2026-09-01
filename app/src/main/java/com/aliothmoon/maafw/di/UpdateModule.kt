package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.update.GitHubReleasesApi
import com.aliothmoon.maafw.update.GitHubUpdateClient
import com.aliothmoon.maafw.update.MirrorChyanLatestApi
import com.aliothmoon.maafw.update.MirrorChyanUpdateClient
import com.aliothmoon.maafw.update.OkHttpUpdateDownloader
import com.aliothmoon.maafw.update.UpdateService
import org.koin.dsl.module

val updateModule = module {
    single { MirrorChyanLatestApi(get()) }
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
    single { OkHttpUpdateDownloader(get()) }
}
