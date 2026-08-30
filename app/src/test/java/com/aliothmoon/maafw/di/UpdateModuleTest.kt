package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.update.OkHttpUpdateHttpGateway
import com.aliothmoon.maafw.update.UpdateService
import org.junit.Test
import org.koin.dsl.koinApplication

class UpdateModuleTest {
    @Test
    fun updateDependenciesResolve() {
        val koin = koinApplication {
            modules(updateModule)
        }.koin

        try {
            koin.get<OkHttpUpdateHttpGateway>()
            koin.get<UpdateService>()
        } finally {
            koin.close()
        }
    }
}
