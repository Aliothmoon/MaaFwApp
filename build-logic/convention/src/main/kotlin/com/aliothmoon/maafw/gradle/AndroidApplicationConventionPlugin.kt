package com.aliothmoon.maafw.gradle

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/** ABIs that ship; a debug build can narrow to one via build.debugAbi to save build time */
private val SHIPPED_ABIS = listOf("arm64-v8a", "x86_64")

/** The package every build sits under; a profile only appends to it, it never replaces it */
private const val BASE_APPLICATION_ID = "com.aliothmoon.maafw"

/**
 * A profile's app.id becomes package segments, so it takes package rules rather than free text
 * Rejecting the rest here beats finding out from a manifest merger error or, worse, an installed
 * package under a name nobody meant to publish
 */
private val APP_ID_PATTERN = Regex("""[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*""")

/**
 * A separate package on purpose: the benchmarks run `pm clear` to measure a first launch, which
 * would otherwise wipe the configurations of the build the developer is actually using
 */
internal const val BENCHMARK_APP_ID_SUFFIX = ".benchmark"

/** Resource name the profile icon lands under, kept apart from the checked-in ic_launcher */
private const val PROFILE_ICON_NAME = "ic_profile_launcher"

/** Single source for both :app and :macrobenchmark, which has to name the same package */
internal fun Project.maafwApplicationId(): String =
    buildProfile().appId?.let { "$BASE_APPLICATION_ID.${it.requireAppId()}" } ?: BASE_APPLICATION_ID

/** Android decodes neither ico nor svg, a profile pointing at one is a mistake worth failing on */
private val ICON_EXTENSIONS = setOf("png", "webp")

/**
 * The whole shell around the app module: SDK baseline, git version, packaging, signing, build types
 * The module script keeps only what this app is: namespace, native, buildFeatures
 * Identity (applicationId, launcher label and icon) comes from the build profile, see [BuildProfile]
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")

            val android = extensions.getByType<ApplicationExtension>()
            configureAndroidCommon(android)

            val profile = buildProfile()
            val icon = profile.appIcon?.also { configureProfileIcon(it) }

            android.defaultConfig {
                applicationId = maafwApplicationId()
                targetSdk = TARGET_SDK
                versionCode = gitVersionCode()
                versionName = gitVersionName()
                println("Build version: applicationId=$applicationId, versionCode=$versionCode, versionName=$versionName")

                // Placeholders rather than resValue: with no profile the value stays a resource
                // reference and the checked-in label and icon keep working untouched
                manifestPlaceholders["appLabel"] = profile.appLabel ?: "@string/app_name"
                manifestPlaceholders["appIcon"] =
                    if (icon != null) "@mipmap/$PROFILE_ICON_NAME" else "@mipmap/ic_launcher"
                manifestPlaceholders["appRoundIcon"] =
                    if (icon != null) "@mipmap/$PROFILE_ICON_NAME" else "@mipmap/ic_launcher_round"

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
                // What :macrobenchmark drives: release-shaped for the numbers to mean anything,
                // profileable so the shell can attach a tracer, debug-signed so it installs
                // without release keystore material
                create("benchmark") {
                    initWith(getByName("release"))
                    applicationIdSuffix = BENCHMARK_APP_ID_SUFFIX
                    isDebuggable = false
                    isProfileable = true
                    signingConfig = android.signingConfigs.getByName("debug")
                    matchingFallbacks += "release"
                    // Only ever runs on the device that is plugged in
                    ndk {
                        abiFilters.clear()
                        abiFilters += debugAbis
                    }
                }
            }
        }
    }
}

private fun String.requireAppId(): String {
    require(APP_ID_PATTERN.matches(this)) {
        "app.id must be lowercase package segments such as m9a or maa.end, got \"$this\""
    }
    return this
}

/**
 * Drops the profile icon into a generated res tree as a single xxxhdpi bitmap
 * One density only, so launchers on API 26+ show the bitmap unmasked instead of an adaptive icon;
 * shipping a full adaptive set would mean the profile carrying foreground, background and densities
 */
private fun Project.configureProfileIcon(icon: java.io.File) {
    require(icon.isFile) { "app.icon points at a missing file: ${icon.absolutePath}" }
    val extension = icon.extension.lowercase()
    require(extension in ICON_EXTENSIONS) {
        "app.icon must be one of $ICON_EXTENSIONS, got .$extension (${icon.absolutePath})"
    }

    val profileResDir = layout.buildDirectory.dir("generated/profileRes")
    val syncProfileIcon = tasks.register<Copy>("syncProfileIcon") {
        group = "build"
        description = "Copy the profile launcher icon into the generated resources"
        from(icon) { rename { "$PROFILE_ICON_NAME.$extension" } }
        into(profileResDir.map { it.dir("mipmap-xxxhdpi") })
    }

    tasks.named("preBuild") {
        dependsOn(syncProfileIcon)
    }

    extensions.configure<ApplicationAndroidComponentsExtension> {
        onVariants { variant ->
            variant.sources.res?.addStaticSourceDirectory(profileResDir.get().asFile.absolutePath)
        }
    }
}
