package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.update.OkHttpUpdateDownloader
import com.aliothmoon.maafw.update.UpdateService
import com.aliothmoon.maafw.util.HttpClientHelper
import org.junit.Test
import org.koin.dsl.koinApplication

class UpdateModuleTest {
    @Test
    fun updateDependenciesResolve() {
        // 共享 OkHttpClient/HttpClientHelper 在 coreModule；Koin 定义惰性求值，
        // 不解析需要 androidContext 的定义就不会触碰 Android
        val koin = koinApplication {
            modules(coreModule, updateModule)
        }.koin

        try {
            koin.get<HttpClientHelper>()
            koin.get<UpdateService>()
            koin.get<OkHttpUpdateDownloader>()
        } finally {
            koin.close()
        }
    }
}
