package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.update.OkHttpUpdateHttpGateway
import com.aliothmoon.maafw.update.UpdateCheckApi
import com.aliothmoon.maafw.update.UpdateHttpGateway
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.dsl.koinApplication

class UpdateModuleTest {
    @Test
    fun updateCheckApiResolvesThroughUpdateHttpGatewayBinding() {
        val koin = koinApplication {
            modules(updateModule)
        }.koin

        try {
            val gateway = koin.get<UpdateHttpGateway>()

            assertTrue(gateway is OkHttpUpdateHttpGateway)
            koin.get<UpdateCheckApi>()
        } finally {
            koin.close()
        }
    }
}
