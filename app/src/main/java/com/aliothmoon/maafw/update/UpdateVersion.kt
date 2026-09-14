package com.aliothmoon.maafw.update

/**
 * A deliberately small SemVer-like value used by update sources.
 *
 * PI and release tags often omit minor or patch. Those components default to zero. Build metadata
 * is parsed but intentionally omitted from equality and ordering, as required by SemVer.
 */
internal data class UpdateVersion(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val preRelease: List<String>,
) : Comparable<UpdateVersion> {
    override fun compareTo(other: UpdateVersion): Int {
        compareValuesBy(
            this,
            other,
            { it.major },
            { it.minor },
            { it.patch },
        ).let { if (it != 0) return it }

        // A release version outranks any prerelease.
        if (preRelease.isEmpty() && other.preRelease.isNotEmpty()) return 1
        if (preRelease.isNotEmpty() && other.preRelease.isEmpty()) return -1

        val length = minOf(preRelease.size, other.preRelease.size)
        for (index in 0 until length) {
            val left = preRelease[index]
            val right = other.preRelease[index]
            val leftNumeric = left.toLongOrNull()
            val rightNumeric = right.toLongOrNull()
            val compared = when {
                leftNumeric != null && rightNumeric != null -> leftNumeric.compareTo(rightNumeric)
                leftNumeric != null -> -1
                rightNumeric != null -> 1
                else -> left.compareTo(right, ignoreCase = true)
            }
            if (compared != 0) return compared
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    fun allowedFor(channel: UpdateChannel): Boolean = when (channel) {
        UpdateChannel.STABLE -> preRelease.isEmpty()
        UpdateChannel.BETA -> preRelease.isEmpty() ||
                preRelease.firstOrNull()?.contains("beta", ignoreCase = true) == true
    }

    companion object {
        private val pattern = Regex(
            """^[vV]?(?<major>\d+)(?:\.(?<minor>\d+))?(?:\.(?<patch>\d+))?""" +
                    """(?:-(?<pre>[0-9A-Za-z.-]+))?(?:\+(?<build>[0-9A-Za-z.-]+))?$""",
        )

        fun parse(raw: String): UpdateVersion? {
            val match = pattern.matchEntire(raw.trim()) ?: return null
            val pre = match.groups["pre"]?.value
                ?.split('.')
                ?.filter(String::isNotEmpty)
                .orEmpty()
            return UpdateVersion(
                major = match.groups["major"]!!.value.toLong(),
                minor = match.groups["minor"]?.value?.toLong() ?: 0,
                patch = match.groups["patch"]?.value?.toLong() ?: 0,
                preRelease = pre,
            )
        }
    }
}
