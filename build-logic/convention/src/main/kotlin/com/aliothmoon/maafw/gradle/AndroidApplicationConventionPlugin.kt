package com.aliothmoon.maafw.gradle

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

/** ABIs that ship; a debug build can narrow to one via build.debugAbi to save build time */
private val SHIPPED_ABIS = listOf("arm64-v8a", "x86_64")

/**
 * The whole shell around the app module: SDK baseline, git version, packaging, signing, build types
 * The module script keeps only what this app is: namespace, applicationId, native, buildFeatures
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")

            val android = extensions.getByType<ApplicationExtension>()
            configureAndroidCommon(android)

            android.defaultConfig {
                targetSdk = TARGET_SDK
                versionCode = gitVersionCode()
                versionName = gitVersionName()
                println("Build version: versionCode=$versionCode, versionName=$versionName")

                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }

            android.packaging {
                jniLibs {
                    useLegacyPackaging = true
                }
                resources {
                    pickFirsts += setOf(
                        "META-INF/LICENSE.md",
                        "META-INF/NOTICE.md",
                    )
                }
            }

            // Without a keystore the release stays unsigned, so a local build never fails
            // just for missing signing material
            val keystorePath = signingSetting("KEYSTORE_PATH", "KEYSTORE_PATH")
            val releaseSigning = android.signingConfigs.create("release").apply {
                if (keystorePath.isNotEmpty()) {
                    storeFile = file(keystorePath)
                    storePassword = signingSetting("KEYSTORE_PASSWORD", "KEYSTORE_PASSWORD")
                    keyAlias = signingSetting("KEY_ALIAS", "KEY_ALIAS")
                    keyPassword = signingSetting("KEY_PASSWORD", "KEY_PASSWORD")
                }
            }

            val debugAbis = listSetting("build.debugAbi").ifEmpty { SHIPPED_ABIS }
            android.buildTypes {
                getByName("debug") {
                    ndk {
                        abiFilters += debugAbis
                    }
                }
                getByName("release") {
                    ndk {
                        abiFilters += SHIPPED_ABIS
                    }
                    isMinifyEnabled = false
                    proguardFiles(
                        android.getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                    if (keystorePath.isNotEmpty()) {
                        signingConfig = releaseSigning
                    }
                }
            }
        }
    }
}
