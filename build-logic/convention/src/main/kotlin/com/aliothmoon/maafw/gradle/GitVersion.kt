package com.aliothmoon.maafw.gradle

import org.gradle.api.Project
import java.io.File

/**
 * A standalone checkout versions itself. When this checkout is a submodule, walk through any
 * nested superprojects and version from the outermost repository instead.
 */
private fun Project.versionGitWorkingDir(): File {
    // Four callers each walked the whole chain, and every `git rev-parse` is a process spawn.
    // Kept on the root project extras so the cache dies with the build, not with the daemon
    val extras = rootProject.extensions.extraProperties
    (extras.properties[GIT_WORKING_DIR_KEY] as? File)?.let { return it }

    var workingDir = rootProject.projectDir
    while (true) {
        val superproject = providers.exec {
            workingDir(workingDir)
            commandLine("git", "rev-parse", "--show-superproject-working-tree")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
        if (superproject.isEmpty()) break
        workingDir = File(superproject)
    }
    extras.set(GIT_WORKING_DIR_KEY, workingDir)
    return workingDir
}

private const val GIT_WORKING_DIR_KEY = "maafw.gitWorkingDir"

/** versionCode counts commits in the selected version repo; the build fails without a git checkout */
internal fun Project.gitVersionCode(): Int {
    val gitWorkingDir = versionGitWorkingDir()
    return providers.exec {
        workingDir(gitWorkingDir)
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}

/**
 * A tag on HEAD gives x.y.z; a tag further back bumps patch by one and appends alpha.<distance>
 * A describe output that does not match degrades to itself instead of blocking the build
 */
internal fun Project.gitVersionName(): String {
    val gitWorkingDir = versionGitWorkingDir()
    val desc = providers.exec {
        workingDir(gitWorkingDir)
        commandLine("git", "describe", "--tags", "--always")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
    val match = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-(\d+)-g[0-9a-f]+)?$""").matchEntire(desc)
        ?: return desc.removePrefix("v").ifEmpty { "0.0.0-dev" }
    val (major, minor, patch, distance) = match.destructured
    return if (distance.isEmpty()) "$major.$minor.$patch"
    else "$major.$minor.${patch.toInt() + 1}-alpha.$distance"
}

/** Empty string on failure rather than a build error; the app side renders that as "(unknown)" */
internal fun Project.gitHeadShort(workingDir: File): String =
    providers.exec {
        workingDir(workingDir)
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()

/** `--exact-match` unlike [gitVersionName], which reaches for the nearest reachable tag */
internal fun Project.gitHeadExactTag(workingDir: File): String =
    providers.exec {
        workingDir(workingDir)
        commandLine("git", "describe", "--tags", "--exact-match")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()

internal fun Project.gitOwnHeadShort(): String = gitHeadShort(rootProject.projectDir)

internal fun Project.gitOwnHeadExactTag(): String = gitHeadExactTag(rootProject.projectDir)

/** Empty string when this checkout is not a submodule */
internal fun Project.gitParentHeadShort(): String {
    val parent = versionGitWorkingDir()
    return if (parent == rootProject.projectDir) "" else gitHeadShort(parent)
}

internal fun Project.gitParentHeadExactTag(): String {
    val parent = versionGitWorkingDir()
    return if (parent == rootProject.projectDir) "" else gitHeadExactTag(parent)
}
