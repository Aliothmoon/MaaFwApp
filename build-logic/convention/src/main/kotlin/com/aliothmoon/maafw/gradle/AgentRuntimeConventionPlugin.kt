package com.aliothmoon.maafw.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import java.security.MessageDigest

/**
 * The chain that packs the external agent runtime (agent.sourceDir); apply after maafw.android.application
 * Leaving it unset means no agent runtime in the package: a PI that declares an agent then fails
 * in prepare(), the build itself does not stop
 * Full wiring steps live in docs/agent-integration.md
 */
class AgentRuntimeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val agentSourceDir = pathSetting("agent.sourceDir", "AGENT_SOURCE_DIR")
            val agentAbiPatterns = listSetting("agent.abi").ifEmpty { listOf("*") }

            val agentAssetsDir = layout.buildDirectory.dir("generated/agentAssets")
            val agentJniLibsDir = layout.buildDirectory.dir("generated/agentJniLibs")

            val emptyAgentSource = layout.buildDirectory.dir("generated/agentEmptySource")
                .get().asFile.apply { mkdirs() }

            val packAgentBundles = tasks.register<Zip>("packAgentBundles") {
                group = "build"
                description = "Pack the bundles under agent.sourceDir per ABI into assets/agent"
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

            val syncAgentAssets = tasks.register<Sync>("syncAgentAssets") {
                group = "build"
                description = "Sync the agent runtime descriptor from agent.sourceDir into assets/agent"
                dependsOn(packAgentBundles)
                // The index file has to live outside this level: Sync wipes whatever in the target
                // does not come from the source
                into(agentAssetsDir.map { it.dir("agent") })
                // packAgentBundles writes bundle.zip into the same directory, Sync must not treat it as leftover
                preserve { include("bundle.zip") }
                if (agentSourceDir != null) {
                    from(agentSourceDir) { include("agent-runtime.json") }
                } else {
                    // Hand it an empty source rather than nothing: with no source at all Sync reports
                    // NO-SOURCE and skips, so a runtime left by an earlier agent.sourceDir would stay
                    // in the generated directory and leak into later packages
                    from(emptyAgentSource)
                    doFirst { logger.info("agent.sourceDir is not set, the build output will not contain an agent runtime") }
                }
            }

            val syncAgentJniLibs = tasks.register<Sync>("syncAgentJniLibs") {
                group = "build"
                description = "Sync the single-file executables from agent.sourceDir into jniLibs"
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
                // inputs.files rather than inputs.file: without agent.sourceDir the archive may not
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
