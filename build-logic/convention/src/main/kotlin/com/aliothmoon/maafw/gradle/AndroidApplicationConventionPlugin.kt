package com.aliothmoon.maafw.gradle

import com.android.build.api.artifact.SingleArtifact
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
                versionName = textSetting("build.versionName", "BUILD_VERSION_NAME") ?: gitVersionName()
                println("Build version: applicationId=$applicationId, versionCode=$versionCode, versionName=$versionName")

                // Empty string is the "not a submodule" / "no tag" signal, rendered app-side
                buildConfigField("String", "MAFW_GIT_COMMIT", "\"" + gitOwnHeadShort() + "\"")
                buildConfigField("String", "MAFW_GIT_TAG", "\"" + gitOwnHeadExactTag() + "\"")
                buildConfigField("String", "MAFW_PARENT_GIT_COMMIT", "\"" + gitParentHeadShort() + "\"")
                buildConfigField("String", "MAFW_PARENT_GIT_TAG", "\"" + gitParentHeadExactTag() + "\"")

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

            extensions.configure<ApplicationAndroidComponentsExtension> {
                onVariants { variant ->
                    // Set here rather than as an applicationIdSuffix on the build type: the
                    // baselineprofile plugin passes its own action to buildTypes.create, which
                    // runs after configureEach and drops the suffix again. Without this the
                    // generation build installs over whatever the developer has on the device
                    val generated = variant.buildType.orEmpty().let {
                        it.startsWith("nonMinified") || it.startsWith("benchmarkRelease")
                    }
                    if (generated) {
                        variant.applicationId.set(maafwApplicationId() + BENCHMARK_APP_ID_SUFFIX)
                    }
                    // Debug / nonMinified / benchmark do not produce OBFUSCATION_MAPPING_FILE.
                    // Wiring the task there leaves mapping unconfigured and configure fails.
                    if (variant.name == "release") {
                        val verify = tasks.register<VerifyR8KeepsTask>("verifyReleaseR8Keeps") {
                            mapping.set(
                                variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE),
                            )
                            criticalClasses.set(R8_CRITICAL_CLASSES)
                        }
                        tasks.matching { it.name == "assembleRelease" }
                            .configureEach { finalizedBy(verify) }
                    }
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
                    // Resource shrinking stays off: it is a separate lever with its own
                    // failure mode, and nothing here has measured it yet
                    isMinifyEnabled = true
                    proguardFiles(
                        android.getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                    if (keystorePath.isNotEmpty()) {
                        signingConfig = releaseSigning
                    }
                }
            }

            // androidx.baselineprofile brings a pair of its own: nonMinifiedRelease to collect
            // the profile from, benchmarkRelease to measure the shipping shape with. A
            // hand-rolled `benchmark` type used to sit alongside doing the latter's job;
            // keeping both multiplied the test module's variants and let an unsuffixed one
            // through, which uninstalled the app on the developer's device
            //
            // They keep release's full ABI set: collection needs API 33+ or a rooted adb
            // session, so it often has to run on an emulator, and that one is x86_64.
            // configureEach rather than getByName - they do not exist yet when this runs
            android.buildTypes.configureEach {
                if (!name.startsWith("nonMinified") && !name.startsWith("benchmarkRelease")) {
                    return@configureEach
                }
                // Debug signing so they install without release keystore material; profileable
                // so macrobenchmark can attach a tracer to a non-debuggable build.
                // The applicationId suffix is set in onVariants above, not here
                signingConfig = android.signingConfigs.getByName("debug")
                isProfileable = true
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
    val writeProfileSplash = tasks.register("writeProfileSplash") {
        group = "build"
        description = "Override the splash theme so it uses the profile icon"
        val themeOut = profileResDir.map { it.file("values/profile_splash.xml") }
        val iconOut = profileResDir.map { it.file("drawable/ic_profile_splash.xml") }
        outputs.files(themeOut, iconOut)
        doLast {
            // SplashScreen always circle-masks the icon. Inset so the full bitmap sits
            // inside that circle; transparent pixels then show colorBackground, not black
            iconOut.get().asFile.apply {
                parentFile.mkdirs()
                writeText(
                    """
                    <layer-list xmlns:android="http://schemas.android.com/apk/res/android">
                        <item
                            android:bottom="48dp"
                            android:left="48dp"
                            android:right="48dp"
                            android:top="48dp">
                            <bitmap
                                android:gravity="fill"
                                android:src="@mipmap/$PROFILE_ICON_NAME" />
                        </item>
                    </layer-list>
                    """.trimIndent() + "\n",
                )
            }
            themeOut.get().asFile.apply {
                parentFile.mkdirs()
                writeText(
                    """
                    <resources>
                        <style name="Theme.MaaFwApp.Starting" parent="Theme.SplashScreen">
                            <item name="windowSplashScreenBackground">?android:attr/colorBackground</item>
                            <item name="windowSplashScreenAnimatedIcon">@drawable/ic_profile_splash</item>
                            <item name="postSplashScreenTheme">@style/Theme.MaaFwApp</item>
                        </style>
                    </resources>
                    """.trimIndent() + "\n",
                )
            }
        }
    }

    tasks.named("preBuild") {
        dependsOn(syncProfileIcon, writeProfileSplash)
    }

    extensions.configure<ApplicationAndroidComponentsExtension> {
        onVariants { variant ->
            variant.sources.res?.addStaticSourceDirectory(profileResDir.get().asFile.absolutePath)
        }
    }
}
