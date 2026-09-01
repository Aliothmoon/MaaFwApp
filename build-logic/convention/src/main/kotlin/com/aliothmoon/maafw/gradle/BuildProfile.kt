package com.aliothmoon.maafw.gradle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.gradle.api.Project
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File

/** What a PI contributes to the package when the profile does not spell out its own list */
private val DEFAULT_PI_INCLUDE = listOf(
    "interface.json",
    "tasks/**",
    "resource/**",
    "resource_*/**",
    "data/**",
    "locales/**",
    "CONTACT",
    "LICENSE",
)

/** Where an executable lands; the two values are what AgentRuntimeLocation deserializes */
private val AGENT_LOCATIONS = setOf("nativeLibs", "bundle")

/** Pretty printed because it ends up in the APK where anyone debugging an agent will read it */
private val descriptorJson = Json { prettyPrint = true }

/**
 * One entry of the descriptor the app reads back as AgentRuntimeDescriptor.runtimes
 * Declared in the profile rather than shipped inside the agent dist: the dist is produced by a build
 * script that knows nothing about how a PI wants its agents launched, so the file was hand written
 * anyway and belongs with the rest of the recipe
 */
internal data class AgentRuntime(
    val location: String,
    val executable: String,
    val args: List<String>,
    val env: Map<String, String>,
)

/**
 * One packaging recipe: which PI goes in, what to take out of it, which agent runtime rides along,
 * and the identity the resulting app carries
 *
 * A fork adapts to another PI by writing one of these and pointing pi.profile at it, so no project
 * specific id or directory name ever enters this repo
 */
internal data class BuildProfile(
    /** Root of the PI on disk; upstream projects usually keep it in an assets directory, hence the profile key */
    val assetsDir: String?,
    val piInclude: List<String>,
    /** Applied on top of the include list, which is how a profile carves a few heavy files out of an included tree */
    val piExclude: List<String>,
    val agentSourceDir: String?,
    val agentAbi: List<String>,
    /** Order matters: entry n launches the PI's agent[n], the app refuses to guess a pairing */
    val agentRuntimes: List<AgentRuntime>,
    /** Package suffix only, never a whole applicationId; the base package is this repo's to decide */
    val appId: String?,
    val appLabel: String?,
    val appIcon: File?,
    /** Wins over the PI's own mirrorchyan_rid: the packager knows where this build is published */
    val mirrorchyanRid: String?,
)

/** Nothing configured at all: the package ships without a PI, see the soft failure on syncPiAssets */
private val NO_PROFILE = BuildProfile(
    assetsDir = null,
    piInclude = DEFAULT_PI_INCLUDE,
    piExclude = emptyList(),
    agentSourceDir = null,
    agentAbi = listOf("*"),
    agentRuntimes = emptyList(),
    appId = null,
    appLabel = null,
    appIcon = null,
    mirrorchyanRid = null,
)

/**
 * pi.profile in local.properties, or PI_PROFILE in the environment, is the only way to point the
 * build at a PI
 * Relative paths inside a profile resolve against the profile's own directory, which lets a profile
 * sit next to the PI it describes
 */
internal fun Project.buildProfile(): BuildProfile {
    val profilePath = pathSetting("pi.profile", "PI_PROFILE") ?: return NO_PROFILE
    val file = rootProject.file(profilePath)
    require(file.isFile) { "pi.profile points at a missing file: ${file.absolutePath}" }
    return runCatching { file.readProfile() }
        .getOrElse { throw IllegalStateException("invalid profile ${file.absolutePath}: ${it.message}", it) }
}

