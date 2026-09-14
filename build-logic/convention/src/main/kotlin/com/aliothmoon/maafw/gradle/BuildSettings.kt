package com.aliothmoon.maafw.gradle

import org.gradle.api.Project
import java.util.Properties

/** Native ABIs the shell and its bundled MaaFramework/agent runtimes support. */
internal val SHIPPED_ABIS = listOf("arm64-v8a", "x86_64")

private fun Project.loadLocalProperties(): Properties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/**
 * Path switches: local.properties is the developer machine config, the env var is the fallback
 * Blank counts as unset; callers treat that as a soft failure
 */
internal fun Project.pathSetting(key: String, envName: String): String? =
    (loadLocalProperties().getProperty(key) ?: System.getenv(envName))?.takeIf { it.isNotBlank() }

/** Text switches use the same precedence as path switches */
internal fun Project.textSetting(key: String, envName: String): String? =
    (loadLocalProperties().getProperty(key) ?: System.getenv(envName))?.takeIf { it.isNotBlank() }

/**
 * Signing material flips the precedence: a release build injects env vars and a stale
 * local.properties entry must not override them
 */
internal fun Project.signingSetting(envName: String, key: String): String =
    System.getenv(envName) ?: loadLocalProperties().getProperty(key, "")

/**
 * The MaaFramework release the jniLibs were laid out from, written by setup_maa_framework.py
 * Absent until that script has run here, and the about card hides the row rather than guess
 */
internal fun Project.maaFrameworkVersion(): String =
    rootProject.file(".maafwversion").takeIf { it.isFile }?.readText()?.trim().orEmpty()

/** Comma separated list switch, read from local.properties only */
internal fun Project.listSetting(key: String): List<String> =
    (loadLocalProperties().getProperty(key) ?: "").split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)

/**
 * Resolve a build-type ABI setting, preserving the historical universal default while rejecting
 * values for which the shell does not ship native dependencies.
 */
internal fun Project.abiSetting(key: String): List<String> {
    val configured = listSetting(key)
    if (configured.isEmpty()) return SHIPPED_ABIS

    val unsupported = configured.filterNot(SHIPPED_ABIS::contains).distinct()
    require(unsupported.isEmpty()) {
        "$key contains unsupported ABI(s): ${unsupported.joinToString()}; " +
            "supported values are ${SHIPPED_ABIS.joinToString()}"
    }
    return configured.distinct()
}
