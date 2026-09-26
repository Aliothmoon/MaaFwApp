package com.aliothmoon.maafw.gradle

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

/**
 * Standalone apps the main app drives on a device during end-to-end checks
 *
 * Shares the SDK / JVM baseline but none of the application plugin's identity work: no build
 * profile, no git-derived version, no release signing. The applicationId is fixed by the module,
 * so a device keeps exactly one copy no matter which PI profile the main app was built from
 */
class AndroidTestAppConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")

            val android = extensions.getByType<ApplicationExtension>()
            configureAndroidCommon(android)
            android.defaultConfig.targetSdk = TARGET_SDK
            android.defaultConfig.versionCode = 1
            android.defaultConfig.versionName = "1.0"
        }
    }
}
