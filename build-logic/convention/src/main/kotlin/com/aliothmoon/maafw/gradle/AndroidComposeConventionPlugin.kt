package com.aliothmoon.maafw.gradle

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

/**
 * Apply after maafw.android.application or maafw.android.library: this reads the android extension they register
 * Only the shared floor lands here (BOM, ui, ui-graphics), material3 / icons / tooling stay per module
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            extensions.getByType<CommonExtension>().buildFeatures.compose = true

            val bom = libs.library("androidx-compose-bom")
            dependencies {
                add("implementation", platform(bom))
                add("androidTestImplementation", platform(bom))
                add("implementation", libs.library("androidx-ui"))
                add("implementation", libs.library("androidx-ui-graphics"))
            }
        }
    }
}
