package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.update.GitHubUpdateSourceChecker
import com.aliothmoon.maafw.update.MirrorChyanUpdateSourceChecker
import com.aliothmoon.maafw.update.OkHttpUpdateHttpGateway
import com.aliothmoon.maafw.update.UpdateCheckApi
import com.aliothmoon.maafw.update.UpdateCheckCoordinator
import org.koin.dsl.module

val updateModule = module {
    single { OkHttpUpdateHttpGateway() }
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
}
