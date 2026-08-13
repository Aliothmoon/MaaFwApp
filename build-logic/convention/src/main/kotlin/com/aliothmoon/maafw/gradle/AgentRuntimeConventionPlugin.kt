package com.aliothmoon.maafw.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import java.io.File
import java.security.MessageDigest

/**
 * The chain that packs the external agent runtime; apply after maafw.android.application
 * Its source directory comes from the build profile, see [BuildProfile]
 * Leaving it unset means no agent runtime in the package: a PI that declares an agent then fails
 * in prepare(), the build itself does not stop
 * Full wiring steps live in docs/agent-integration.md
 */
class AgentRuntimeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val profile = buildProfile()
            val agentSourceDir = profile.agentSourceDir
            val agentAbiPatterns = profile.agentAbi

            val agentAssetsDir = layout.buildDirectory.dir("generated/agentAssets")
            val agentJniLibsDir = layout.buildDirectory.dir("generated/agentJniLibs")

            val emptyAgentSource = layout.buildDirectory.dir("generated/agentEmptySource")
                .get().asFile.apply { mkdirs() }

            val packAgentBundles = tasks.register<Zip>("packAgentBundles") {
                group = "build"
                description = "Pack the configured agent bundles per ABI into assets/agent"
                destinationDirectory.set(agentAssetsDir.map { it.dir("agent") })
                archiveFileName.set("bundle.zip")
                if (agentSourceDir != null) {
                    agentAbiPatterns.forEach { abi ->
                        from(agentSourceDir) {
                            include("$abi/bundle/**")
                            // <abi>/bundle/** flattens to <abi>/**: bundle is only a category in the
                            // source tree and means nothing on the device
                            eachFile { path = path.replaceFirst("/bundle/", "/") }
                            includeEmptyDirs = false
                        }
                    }
                }
            }

            val descriptorDir = layout.buildDirectory.dir("generated/agentDescriptor")
            val descriptor = profile.agentRuntimes.takeIf { it.isNotEmpty() }?.toDescriptorJson()

            val writeAgentDescriptor = tasks.register("writeAgentDescriptor") {
                group = "build"
                description = "Write the agent runtime descriptor declared by the profile"
                // The descriptor is the input here, not a file on disk: it is assembled from the
                // profile, so editing the profile has to invalidate this task
                inputs.property("descriptor", descriptor.orEmpty())
                outputs.dir(descriptorDir)
                doLast {
                    val dir = descriptorDir.get().asFile
                    dir.deleteRecursively()
                    dir.mkdirs()
                    if (descriptor != null) File(dir, "agent-runtime.json").writeText(descriptor)
                }
            }

            val syncAgentAssets = tasks.register<Sync>("syncAgentAssets") {
                group = "build"
                description = "Lay the generated agent runtime descriptor into assets/agent"
                dependsOn(packAgentBundles)
                // The index file has to live outside this level: Sync wipes whatever in the target
                // does not come from the source
                into(agentAssetsDir.map { it.dir("agent") })
                // packAgentBundles writes bundle.zip into the same directory, Sync must not treat it as leftover
                preserve { include("bundle.zip") }
                // Always a real source, even when empty: with no source at all Sync reports NO-SOURCE
                // and skips, so a descriptor left by an earlier profile would stay in the generated
                // directory and leak into later packages
                from(writeAgentDescriptor)
            }

            val syncAgentJniLibs = tasks.register<Sync>("syncAgentJniLibs") {
                group = "build"
                description = "Sync the configured single-file executables into jniLibs"
                into(agentJniLibsDir)
                if (agentSourceDir != null) {
                    from(agentSourceDir) {
                        agentAbiPatterns.forEach { include("$it/jniLibs/**") }
                        eachFile { path = path.replaceFirst("/jniLibs/", "/") }
                        includeEmptyDirs = false
                    }
                } else {
                    from(emptyAgentSource)
                }
            }

            val writeAgentIndex = tasks.register("writeAgentIndex") {
                group = "build"
                description = "Hash the agent runtime archive into assets/agent.fingerprint"
                dependsOn(syncAgentAssets)
                val bundleZip = agentAssetsDir.map { it.file("agent/bundle.zip") }
                val fingerprintFile = agentAssetsDir.map { it.file("agent.fingerprint") }
                // inputs.files rather than inputs.file: with no agent configured the archive may not
                // exist and inputs.file would fail validation outright
                inputs.files(bundleZip).withPathSensitivity(PathSensitivity.RELATIVE)
                outputs.file(fingerprintFile)
                doLast {
                    fingerprintFile.get().asFile.parentFile.mkdirs()
                    val digest = MessageDigest.getInstance("SHA-256")
                    bundleZip.get().asFile.takeIf { it.isFile }?.inputStream()?.use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = stream.read(buffer)
                            if (read <= 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                    fingerprintFile.get().asFile.writeText(
                        digest.digest().joinToString("") { "%02x".format(it) })
                }
            }

            tasks.named("preBuild") {
                dependsOn(writeAgentIndex, syncAgentJniLibs)
            }

            extensions.configure<ApplicationAndroidComponentsExtension> {
                onVariants { variant ->
                    variant.sources.assets?.addStaticSourceDirectory(
                        agentAssetsDir.get().asFile.absolutePath
                    )
                    variant.sources.jniLibs?.addStaticSourceDirectory(
                        agentJniLibsDir.get().asFile.absolutePath
                    )
                }
            }

            tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
                .configureEach {
                    inputs.files(agentAssetsDir.map { it.asFileTree })
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                }
            tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
                .configureEach {
                    inputs.files(agentJniLibsDir.map { it.asFileTree })
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                }
        }
    }
}
