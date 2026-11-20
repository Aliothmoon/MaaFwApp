package com.aliothmoon.maafw.update

import java.util.Locale

/** 下载速度：取最近一个窗口内的增量，比单个采样间隔稳 */
internal class TransferRateMeter(
    private val windowMillis: Long = 3_000,
    /** 开头采样跨度太短算出来的数忽大忽小，不报 */
    private val minSpanMillis: Long = 1_000,
) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** 还算不出时返回 [UNKNOWN] */
    fun sample(nowMillis: Long, bytes: Long): Long {
        samples.addLast(nowMillis to bytes)
        // 留下的最早一份仍在窗口外沿上，算出来的跨度不短于窗口
        while (samples.size > 2 && nowMillis - samples[1].first >= windowMillis) samples.removeFirst()
        val (startMillis, startBytes) = samples.first()
        val span = nowMillis - startMillis
        if (span < minSpanMillis) return UNKNOWN
        return (bytes - startBytes).coerceAtLeast(0) * 1000 / span
    }

    companion object {
        const val UNKNOWN = -1L
    }
}

internal fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= MIB -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond.toDouble() / MIB)
    bytesPerSecond >= KIB -> String.format(Locale.US, "%.1f KB/s", bytesPerSecond.toDouble() / KIB)
    else -> "$bytesPerSecond B/s"
}

private const val KIB = 1024L
private const val MIB = 1024L * 1024
