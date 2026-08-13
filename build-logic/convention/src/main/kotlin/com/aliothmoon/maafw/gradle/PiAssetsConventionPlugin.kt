package com.aliothmoon.maafw.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

/** The parts of a PI that go into the package; pi.includeExtra appends to this list */
private val PI_INCLUDE_PATTERNS = listOf(
    "interface.json",
    "tasks/**",
    "resource/**",
    "resource_*/**",
    "data/**",
    "locales/**",
    "CONTACT",
    "LICENSE",
)

/**
 * The chain that syncs the external PI project (pi.sourceDir) into assets; apply after maafw.android.application
 * See the architecture chain in CLAUDE.md: syncPiAssets -> writePiManifest -> assets/pi + assets/pi.manifest
 */
class PiAssetsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val piSourceDir = pathSetting("pi.sourceDir", "PI_SOURCE_DIR")
            val piAssetsDir = layout.buildDirectory.dir("generated/piAssets")
            val includePatterns = PI_INCLUDE_PATTERNS + listSetting("pi.includeExtra")

            val syncPiAssets = tasks.register<Sync>("syncPiAssets") {
                group = "build"
                description = "Sync the PI project at pi.sourceDir into assets/pi"
                // Mirrors PI_ASSET_ROOT: the source dir sits one level up so the in-APK path is assets/pi
                into(piAssetsDir.map { it.dir("pi") })
                if (piSourceDir != null) {
                    from(piSourceDir) {
                        includePatterns.forEach { include(it) }
                        // The include list already keeps them out; pruning explicitly only avoids
                        // walking .git and other large directories of the upstream repo
                        exclude(".git/**", "node_modules/**", ".venv/**", "__pycache__/**")
                    }
                } else {
                    // Soft failure: unit tests read src/test/fixtures and must not be blocked by PI config
                    // A package without a PI ends up in ProjectState.Error at runtime
                    doFirst {
                        logger.warn("pi.sourceDir (local.properties) or PI_SOURCE_DIR is not set, the build output will not contain a PI")
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
