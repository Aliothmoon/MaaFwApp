package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.util.JsonUtils
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 系统记下的一次进程退出；App 查自己、提权进程查游戏，两边共用同一条线格式 */
@Serializable
data class ProcessExitRecord(
    val timestamp: Long,
    val pid: Int,
    val processName: String,
    val reason: Int,
    val reasonName: String,
    val detail: Int,
    val detailName: String,
    val status: Int,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
    val description: String? = null,
)

internal enum class ProcessExitSubject(val label: String) {
    SELF("self"),
    GAME("game"),
}

/** 退出记录的去重、分级与格式化；不碰 Android API，JVM 单测可直接覆盖 */
internal object ProcessExitLog {
    const val MAX_RECORDS = 16

    /** 首次没有水位时只回溯最近几条，避免升级后把整份历史倒进日志 */
    const val FIRST_RUN_BACKFILL = 3

    private const val REASON_EXIT_SELF = 1
    private const val REASON_USER_REQUESTED = 10
    private const val REASON_PACKAGE_UPDATED = 16

    private val FORCE_STOP_BY_CALLER = Regex(""" due to from (pid |process:)\d+""")
    private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.ROOT)

    private val IMPORTANCE = mapOf(
        100 to "FOREGROUND",
        125 to "FOREGROUND_SERVICE",
        200 to "VISIBLE",
        230 to "PERCEPTIBLE",
        300 to "SERVICE",
        325 to "TOP_SLEEPING",
        350 to "CANT_SAVE_STATE",
        400 to "CACHED",
        1000 to "GONE",
    )

    data class Watermark(val timestamp: Long, val pid: Int)

    private val ORDER = compareBy<ProcessExitRecord>({ it.timestamp }, { it.pid })

    /** 水位之后的记录按时间正序返回；同一毫秒内再用 pid 去重 */
    fun selectNew(records: List<ProcessExitRecord>, watermark: Watermark?): List<ProcessExitRecord> {
        val sorted = records.sortedWith(ORDER)
        if (watermark == null) return sorted.takeLast(FIRST_RUN_BACKFILL)
        return sorted.filter {
            it.timestamp > watermark.timestamp ||
                (it.timestamp == watermark.timestamp && it.pid > watermark.pid)
        }
    }

    /** 覆盖安装、正常退出、任务主动重启游戏都属于预期内，只记 INFO */
    fun isExpected(subject: ProcessExitSubject, record: ProcessExitRecord): Boolean = when {
        record.reason == REASON_PACKAGE_UPDATED -> true
        record.reason == REASON_USER_REQUESTED &&
            record.description?.contains("installPackageLI") == true -> true
        record.reason == REASON_EXIT_SELF && record.status == 0 -> true
        subject == ProcessExitSubject.GAME &&
            record.reason == REASON_USER_REQUESTED &&
            record.description?.let(FORCE_STOP_BY_CALLER::containsMatchIn) == true -> true

        else -> false
    }

    fun format(
        subject: ProcessExitSubject,
        record: ProcessExitRecord,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        append("Process exit [").append(subject.label).append("] ").append(record.processName)
        append(" pid=").append(record.pid)
        append(" at ").append(TIME.format(Instant.ofEpochMilli(record.timestamp).atZone(zone)))
        append(" reason=").append(record.reasonName.ifEmpty { "?" })
        append('(').append(record.reason).append(')')
        if (record.detail != 0) {
            append(" detail=").append(record.detailName.ifEmpty { "?" })
            append('(').append(record.detail).append(')')
        }
        append(" status=").append(record.status)
        append(" importance=").append(IMPORTANCE[record.importance] ?: "?")
        append('(').append(record.importance).append(')')
        if (record.pssKb > 0) append(" pss=").append(mb(record.pssKb))
        if (record.rssKb > 0) append(" rss=").append(mb(record.rssKb))
        record.description?.takeIf { it.isNotBlank() }?.let {
            append(" desc=\"").append(it).append('"')
        }
    }

    private fun mb(kb: Long): String = String.format(Locale.ROOT, "%.1fMB", kb / 1024.0)
}

/** AIDL 只传字符串，避免 Parcelable 类布局随外壳升级与旧特权进程错位 */
internal object ProcessExitWire {
    fun encode(records: List<ProcessExitRecord>): String =
        JsonUtils.common.encodeToString(records)

    fun decode(raw: String): List<ProcessExitRecord> =
        runCatching { JsonUtils.common.decodeFromString<List<ProcessExitRecord>>(raw) }
            .getOrDefault(emptyList())
}
