package com.aliothmoon.maafw.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/**
 * The chain that packs the external agent runtime; apply after maafw.android.application
 * Its source directory comes from the build profile, see [BuildProfile]
 * Leaving it unset means no agent runtime in the package: a PI that declares an agent then fails
 * in prepare(), the build itself does not stop
 * An agent.abi entry missing a runtime's files or executable fails packaging, compilation still runs
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

            val agentRuntimes = profile.agentRuntimes
            val declaresBundleRuntime = agentRuntimes.any { it.location == AGENT_LOCATION_BUNDLE }
            fun abiTrees(category: String) = agentSourceDir?.let { dir ->
                agentAbiPatterns.associateWith { abi -> fileTree(dir) { include("$abi/$category/**") } }
            }.orEmpty()
            val bundleTrees = abiTrees("bundle")
            val jniLibTrees = abiTrees("jniLibs")

            val packAgentBundles = tasks.register<Zip>("packAgentBundles") {
                group = "build"
                description = "Pack the configured agent bundles per ABI"
                destinationDirectory.set(layout.buildDirectory.dir("generated/agentBundle"))
                archiveFileName.set("bundle.zip")
                onlyIf { declaresBundleRuntime }
                bundleTrees.values.forEach { tree ->
                    from(tree) {
                        // <abi>/bundle/** flattens to <abi>/**: bundle is only a category in the
                        // source tree and means nothing on the device
                        eachFile { path = path.replaceFirst("/bundle/", "/") }
                        includeEmptyDirs = false
                    }
                }
            }

            // Not inside packAgentBundles: an empty source turns that task NO-SOURCE, its actions never
            // run and the archive of an earlier build stays in place
            val verifyAgentRuntimes = tasks.register("verifyAgentRuntimes") {
                group = "verification"
                description = "Fail packaging when an agent.abi entry lacks the files or the executable a runtime needs"
                onlyIf { agentRuntimes.isNotEmpty() }
                doLast {
                    if (agentAbiPatterns.isEmpty()) {
                        throw GradleException("the profile declares agent runtimes but agent.abi lists no ABI")
                    }
                    val sourceDir = File(requireNotNull(agentSourceDir) { "agent.runtimes passed without agent.sourceDir" })
                    val problems = agentRuntimes.flatMapIndexed { index, runtime ->
                        val bundle = runtime.location == AGENT_LOCATION_BUNDLE
                        val category = if (bundle) "bundle" else "jniLibs"
                        (if (bundle) bundleTrees else jniLibTrees).flatMap { (pattern, tree) ->
                            // Every ABI directory the entry matched lands on some device, so each one needs the executable
                            val abis = sortedSetOf<String>()
                            tree.visit { if (!isDirectory) abis += relativePath.segments.first() }
                            if (abis.isEmpty()) {
                                listOf("runtimes[$index]: no $pattern/$category/** files")
                            } else {
                                abis.map { "$it/$category/${runtime.executable}" }
                                    .filterNot { File(sourceDir, it).isFile }
                                    .map { "runtimes[$index]: $it is missing" }
                            }
                        }
                    }
                    if (problems.isNotEmpty()) {
                        throw GradleException(
                            "the agent runtimes in the profile do not match $sourceDir (agent.abi: $agentAbiPatterns)\n" +
                                problems.joinToString("\n") { "  - $it" },
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

            val agentLibs = agentSourceDir?.let { dir ->
                fileTree(dir) { agentAbiPatterns.forEach { include("$it/jniLibs/**") } }
            } ?: files()
            val syncAgentJniLibs = tasks.register<AgentFilesSyncTask>("syncAgentJniLibs") {
                group = "build"
                description = "Sync the configured single-file executables into jniLibs"
                inputs.files(agentLibs).withPathSensitivity(PathSensitivity.RELATIVE)
                outputs.dir(agentJniLibsDir)
                doLast {
                    fs.sync {
                        into(agentJniLibsDir)
                        from(agentLibs) {
                            eachFile { path = path.replaceFirst("/jniLibs/", "/") }
                            includeEmptyDirs = false
                        }
                    }
                    agentJniLibsDir.get().asFile.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.forEach { abi ->
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
                // inputs.files rather than inputs.file: an empty bundle source never produces the archive,
                // and inputs.file would fail validation before verifyAgentRuntimes can say why
                inputs.files(bundleZip).withPathSensitivity(PathSensitivity.RELATIVE)
                outputs.dir(agentIndexDir)
                onlyIf { declaresBundleRuntime }
                doLast {
                    val dir = agentIndexDir.get().asFile
                    dir.deleteRecursively()
                    dir.mkdirs()
                    val archive = bundleZip.get().asFile
                    if (!archive.isFile) return@doLast
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

            val agentAssetSources = files(writeAgentDescriptor)
            if (declaresBundleRuntime) agentAssetSources.from(packAgentBundles, writeAgentIndex)
            val syncAgentAssets = tasks.register<AgentFilesSyncTask>("syncAgentAssets") {
                group = "build"
                description = "Lay the generated agent runtime files into assets"
                inputs.files(agentAssetSources).withPathSensitivity(PathSensitivity.RELATIVE)
                outputs.dir(agentAssetsDir)
                doLast {
                    fs.sync {
                        into(agentAssetsDir)
                        from(writeAgentDescriptor) { into("agent") }
                        if (declaresBundleRuntime) {
                            from(packAgentBundles) { into("agent") }
                            from(writeAgentIndex)
                        }
                    }
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
                    // Gates packaging only; preBuild leaves it out so compilation and unit tests are not
                    // held up by an unfinished agent dist
                    dependsOn(verifyAgentRuntimes)
                }
            tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
                .configureEach {
                    inputs.files(agentJniLibsDir.map { it.asFileTree })
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                }
        }
    }
}

/**
 * Syncs in its own action: Sync reports NO-SOURCE when every source is empty and keeps its previous
 * output, so files from an earlier profile would leak into later packages
 */
abstract class AgentFilesSyncTask : DefaultTask() {
    @get:Inject
    abstract val fs: FileSystemOperations
}
