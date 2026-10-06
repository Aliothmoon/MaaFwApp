package com.aliothmoon.maafw.gradle

import java.io.File
import java.util.Locale

/** Profile paths resolve against the profile directory and keep its ".." segments */
private fun String.normalizedPath(): String = File(this).toPath().normalize().toString()

/** The package kind the updater will stick to, with the ABIs behind a universal one */
private fun List<String>.packageText(): String =
    packageAbi(this).let { if (size > 1) "$it (${joinToString()})" else it }

/** Sizes in the build log read in MB, the scale the package and its parts actually sit at */
internal fun Long.toSizeText(): String = String.format(Locale.ROOT, "%.1f MB", this / (1024.0 * 1024.0))

/**
 * What a build is about to package, printed once at configuration
 * Everything here is otherwise spread over local.properties, the profile and the environment,
 * and a wrong one of them only shows up once the app is on a device
 */
internal fun buildSummary(
    profilePath: String?,
    profile: BuildProfile,
    versionName: String?,
    versionCode: Int?,
    frameworkVersion: String,
    debugAbis: List<String>,
    releaseAbis: List<String>,
    releaseSigned: Boolean,
): String {
    val rows = buildList {
        add("app" to listOfNotNull(profile.appLabel, "$versionName ($versionCode)").joinToString("  "))
        add("profile" to (profilePath ?: "none, the package ships without a PI"))
        if (profilePath != null) {
            add("PI" to "${profile.assetsDir?.normalizedPath() ?: "none"}  include ${profile.piInclude.size}, exclude ${profile.piExclude.size}")
            val runtimes = profile.agentRuntimes.joinToString { it.name ?: File(it.executable).name }
            add("agent" to (profile.agentSourceDir?.let { "${it.normalizedPath()}  $runtimes" } ?: "none"))
        }
        add("MaaFramework" to frameworkVersion.ifEmpty { "unknown, scripts/setup_maa_framework.py has not run" })
        add("ABI" to "debug ${debugAbis.packageText()}  release ${releaseAbis.packageText()}")
        add("signing" to if (releaseSigned) "release keystore configured" else "no keystore, release stays unsigned")
    }
    val width = rows.maxOf { it.first.length }
    return rows.joinToString("\n", prefix = "MaaFwApp build\n") { (key, value) -> "  ${key.padEnd(width)}  $value" }
}
