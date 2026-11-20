package com.aliothmoon.maafw.remote.internal

/** 同一条 SELinux 拒绝只留首条，部分 ROM 每秒刷数条，占 App 侧 logcat 八成以上 */
internal class LogcatNoiseFilter(private val maxKeys: Int = 256) {

    private val seen = LinkedHashSet<String>()

    fun accept(line: String): Boolean {
        val at = line.indexOf(AVC_DENIED)
        if (at < 0) return true
        // 审计序号在标记之前，之后的内容固定
        val key = line.substring(at)
        if (!seen.add(key)) return false
        if (seen.size > maxKeys) seen.remove(seen.first())
        return true
    }

    private companion object {
        const val AVC_DENIED = "avc:  denied"
    }
}
