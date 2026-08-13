package com.aliothmoon.maafw.gradle

import org.gradle.api.Project
import java.util.Properties

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

/**
 * Signing material flips the precedence: a release build injects env vars and a stale
 * local.properties entry must not override them
 */
internal fun Project.signingSetting(envName: String, key: String): String =
    System.getenv(envName) ?: loadLocalProperties().getProperty(key, "")

/** Comma separated list switch, read from local.properties only */
internal fun Project.listSetting(key: String): List<String> =
    (loadLocalProperties().getProperty(key) ?: "").split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
