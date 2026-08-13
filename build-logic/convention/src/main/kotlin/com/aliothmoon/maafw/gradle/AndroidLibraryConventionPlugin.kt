package com.aliothmoon.maafw.gradle

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // AGP 9 already puts the library plugin on the classpath, no version needed here
            pluginManager.apply("com.android.library")
            configureAndroidCommon(extensions.getByType<LibraryExtension>())
        }
    }
}
