package com.aliothmoon.maafw.maa

/**
 * MaaVersion() 返回串的比较与 bridge 合约能力判定
 *
 * 解析不了的一律按「不支持」处理：最坏退化为单指 contact 0，与旧 bridge 行为等价，
 * 不会回到读旧 fw 从未写过的合约字段
 */
object MaaFwVersion {

    /** 开始填充 bridge 合约 TouchArgs.contact 的 fw 版本；上游钉出更早版本可下调 */
    private const val MIN_CONTACT_FILL = "5.12.3"

    private val NUMERIC = Regex("""\s*v?(\d+)\.(\d+)\.(\d+)\s*""")

    fun fillsTouchContact(raw: String?): Boolean = atLeast(raw, MIN_CONTACT_FILL)

    fun atLeast(raw: String?, min: String): Boolean {
        // 全串匹配才认：带 -beta/-rc 后缀的预发布按「低于其所标版本」处理
        val m = NUMERIC.matchEntire(raw ?: return false) ?: return false
        val cur = m.groupValues.drop(1).map(String::toInt)
        val floor = min.split('.').map(String::toInt)
        for (i in cur.indices) {
            if (cur[i] != floor[i]) return cur[i] > floor[i]
        }
        return true
    }
}
