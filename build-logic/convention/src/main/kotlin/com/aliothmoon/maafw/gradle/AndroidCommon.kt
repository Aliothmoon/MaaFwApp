package com.aliothmoon.maafw.gradle

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/** One SDK / JVM baseline for the whole repo: changing it here moves every module */
internal const val COMPILE_SDK = 37
internal const val TARGET_SDK = 36
internal const val MIN_SDK = 28

internal val JAVA_VERSION = JavaVersion.VERSION_17
internal val JVM_TARGET = JvmTarget.JVM_17

/**
 * AGP 9's CommonExtension only exposes getters, no lambda-shaped DSL methods, so assign properties here
 * The kotlin extension comes from AGP 9's built-in Kotlin support and is still KGP's type
 */
internal fun Project.configureAndroidCommon(extension: CommonExtension) {
    extension.compileSdk = COMPILE_SDK
    extension.defaultConfig.minSdk = MIN_SDK
    extension.compileOptions.sourceCompatibility = JAVA_VERSION
    extension.compileOptions.targetCompatibility = JAVA_VERSION

    // Without this an android.os.Trace section anywhere under test blows up with "not mocked".
    // The alternative is stubbing the platform per test, which buys nothing: the tests that care
    // about platform behaviour are instrumented ones anyway
    extension.testOptions.unitTests.isReturnDefaultValues = true

    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JVM_TARGET)
        }
    }
}