private fun File.readProfile(): BuildProfile {
    val loaded = Load(LoadSettings.builder().build()).loadFromString(readText())
    val root = loaded as? Map<*, *>
        ?: throw IllegalStateException("the top level of a profile must be a mapping")
    val base = parentFile
    val agent = root.child("agent")
    val app = root.child("app")
    val update = root.child("update")

    val agentSourceDir = agent?.text("sourceDir")?.let { base.resolvePath(it).absolutePath }
    val agentRuntimes = agent?.children("runtimes")?.map { it.toAgentRuntime() }.orEmpty()
    // One without the other never produces a launchable agent, and both halves fail far from here:
    // a missing descriptor only surfaces when a run reaches prepare(), a missing dist at exec time
    require(agentSourceDir == null || agentRuntimes.isNotEmpty()) {
        "agent.sourceDir is set but agent.runtimes is empty, nothing could be launched from it"
    }
    require(agentRuntimes.isEmpty() || agentSourceDir != null) {
        "agent.runtimes is declared but agent.sourceDir is not, there would be no executables to launch"
    }

    return BuildProfile(
        assetsDir = root.text("assets")?.let { base.resolvePath(it).absolutePath },
        piInclude = root.textList("include") ?: DEFAULT_PI_INCLUDE,
        piExclude = root.textList("exclude").orEmpty(),
        agentSourceDir = agentSourceDir,
        agentAbi = agent?.textList("abi") ?: listOf("*"),
        agentRuntimes = agentRuntimes,
        appId = app?.text("id"),
        appLabel = app?.text("label"),
        appIcon = app?.text("icon")?.let { base.resolvePath(it) },
        mirrorchyanRid = update?.text("mirrorchyanRid")?.requireMirrorchyanRid(),
    )
}

private fun Map<*, *>.toAgentRuntime(): AgentRuntime {
    val location = text("location")
    require(location in AGENT_LOCATIONS) {
        "agent.runtimes[].location must be one of $AGENT_LOCATIONS, got ${location ?: "nothing"}"
    }
    return AgentRuntime(
        location = location!!,
        executable = requireNotNull(text("executable")) { "agent.runtimes[].executable is required" },
        args = textList("args").orEmpty(),
        env = child("env")?.entries
            ?.associate { (key, value) -> key.toString() to value.scalar().orEmpty() }
            .orEmpty(),
    )
}

/** The exact bytes that land in assets/agent/agent-runtime.json, see AgentRuntimeDescriptor */
internal fun List<AgentRuntime>.toDescriptorJson(): String = descriptorJson.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        putJsonArray("runtimes") {
            this@toDescriptorJson.forEach { runtime ->
                addJsonObject {
                    put("location", runtime.location)
                    put("executable", runtime.executable)
                    if (runtime.args.isNotEmpty()) {
                        putJsonArray("args") { runtime.args.forEach { add(it) } }
                    }
                    if (runtime.env.isNotEmpty()) {
                        putJsonObject("env") { runtime.env.forEach { (key, value) -> put(key, value) } }
                    }
                }
            }
        }
    },
)

private fun File.resolvePath(path: String): File =
    File(path).let { if (it.isAbsolute) it else File(this, path) }

/**
 * YAML hands back Int, Boolean and friends for unquoted scalars; a profile only ever wants text out
 * of them, so anything that is not a collection is read as its literal form
 */
private fun Any?.scalar(): String? = when (this) {
    null, is Map<*, *>, is List<*> -> null
    else -> toString().expandEnv()
}

private fun Map<*, *>.text(key: String): String? = this[key].scalar()?.takeIf { it.isNotBlank() }

private fun Map<*, *>.textList(key: String): List<String>? =
    (this[key] as? List<*>)?.mapNotNull { it.scalar() }?.filter { it.isNotBlank() }

/** It ends up in a BuildConfig string literal, where a quote breaks the generated source instead */
private fun String.requireMirrorchyanRid(): String {
    // 92 is the backslash; isISOControl covers the line breaks
    require(none { it == '"' || it.code == 92 || it.isISOControl() }) {
        "update.mirrorchyanRid must not contain quotes, backslashes or control characters: $this"
    }
    return this
}

private fun Map<*, *>.child(key: String): Map<*, *>? = this[key] as? Map<*, *>

private fun Map<*, *>.children(key: String): List<Map<*, *>>? =
    (this[key] as? List<*>)?.filterIsInstance<Map<*, *>>()

private val ENV_PLACEHOLDER = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}""")

/**
 * Every string a profile yields goes through this, so a checked-in profile can leave the machine
 * specific parts (paths, labels, ids) to the environment instead of baking them in
 *
 * An unset ${NAME} without a :-fallback fails the build rather than expanding to nothing: a package
 * name or label with a hole in it installs and ships just fine, which is how it goes unnoticed
 */
private fun String.expandEnv(): String = ENV_PLACEHOLDER.replace(this) { match ->
    val name = match.groupValues[1]
    System.getenv(name)?.takeIf { it.isNotEmpty() }
        ?: match.groups[2]?.value
        ?: throw IllegalStateException("\${$name} is referenced but that environment variable is not set")
}
