package com.aliothmoon.maafw.gradle

import org.gradle.api.Project

/** versionCode counts commits in this repo; the build needs a working git checkout and fails without one */
internal fun Project.gitVersionCode(): Int =
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()

/**
 * A tag on HEAD gives x.y.z; a tag further back bumps patch by one and appends alpha.<distance>
 * A describe output that does not match degrades to itself instead of blocking the build
 */
internal fun Project.gitVersionName(): String {
    val desc = providers.exec {
        commandLine("git", "describe", "--tags", "--always")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
    val match = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-(\d+)-g[0-9a-f]+)?$""").matchEntire(desc)
        ?: return desc.removePrefix("v").ifEmpty { "0.0.0-dev" }
    val (major, minor, patch, distance) = match.destructured
    return if (distance.isEmpty()) "$major.$minor.$patch"
    else "$major.$minor.${patch.toInt() + 1}-alpha.$distance"
}
