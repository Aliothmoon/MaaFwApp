package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.privileged.DisplaySizeController
import com.aliothmoon.maafw.privileged.DisplaySizeGateway
import com.aliothmoon.maafw.privileged.PermissionGateway
import com.aliothmoon.maafw.privileged.PermissionManager
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.privileged.RemoteAccessCoordinator
import com.aliothmoon.maafw.privileged.RemoteAccessPort
import com.aliothmoon.maafw.privileged.RemoteServiceManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val privilegedModule = module {
    single<PrivilegedServicePort> { RemoteServiceManager }
    single<RemoteAccessPort> { RemoteAccessCoordinator }

    single { PermissionManager(androidContext(), get(), get(), get()) }
    single<PermissionGateway> { get<PermissionManager>() }
    single<DisplaySizeGateway> { DisplaySizeController(androidContext(), get()) }
}