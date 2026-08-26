package com.aliothmoon.maafw.log

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 日志导出时的设备快照；字段先采集成字符串，渲染保持纯函数便于单测 */
data class DeviceInfo(
    val exportTime: ZonedDateTime,
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val buildType: String,
    val gitCommit: String,
    val gitTag: String,
    val parentGitCommit: String,
    val parentGitTag: String,
    val device: String,
    val android: String,
    val securityPatch: String,
    val abi: String,
    val screen: String,
    val memory: String,
    val storage: String,
    val batteryOptimization: String,
    val selinux: String,
)

object DeviceInfoText {

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS (Z)", Locale.US)

    fun render(info: DeviceInfo): String = buildString {
        val line = "=".repeat(60)
        append(line).append('\n')
        append("=== MaaFwApp Device & App Info ===\n")
        append("Export Time : ").append(info.exportTime.format(TIME_FORMAT)).append('\n')
        append("App         : ").append(info.applicationId).append('\n')
        append("Version     : ").append(info.versionName)
            .append(" (").append(info.versionCode).append(") ")
            .append(info.buildType).append('\n')
        append(formatGitLine("Git (MaaFwApp)", info.gitTag, info.gitCommit)).append('\n')
        append(
            formatGitLine(
                "Git (Parent)",
                info.parentGitTag,
                info.parentGitCommit,
                notSubmodule = info.parentGitCommit.isEmpty(),
            ),
        ).append('\n')
        append("Device      : ").append(info.device).append('\n')
        append("Android     : ").append(info.android).append('\n')
        append("Security    : ").append(info.securityPatch).append('\n')
        append("ABI         : ").append(info.abi).append('\n')
        append("--- Device ---\n")
        append("Screen      : ").append(info.screen).append('\n')
        append("RAM         : ").append(info.memory).append('\n')
        append("Storage     : ").append(info.storage).append('\n')
        append("Battery Opt : ").append(info.batteryOptimization).append('\n')
        append("SELinux     : ").append(info.selinux).append('\n')
        append(line).append('\n')
    }

    /**
     * Render a single `Git (...)` line shared by [render], `AppLogWriter.setup()` and
     * `CrashHandler.report()` so the three places never drift apart.
     *
     * Empty `commit` and `notSubmodule == true` -> the literal "(not a submodule)" marker;
     * non-empty `tag` -> "tag (commit)"; otherwise the bare short hash.
     */
    fun formatGitLine(
        label: String,
        tag: String,
        commit: String,
        notSubmodule: Boolean = false,
    ): String = buildString {
        append(label).append(" : ")
        when {
            notSubmodule -> append("(not a submodule)")
            tag.isNotEmpty() -> append(tag).append(" (").append(commit).append(")")
            else -> append(commit)
        }
    }
}
