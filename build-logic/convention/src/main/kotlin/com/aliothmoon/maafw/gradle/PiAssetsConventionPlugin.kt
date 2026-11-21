package com.aliothmoon.maafw.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import java.util.zip.ZipFile

/**
 * The chain that syncs the external PI project into a single archive; apply after
 * maafw.android.application. Which PI and which parts of it come from the build profile,
 * see [BuildProfile]
 *
 * Loose assets are rewritten by AAPT (`<dir>_*` dropped, `.*` dropped, `.gz` decompressed).
 * Same reason agent runtimes ship as bundle.zip: syncPiAssets -> packPiArchive -> assets/pi.zip
 */
class PiAssetsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val profile = buildProfile()
            val assetsSourceDir = profile.assetsDir
            val piAssetsDir = layout.buildDirectory.dir("generated/piAssets")
            val piDir = piAssetsDir.map { it.dir("pi") }
            val packedDir = piAssetsDir.map { it.dir("packed") }
            val includePatterns = profile.piInclude
            val excludePatterns = profile.piExclude

            val syncPiAssets = tasks.register<Sync>("syncPiAssets") {
                group = "build"
                description = "Sync the configured PI project into the generated PI tree"
                into(piDir)
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

            // Not inside packPiArchive: an empty piDir turns that task NO-SOURCE so its actions never
            // run, and directories left empty by include/exclude would silently vanish under includeEmptyDirs = false
            val verifyPiAssets = tasks.register("verifyPiAssets") {
                group = "verification"
                description = "Fail packaging when a resource directory declared for Android in interface.json is missing from the synced PI tree"
                dependsOn(syncPiAssets)
                onlyIf { assetsSourceDir != null }
                doLast {
                    val root = piDir.get().asFile
                    val problems = verifySyncedPiAssets(root)
                    if (problems.isNotEmpty()) {
                        throw GradleException(
                            "the synced PI tree in $root is missing resource directories declared in interface.json (check include/exclude in the profile)\n" +
                                problems.joinToString("\n") { "  - $it" },
                        )
                    }
                }
            }

            val packPiArchive = tasks.register<Zip>("packPiArchive") {
                group = "build"
                description = "Pack the synced PI tree into assets/pi.zip"
                dependsOn(syncPiAssets, verifyPiAssets)
                from(piDir)
                destinationDirectory.set(packedDir)
                archiveFileName.set("pi.zip")
                includeEmptyDirs = false
                doLast {
                    val archive = archiveFile.get().asFile
                    val entries = ZipFile(archive).use { it.size() }
                    logger.lifecycle("PI archive pi.zip  ${archive.length().toSizeText()}  $entries entries")
                }
            }

            tasks.named("preBuild") {
                dependsOn(packPiArchive)
            }

            packedDir.get().asFile.mkdirs()

            extensions.configure<ApplicationAndroidComponentsExtension> {
                onVariants { variant ->
                    variant.sources.assets?.addStaticSourceDirectory(
                        packedDir.get().asFile.absolutePath
                    )
                }
            }

            tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
                .configureEach {
                    inputs.files(packedDir.map { it.asFileTree })
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                    dependsOn(verifyPiAssets)
                }
        }
    }
}
