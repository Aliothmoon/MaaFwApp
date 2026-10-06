package com.aliothmoon.maafw.update

import io.github.z4kn4fein.semver.Version
import io.github.z4kn4fein.semver.toVersionOrNull

/** 版本解析、比较与预发布判定一律按 SemVer 2.0.0，不自定义规则 */
internal object UpdateVersion {

    /** 宽松模式：release tag 与 PI 版本常带 `v` 前缀、省略 minor/patch */
    fun parse(raw: String): Version? = raw.trim().toVersionOrNull(strict = false)
}

/** stable 只收正式版；beta 收任意预发布（SemVer 不区分 alpha / beta / rc） */
internal fun Version.allowedFor(channel: UpdateChannel): Boolean = when (channel) {
    UpdateChannel.STABLE -> !isPreRelease
    UpdateChannel.BETA -> true
}
