import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

val gitVersionCode: Int by lazy {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}

val gitVersionName: String by lazy {
    val desc = providers.exec {
        commandLine("git", "describe", "--tags", "--always")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
    val match = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-(\d+)-g[0-9a-f]+)?$""").matchEntire(desc)
    if (match != null) {
        val (major, minor, patch, distance) = match.destructured
        if (distance.isEmpty()) "$major.$minor.$patch"
        else "$major.$minor.${patch.toInt() + 1}-beta.$distance"
    } else {
        desc.removePrefix("v").ifEmpty { "0.0.0-dev" }
    }
}

android {
    namespace = "com.aliothmoon.maafw"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aliothmoon.maafw"
        minSdk = 28
        targetSdk = 36
        versionCode = gitVersionCode
        versionName = gitVersionName
        println("Build version: versionCode=$versionCode, versionName=$versionName")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
                ?: localProperties.getProperty("KEYSTORE_PATH", "")
            if (keystorePath.isNotEmpty()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?: localProperties.getProperty("KEYSTORE_PASSWORD", "")
                keyAlias = System.getenv("KEY_ALIAS")
                    ?: localProperties.getProperty("KEY_ALIAS", "")
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: localProperties.getProperty("KEY_PASSWORD", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val keystorePath = System.getenv("KEYSTORE_PATH")
                ?: localProperties.getProperty("KEYSTORE_PATH", "")
            if (keystorePath.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.squircle)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.window)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.timber)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
