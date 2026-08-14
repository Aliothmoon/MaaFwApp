package com.aliothmoon.maafw.gradle

import com.android.build.api.dsl.TestExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

class AndroidBenchmarkConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.test")

            val android = extensions.getByType<TestExtension>()
            configureAndroidCommon(android)

            android.defaultConfig.minSdk = BENCHMARK_MIN_SDK
            android.defaultConfig.targetSdk = TARGET_SDK
            android.defaultConfig.testInstrumentationRunner =
                "androidx.test.runner.AndroidJUnitRunner"

            val targetAppId = maafwApplicationId() + BENCHMARK_APP_ID_SUFFIX
            android.defaultConfig.buildConfigField("String", "TARGET_PACKAGE", "\"$targetAppId\"")
            android.defaultConfig.manifestPlaceholders["targetAppId"] = targetAppId
            android.buildFeatures.buildConfig = true

            // No build type of its own: androidx.baselineprofile derives nonMinifiedRelease
            // and benchmarkRelease from the app's release, and one here would get multiplied
            // against those. Self-built types also do not inherit debug signing, which shows up
            // as INSTALL_PARSE_FAILED_NO_CERTIFICATES rather than anything about signing

            // Runs the test APK in its own process, which is what lets it cold-start the target
            android.experimentalProperties["android.experimental.self-instrumenting"] = true
        }
    }
}

/** Above the repo floor of 28: profileable on a non-debuggable build only exists from 29 */
private const val BENCHMARK_MIN_SDK = 29
