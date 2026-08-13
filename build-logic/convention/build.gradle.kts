import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "com.aliothmoon.maafw.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // compileOnly：插件里只引 AGP/KGP 的 DSL 类型，运行时那份由消费方的 buildscript classpath 提供
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "maafw.android.application"
            implementationClass = "com.aliothmoon.maafw.gradle.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "maafw.android.library"
            implementationClass = "com.aliothmoon.maafw.gradle.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "maafw.android.compose"
            implementationClass = "com.aliothmoon.maafw.gradle.AndroidComposeConventionPlugin"
        }
        register("kotlinJvm") {
            id = "maafw.kotlin.jvm"
            implementationClass = "com.aliothmoon.maafw.gradle.KotlinJvmConventionPlugin"
        }
        register("piAssets") {
            id = "maafw.pi.assets"
            implementationClass = "com.aliothmoon.maafw.gradle.PiAssetsConventionPlugin"
        }
        register("agentRuntime") {
            id = "maafw.agent.runtime"
            implementationClass = "com.aliothmoon.maafw.gradle.AgentRuntimeConventionPlugin"
        }
    }
}
