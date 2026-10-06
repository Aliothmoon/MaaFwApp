package com.aliothmoon.maafw.log

import com.aliothmoon.maafw.domain.SECRET_MASK

/**
 * 日志离开设备前把 PI password 明文换成掩码
 *
 * MaaFramework 的 `MaaTaskerPostTask` 会把替换后的整份 pipeline_override 写进框架日志 `log/maafw.log`，
 * 外壳拦不住，只能在往外交的那一步补：导出打包、遥测上传都走这里
 */
internal object SecretRedaction {

    /** 一两个字符的串在日志里到处都是，替换掉会把整份日志毁了；更短的密码不打码 */
    private const val MIN_REDACT_LENGTH = 4

    /** 长的先换：一个密码是另一个的子串时，先换短的会留下长的那截尾巴 */
    fun redactable(secrets: Collection<String>): List<String> =
        secrets.filter { it.length >= MIN_REDACT_LENGTH }.distinct().sortedByDescending { it.length }

    /** [secrets] 须先过 [redactable] */
    fun redact(text: String, secrets: List<String>): String =
        secrets.fold(text) { current, secret -> current.replace(secret, SECRET_MASK) }
}
