package com.aliothmoon.maafw.notification.provider

private const val TRUNCATED_MARK = "[...]"

/** `NotificationCenter.appendLogs` 在正文与日志之间放的就是它 */
private const val LOG_SEPARATOR = "\n\n"

/**
 * 裁进渠道上限：保住开头的正文与最近的日志，从中间（最旧的日志行）按行裁
 *
 * 开「附带日志」后正文后面拼着最近若干行运行日志，超出上限的整条会被拒。外壳的顺序是
 * 正文在前、日志在后——与 MaaMeow 相反，照搬它的「只留末尾」会把失败原因裁掉
 */
internal fun String.fitChars(max: Int): String = fit(max, String::length)

/** 同 [fitChars]，按 UTF-8 字节计：Bark 的 APNs 载荷、钉钉的请求体都按字节限 */
internal fun String.fitUtf8(maxBytes: Int): String = fit(maxBytes) { it.encodeToByteArray().size }

private fun String.fit(max: Int, size: (String) -> Int): String {
    if (size(this) <= max) return this
    val split = indexOf(LOG_SEPARATOR)
    if (split < 0) return keepHead(max, size)

    val prefix = substring(0, split) + LOG_SEPARATOR + TRUNCATED_MARK
    var budget = max - size(prefix)
    if (budget <= 0) return keepHead(max, size)
    val kept = ArrayDeque<String>()
    for (line in substring(split + LOG_SEPARATOR.length).lines().asReversed()) {
        val cost = size(line) + 1
        if (cost > budget) break
        budget -= cost
        kept.addFirst(line)
    }
    return prefix + kept.joinToString("") { "\n$it" }
}

/** 正文自己就超了：保开头，末尾打标 */
private fun String.keepHead(max: Int, size: (String) -> Int): String {
    val mark = "\n$TRUNCATED_MARK"
    val budget = max - size(mark)
    var lo = 0
    var hi = length
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (size(substring(0, mid)) <= budget) lo = mid else hi = mid - 1
    }
    // 切点落在代理项对（如 emoji）中间时把孤立的高位代理项丢掉，否则序列化成 JSON 会变成替换字符
    return substring(0, lo).trimEnd { it.isHighSurrogate() } + mark
}
