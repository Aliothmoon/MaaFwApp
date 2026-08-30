package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.overlay.OverlayController
import com.aliothmoon.maafw.overlay.OverlayViewModelOwner
import com.aliothmoon.maafw.overlay.border.BorderOverlayManager
import com.aliothmoon.maafw.overlay.screensaver.ScreenSaverOverlayManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val overlayModule = module {
    single { BorderOverlayManager(androidContext()) }
    single { OverlayViewModelOwner() }
    single {
        OverlayController(
            context = androidContext() as android.app.Application,
            runnerPort = get(),
            appSettings = get(),
            borderOverlayManager = get(),
            viewModelOwner = get(),
            sessionViewModel = get(),
        )
    }

    single {
        ScreenSaverOverlayManager(
            context = androidContext(),
            runnerPort = get(),
            appSettings = get(),
        )
    }
}