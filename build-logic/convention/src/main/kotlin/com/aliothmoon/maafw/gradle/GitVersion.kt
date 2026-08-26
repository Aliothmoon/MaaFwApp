package com.aliothmoon.maafw.gradle

import org.gradle.api.Project
import java.io.File

/**
 * A standalone checkout versions itself. When this checkout is a submodule, walk through any
 * nested superprojects and version from the outermost repository instead.
 */
private fun Project.versionGitWorkingDir(): File {
    var workingDir = rootProject.projectDir
    while (true) {
        val superproject = providers.exec {
            workingDir(workingDir)
            commandLine("git", "rev-parse", "--show-superproject-working-tree")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
        if (superproject.isEmpty()) return workingDir
        workingDir = File(superproject)
    }
}

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

/**
 * Short SHA of HEAD for the given working directory; empty string on failure
 *
 * Used both for the app's own commit and for an outer superproject when MaaFwApp sits as a
 * submodule. Kept lenient: a missing repo or detached-but-empty HEAD degrades to "", which the
 * renderer turns into "(not a submodule)" / "(unknown)" rather than failing the build
 */
internal fun Project.gitHeadShort(workingDir: File): String =
    providers.exec {
        workingDir(workingDir)
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()

/**
 * Tag pointing exactly at HEAD in the given working directory; empty string when HEAD is not
 * on any tag. `--exact-match` keeps this distinct from `gitVersionName` which reaches for the
 * nearest reachable tag with distance
 */
internal fun Project.gitHeadExactTag(workingDir: File): String =
    providers.exec {
        workingDir(workingDir)
        commandLine("git", "describe", "--tags", "--exact-match")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()

/** Short SHA of MaaFwApp's own HEAD; always available when the checkout is a git repo */
internal fun Project.gitOwnHeadShort(): String = gitHeadShort(rootProject.projectDir)

/** Tag exactly at MaaFwApp's own HEAD; empty string when no tag points at HEAD */
internal fun Project.gitOwnHeadExactTag(): String = gitHeadExactTag(rootProject.projectDir)

/**
 * Short SHA of the outermost superproject's HEAD; empty string when this checkout is not a
 * submodule. Reuses [versionGitWorkingDir] to walk up the parent chain
 */
internal fun Project.gitParentHeadShort(): String {
    val parent = versionGitWorkingDir()
    return if (parent == rootProject.projectDir) "" else gitHeadShort(parent)
}

/**
 * Tag exactly at the outermost superproject's HEAD; empty string when this checkout is not a
 * submodule or the parent's HEAD has no tag
 */
internal fun Project.gitParentHeadExactTag(): String {
    val parent = versionGitWorkingDir()
    return if (parent == rootProject.projectDir) "" else gitHeadExactTag(parent)
}
