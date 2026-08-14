package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.project.AssetPiPackage
import com.aliothmoon.maafw.project.DefaultProjectRepository
import com.aliothmoon.maafw.project.InstalledProjectSource
import com.aliothmoon.maafw.project.PiInstallCoordinator
import com.aliothmoon.maafw.project.PiInstaller
import com.aliothmoon.maafw.project.ProjectLoader
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectSource
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val projectModule = module {
    single {
        PiInstaller(
            pkg = AssetPiPackage(androidContext()),
            versionCode = BuildConfig.VERSION_CODE,
        )
    }
    single { PiInstallCoordinator(get()) }
    single<ProjectSource> { InstalledProjectSource(get()) }
    single { ProjectLoader(get()) }
    single<ProjectRepository> { DefaultProjectRepository(get()) }
}