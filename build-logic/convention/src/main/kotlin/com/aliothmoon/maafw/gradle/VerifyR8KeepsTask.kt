package com.aliothmoon.maafw.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Classes native code and other processes look up by their literal name
 *
 * bridge.cpp holds them as string constants, and the privileged process is started with
 * `app_process --class=<name>`. Renaming one is invisible at build time and only shows up on a
 * device, inside a process without a debugger, possibly only once a run is actually under way
 */
internal val R8_CRITICAL_CLASSES = setOf(
    "com.aliothmoon.maafw.bridge.NativeBridgeLib",
    "com.aliothmoon.maafw.bridge.DriverClass",
    "com.aliothmoon.maafw.maa.MaaFrameworkLibrary",
    "com.aliothmoon.maafw.maa.MaaAgentClientLibrary",
    "com.aliothmoon.maafw.remote.RemoteServiceImpl",
    "com.aliothmoon.maafw.remote.LogcatCaptureServiceImpl",
    "com.aliothmoon.maafw.root.RootServiceStarter",
    "com.aliothmoon.maafw.root.RootUserService",
)

/**
 * Asserts the keep rules for [R8_CRITICAL_CLASSES] still hold
 *
 * R8 omits identity mappings, so a class that appears with a different name on the right-hand
 * side lost its keep rule; a class missing from the mapping altogether was shrunk away
 */
abstract class VerifyR8KeepsTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mapping: RegularFileProperty

    @get:Input
    abstract val criticalClasses: SetProperty<String>

    @TaskAction
    fun verify() {
        val file = mapping.get().asFile
        if (!file.isFile) return

        val renamed = mutableMapOf<String, String>()
        file.forEachLine { line ->
            if (line.startsWith(" ") || !line.endsWith(":")) return@forEachLine
            val parts = line.dropLast(1).split(" -> ")
            if (parts.size == 2) renamed[parts[0]] = parts[1]
        }

        val broken = criticalClasses.get().mapNotNull { name ->
            when (val mapped = renamed[name]) {
                null -> "$name was shrunk away"
                name -> null
                else -> "$name was renamed to $mapped"
            }
        }
        if (broken.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("R8 broke a name that native code or another process looks up literally:")
                    broken.forEach { appendLine("  - $it") }
                    appendLine("Check the keep rules in app/proguard-rules.pro before shipping this.")
                },
            )
        }
        logger.lifecycle("R8 keeps verified: ${criticalClasses.get().size} classes kept under their own name")
    }
}
