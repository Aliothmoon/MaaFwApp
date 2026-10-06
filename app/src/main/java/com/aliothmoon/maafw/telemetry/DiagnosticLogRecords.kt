package com.aliothmoon.maafw.telemetry

import io.sentry.SentryLogEvent
import io.sentry.SentryLogLevel
import io.sentry.SpanId
import io.sentry.protocol.SentryId

/** 一条要发的 Sentry Log；一份日志尾巴切成若干条 */
internal class DiagnosticLogRecord(
    val level: SentryLogLevel,
    val body: String,
    val attributes: Map<String, Any>,
)

/** 把日志记录挂回失败事件所需的那几样 */
internal class DiagnosticLogContext(
    val eventId: String,
    val runId: String,
    val taskId: Long?,
    val task: String,
    val failureNode: String,
    val failureStage: String,
    val traceId: String?,
    val spanId: String?,
)

/**
 * 属性与 MXU `build_diagnostic_log_records` 同名：在 Sentry 里按 `sentry.event_id` 或 `run.id`
 * 就能把一条失败事件的全部日志捞出来，按 `log.chunk_index` 拼回原样
 */
internal fun DiagnosticLog.toRecords(context: DiagnosticLogContext): List<DiagnosticLogRecord> {
    val texts = mapOf(
        "diagnostic.reason" to "task_failure",
        "diagnostic.source" to source,
        "diagnostic.kind" to kind,
        "sentry.event_id" to context.eventId,
        "run.id" to context.runId,
        "task.name" to context.task,
        "failure.node" to context.failureNode,
        "failure.stage" to context.failureStage,
    )
    val attributes = buildMap<String, Any> {
        texts.forEach { (key, value) -> put(key, boundedAttribute(value)) }
        if (texts.any { (_, value) -> boundedAttribute(value) != value }) put("diagnostic.attributes_truncated", true)
        context.taskId?.let { put("task.id", it) }
        put("log.raw_bytes", rawBytes)
        context.traceId?.let { put(TRACE_ID_ATTRIBUTE, it) }
        context.spanId?.let { put(SPAN_ID_ATTRIBUTE, it) }
    }
    val chunks = chunkLogBody(content, bodyBudget(attributes))
    return chunks.mapIndexed { index, chunk ->
        DiagnosticLogRecord(
            level = logLevelOf(chunk),
            body = chunk,
            attributes = attributes + mapOf("log.chunk_index" to index, "log.chunk_count" to chunks.size),
        )
    }
}

/**
 * Relay 对一个 logs 信封项只收 1 MiB，SDK 攒够 100 条就发、不看字节数。
 * 一条记录序列化后卡在 7 KiB 以内，与 MXU 同一个数，满一批也超不了；属性占掉的从正文里扣
 */
private fun bodyBudget(attributes: Map<String, Any>): Int {
    val own = attributes.entries.sumOf { (key, value) ->
        key.length + ATTRIBUTE_FRAMING_BYTES + value.toString().sumOf { serializedSize(it.code) }
    }
    return (MAX_RECORD_BYTES - SDK_ATTRIBUTES_BYTES - own).coerceAtLeast(MIN_LOG_BODY_BYTES)
}

/** 按序列化后的字节数切，不在字符中间下刀 */
internal fun chunkLogBody(content: String, budget: Int): List<String> {
    val chunks = mutableListOf<String>()
    var start = 0
    var cost = 0
    var index = 0
    while (index < content.length) {
        val codePoint = content.codePointAt(index)
        val size = serializedSize(codePoint)
        if (cost + size > budget && index > start) {
            chunks += content.substring(start, index)
            start = index
            cost = 0
        }
        cost += size
        index += Character.charCount(codePoint)
    }
    if (start < content.length) chunks += content.substring(start)
    return chunks
}

/**
 * 日志记录的 trace 默认取 scope 上的传播上下文，与失败事件不在一条 Trace 上；
 * 按 [toRecords] 写进属性的那两个 id 改回去，记录才会出现在那一轮的 Trace 里
 */
internal fun SentryLogEvent.bindDiagnosticTrace() {
    val attributes = attributes ?: return
    runCatching {
        (attributes[TRACE_ID_ATTRIBUTE]?.value as? String)?.let { traceId = SentryId(it) }
        (attributes[SPAN_ID_ATTRIBUTE]?.value as? String)?.let { spanId = SpanId(it) }
    }
}

/** 一个字符写进 JSON 字符串后占几个字节，按转义后的最坏情况算 */
private fun serializedSize(codePoint: Int): Int = when {
    codePoint == '"'.code || codePoint == '\\'.code -> 2
    codePoint < 0x20 -> if (codePoint in SHORT_ESCAPES) 2 else 6
    codePoint == 0x2028 || codePoint == 0x2029 -> 6
    codePoint < 0x80 -> 1
    codePoint < 0x800 -> 2
    codePoint < 0x10000 -> 3
    else -> 4
}

private fun boundedAttribute(value: String): String =
    value.map { if (it.isISOControl()) '�' else it }.take(MAX_ATTRIBUTE_CHARACTERS).joinToString("")

/** MaaFramework 与外壳的日志都把级别写成方括号里的缩写 */
private fun logLevelOf(content: String): SentryLogLevel {
    val upper = content.uppercase()
    return when {
        "[FTL]" in upper || "[FATAL]" in upper -> SentryLogLevel.FATAL
        "[ERR]" in upper || "[ERROR]" in upper -> SentryLogLevel.ERROR
        "[WRN]" in upper || "[WARN]" in upper -> SentryLogLevel.WARN
        "[DBG]" in upper || "[DEBUG]" in upper -> SentryLogLevel.DEBUG
        else -> SentryLogLevel.INFO
    }
}

private const val MAX_RECORD_BYTES = 7 * 1024
private const val MIN_LOG_BODY_BYTES = 1024

/** 一个属性在 JSON 里除了键和值之外的那圈壳：`"k":{"type":"string","value":"v"},` */
private const val ATTRIBUTE_FRAMING_BYTES = 40

/** SDK 自己往每条记录上加的那些（用户 ID、release、environment、设备与系统），留足余量 */
private const val SDK_ATTRIBUTES_BYTES = 1024
private const val MAX_ATTRIBUTE_CHARACTERS = 200
private const val TRACE_ID_ATTRIBUTE = "trace.id"
private const val SPAN_ID_ATTRIBUTE = "span.id"

/** `\b \t \n \f \r`，JSON 里写成两个字符 */
private val SHORT_ESCAPES = setOf(0x08, 0x09, 0x0A, 0x0C, 0x0D)
