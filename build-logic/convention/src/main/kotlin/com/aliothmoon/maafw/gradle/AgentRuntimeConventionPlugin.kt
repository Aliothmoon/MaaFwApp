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
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

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

            // Only BUNDLE entries read bundle.zip and the fingerprint at run time.
            val declaresBundleRuntime =
                profile.agentRuntimes.any { it.location == AGENT_LOCATION_BUNDLE }

            val emptyAgentSource = layout.buildDirectory.dir("generated/agentEmptySource")
                .get().asFile.apply { mkdirs() }

            val packAgentBundles = tasks.register<Zip>("packAgentBundles") {
                group = "build"
                description = "Pack the configured agent bundles per ABI"
                destinationDirectory.set(layout.buildDirectory.dir("generated/agentBundle"))
                archiveFileName.set("bundle.zip")
                inputs.property("bundleRuntimeDeclared", declaresBundleRuntime)
                inputs.property("agentAbiPatterns", agentAbiPatterns)
                onlyIf { declaresBundleRuntime }
                from(emptyAgentSource)
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
                doLast {
                    val sourceDir = requireNotNull(agentSourceDir)
                    val missingAbis = agentAbiPatterns.filter { abi ->
                        fileTree(sourceDir).matching { include("$abi/bundle/**") }.files.isEmpty()
                    }
                    val archive = archiveFile.get().asFile
                    val packedAbis = ZipFile(archive).use { zip ->
                        zip.entries().asSequence()
                            .filterNot { it.isDirectory }
                            .map { it.name.substringBefore('/') }
                            .toSet()
                    }
                    if (missingAbis.isNotEmpty() || packedAbis.isEmpty()) {
                        throw GradleException(
                            "the profile declares a bundle agent runtime but $sourceDir has no " +
                                "<abi>/bundle/** content for ${missingAbis.joinToString()} " +
                                "(agent.abi: $agentAbiPatterns; packed ABIs: $packedAbis)",
                        )
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

            val syncAgentJniLibs = tasks.register<Sync>("syncAgentJniLibs") {
                group = "build"
                description = "Sync the configured single-file executables into nativeLibraryDir"
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
                doLast {
                    destinationDir.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.forEach { abi ->
                        val libs = abi.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
                            .joinToString { "${it.name} ${it.length().toSizeText()}" }
                        logger.lifecycle("Agent ${abi.name}  $libs")
                    }
                }
            }

            val agentIndexDir = layout.buildDirectory.dir("generated/agentIndex")
            val writeAgentIndex = tasks.register("writeAgentIndex") {
                group = "build"
                description = "Hash the packed agent bundle"
                dependsOn(packAgentBundles)
                val bundleZip = layout.buildDirectory.file("generated/agentBundle/bundle.zip")
                inputs.file(bundleZip).withPathSensitivity(PathSensitivity.RELATIVE)
                inputs.property("bundleRuntimeDeclared", declaresBundleRuntime)
                outputs.dir(agentIndexDir)
                onlyIf { declaresBundleRuntime }
                doLast {
                    val archive = bundleZip.get().asFile
                    if (!archive.isFile) {
                        throw GradleException(
                            "the profile declares a bundle agent runtime but ${archive.absolutePath} " +
                                "was not produced; see packAgentBundles",
                        )
                    }
                    val dir = agentIndexDir.get().asFile
                    dir.deleteRecursively()
                    dir.mkdirs()
                    val digest = MessageDigest.getInstance("SHA-256")
                    archive.inputStream().use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = stream.read(buffer)
                            if (read <= 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                    File(dir, "agent.fingerprint").writeText(
                        digest.digest().joinToString("") { "%02x".format(it) },
                    )
                }
            }

            val syncAgentAssets = tasks.register<Sync>("syncAgentAssets") {
                group = "build"
                description = "Lay the generated agent runtime files into assets"
                into(agentAssetsDir)
                from(writeAgentDescriptor) { into("agent") }
                if (declaresBundleRuntime) {
                    from(packAgentBundles) { into("agent") }
                    from(writeAgentIndex)
                }
            }

            tasks.named("preBuild") {
                dependsOn(syncAgentAssets, syncAgentJniLibs)
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
