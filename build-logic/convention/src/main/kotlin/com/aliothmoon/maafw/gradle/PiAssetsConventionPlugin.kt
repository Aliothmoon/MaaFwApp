package com.aliothmoon.maafw.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

/**
 * The chain that syncs the external PI project into assets; apply after maafw.android.application
 * Which PI and which parts of it come from the build profile, see [BuildProfile]
 * syncPiAssets -> writePiManifest -> assets/pi + assets/pi.manifest
 */
class PiAssetsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val profile = buildProfile()
            val assetsSourceDir = profile.assetsDir
            val piAssetsDir = layout.buildDirectory.dir("generated/piAssets")
            val includePatterns = profile.piInclude
            val excludePatterns = profile.piExclude

            val syncPiAssets = tasks.register<Sync>("syncPiAssets") {
                group = "build"
                description = "Sync the configured PI project into assets/pi"
                // Mirrors PI_ASSET_ROOT: the source dir sits one level up so the in-APK path is assets/pi
                into(piAssetsDir.map { it.dir("pi") })
                if (assetsSourceDir != null) {
                    from(assetsSourceDir) {
                        includePatterns.forEach { include(it) }
                        // Exclude wins over include in Gradle, which is what carves heavy files
                        // back out of an otherwise wholesale directory
                        excludePatterns.forEach { exclude(it) }
                        // Always on, on top of whatever the profile says: the include list already
                        // keeps them out, pruning explicitly only avoids walking .git and other
                        // large directories of the upstream repo
                        exclude(".git/**", "node_modules/**", ".venv/**", "__pycache__/**")
                    }
                } else {
                    // Soft failure: unit tests read src/test/fixtures and must not be blocked by PI config
                    // A package without a PI ends up in ProjectState.Error at runtime
                    doFirst {
                        logger.warn("no PI configured (pi.profile in local.properties or PI_PROFILE), the build output will not contain a PI")
                    }
                }
            }

            val writePiManifest = tasks.register("writePiManifest") {
                group = "build"
                description = "List the synced PI entries into assets/pi.manifest"
                dependsOn(syncPiAssets)
                val piDir = piAssetsDir.map { it.dir("pi") }
                val manifestFile = piAssetsDir.map { it.file("pi.manifest") }
                inputs.dir(piDir).withPathSensitivity(PathSensitivity.RELATIVE)
                outputs.file(manifestFile)
                doLast {
                    val root = piDir.get().asFile
                    val entries = root.walkTopDown().filter { it.isFile }
                        .map { it.toRelativeString(root).replace('\\', '/') }.sorted().toList()
                    manifestFile.get().asFile.writeText(entries.joinToString("\n"))
                }
            }

            tasks.named("preBuild") {
                dependsOn(writePiManifest)
            }

            extensions.configure<ApplicationAndroidComponentsExtension> {
                onVariants { variant ->
                    variant.sources.assets?.addStaticSourceDirectory(
                        piAssetsDir.get().asFile.absolutePath
                    )
                }
            }

            tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
                .configureEach {
                    inputs.files(piAssetsDir.map { it.asFileTree })
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                }
        }
    }
}
