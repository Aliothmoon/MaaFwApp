package com.aliothmoon.maafw.maa

/**
 * MaaVersion() 返回串的比较与 bridge 合约能力判定
 *
 * 解析不了的一律按「不支持」处理：最坏退化为单指 contact 0，与旧 bridge 行为等价，
 * 不会回到读旧 fw 从未写过的合约字段
 */
object MaaFwVersion {

    /** AndroidNative 从这个版本起才会填充 bridge 合约的 TouchArgs.contact */
    private const val MIN_CONTACT_FILL = "5.13.0-beta.3"

    private val SEMVER = Regex(
        """\s*v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?""" +
            """(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?\s*""",
    )

    fun fillsTouchContact(raw: String?): Boolean = atLeast(raw, MIN_CONTACT_FILL)

    fun atLeast(raw: String?, min: String): Boolean {
        val current = parse(raw) ?: return false
        val floor = parse(min) ?: return false
        return current >= floor
    }

    private fun parse(raw: String?): SemVer? {
        val match = SEMVER.matchEntire(raw ?: return null) ?: return null
        val core = match.groupValues.drop(1).take(3)
        if (core.any(::hasInvalidLeadingZero)) return null
        val numbers = core.map { it.toLongOrNull() ?: return null }
        val prerelease = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.')
        if (prerelease?.any(::hasInvalidLeadingZero) == true) return null
        return SemVer(numbers[0], numbers[1], numbers[2], prerelease)
    }

    private fun hasInvalidLeadingZero(identifier: String): Boolean =
        identifier.length > 1 && identifier[0] == '0' && identifier.all(Char::isDigit)

    private data class SemVer(
        val major: Long,
        val minor: Long,
        val patch: Long,
        val prerelease: List<String>?,
    ) : Comparable<SemVer> {

        override fun compareTo(other: SemVer): Int {
            major.compareTo(other.major).takeIf { it != 0 }?.let { return it }
            minor.compareTo(other.minor).takeIf { it != 0 }?.let { return it }
            patch.compareTo(other.patch).takeIf { it != 0 }?.let { return it }

            if (prerelease == null) return if (other.prerelease == null) 0 else 1
            if (other.prerelease == null) return -1
            for (index in 0 until minOf(prerelease.size, other.prerelease.size)) {
                compareIdentifier(prerelease[index], other.prerelease[index])
                    .takeIf { it != 0 }
                    ?.let { return it }
            }
            return prerelease.size.compareTo(other.prerelease.size)
        }

        private fun compareIdentifier(left: String, right: String): Int {
            val leftNumeric = left.all(Char::isDigit)
            val rightNumeric = right.all(Char::isDigit)
            return when {
                leftNumeric && rightNumeric ->
                    left.length.compareTo(right.length).takeIf { it != 0 }
                        ?: left.compareTo(right)
                leftNumeric -> -1
                rightNumeric -> 1
                else -> left.compareTo(right)
            }
        }
    }
}
