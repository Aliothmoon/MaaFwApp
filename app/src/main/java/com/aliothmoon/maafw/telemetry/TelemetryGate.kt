package com.aliothmoon.maafw.telemetry

/**
 * PI 版本是不是开发态；开发态一律不上报，免得把调试数据混进 PI 作者的看板
 *
 * 判据对齐 MXU 的 `isDebugVersion`（`src/services/updateService.ts`）：
 * `DEBUG_VERSION`、1.0.0 以下、以及 beta / rc 之外的预发布标签都算
 */
fun isDebugProjectVersion(version: String?): Boolean {
    if (version.isNullOrBlank()) return false
    if (version == DEBUG_VERSION) return true

    val match = SEMVER.find(version.removePrefix("v").removePrefix("V")) ?: return false
    // 1.0.0 之下只可能是 major 为 0
    if (match.groupValues[1].toIntOrNull() == 0) return true

    val prerelease = match.groupValues[4].takeIf { it.isNotEmpty() } ?: return false
    return prerelease.split('.').none { it in UPDATEABLE_TAGS }
}

private const val DEBUG_VERSION = "DEBUG_VERSION"
private val UPDATEABLE_TAGS = setOf("beta", "rc")
private val SEMVER = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.\-]+))?""")
