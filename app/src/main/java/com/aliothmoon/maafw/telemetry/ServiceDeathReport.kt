package com.aliothmoon.maafw.telemetry

import com.aliothmoon.maafw.privileged.ServiceProcessDeath
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.Message

internal data class ServiceDeathIncident(
    val death: ServiceProcessDeath,
    val runId: String?,
)

internal fun ServiceDeathIncident.toSentryEvent(): SentryEvent = SentryEvent().also { event ->
    val backend = death.backend?.name?.lowercase() ?: "unknown"
    val pid = death.pid?.toString() ?: "unknown"
    event.level = SentryLevel.ERROR
    event.logger = SERVICE_LOGGER
    event.transaction = SERVICE_DEATH_TRANSACTION
    event.message = Message().apply {
        formatted = "Privileged service process died: backend=$backend pid=$pid"
    }
    event.fingerprints = listOf(SERVICE_FINGERPRINT, backend)
    event.setTag("service.backend", backend)
    event.setTag("service.pid", pid)
    event.setTag("result", "service_died")
    runId?.let { event.setTag("run.id", it) }
    event.setExtra("service.died_at_ms", death.diedAtMs)
    death.pid?.let { event.setExtra("service.pid", it) }
}

/**
 * 系统日志正文走 Sentry Logs，用原事件 ID 关联；事件本身只留取证状态，避免正文进普通的错误详情。
 */
internal fun serviceDeathLogRecords(
    content: String,
    pending: ServiceDeathForensics.Pending,
    connectedPid: Int,
    delayMs: Long,
): List<DiagnosticLogRecord> {
    val attributes = buildMap<String, Any> {
        put("diagnostic.reason", "service_death")
        put("diagnostic.source", SYSTEM_LOG_SOURCE)
        put("diagnostic.kind", "system")
        put("sentry.event_id", pending.eventId)
        pending.runId?.let { put("run.id", it) }
        pending.backend?.let { put("service.backend", it) }
        put("service.pid", pending.pid)
        put("service.connected_pid", connectedPid)
        put("forensics.delay_s", (delayMs / 1000).toString())
        put("log.raw_bytes", content.toByteArray().size.toLong())
    }
    val chunks = chunkLogBody(content, SERVICE_LOG_BODY_BUDGET)
    return chunks.mapIndexed { index, chunk ->
        DiagnosticLogRecord(
            level = logLevelOf(chunk),
            body = chunk,
            attributes = attributes + mapOf(
                "log.chunk_index" to index,
                "log.chunk_count" to chunks.size,
            ),
        )
    }
}

internal const val SERVICE_DEATH_TRANSACTION = "maafwapp.service.died"
internal const val SYSTEM_LOG_SOURCE = "logcat/system"

private const val SERVICE_LOGGER = "maafwapp.service"
private const val SERVICE_FINGERPRINT = "maafwapp-service-died"
private const val SERVICE_LOG_BODY_BUDGET = 6 * 1024
