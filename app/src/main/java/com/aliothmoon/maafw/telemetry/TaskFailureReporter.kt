package com.aliothmoon.maafw.telemetry

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.log.SecretRedaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** 一条失败事件连同它的证据，交给 [TelemetryController] 发出去 */
internal class FailureReport(
    val failure: TaskFailure,
    val appName: String,
    /** null = 没取到证（开跑快照没赶上，或取证本身出了错） */
    val logs: DiagnosticLogs?,
    val attachment: AttachmentOutcome,
)

/**
 * 给失败事件配证据：任务开跑时拍一份快照，失败后在后台读日志尾巴、打包截图，弄完再把事件发出去
 *
 * 流程对齐 MXU `submit_failure_report`。文件 IO 全在后台做，不占事件流那条协程；
 * 取证出任何岔子事件照发，只是不带证据。进程在那一秒多的窗口里被杀则事件丢失——
 * MXU 靠退出钩子兜底，Android 没有可靠的退出时机
 *
 * [onTaskStarted] 与 [report] 由调用方串行调用
 */
internal class TaskFailureReporter(
    private val scope: CoroutineScope,
    private val sources: () -> List<EvidenceSource>,
    /** 当前保存着的 PI password 明文，日志离开设备前换成掩码 */
    private val secrets: suspend () -> Collection<String>,
    private val send: (FailureReport) -> Unit,
) {

    /**
     * 每有任务开跑就加一。截图只在失败任务所属的那一代里补采，
     * 下一个任务一开跑就停手，否则它的出错截图会算到上一个任务头上
     */
    private val epoch = AtomicLong()
    private var workers: Job = SupervisorJob()
    private var pending: Deferred<TaskEvidenceStart>? = null

    fun onTaskStarted() {
        epoch.incrementAndGet()
        pending = scope.async(workers + MaaDispatchers.IO) { TaskEvidence.captureStart(sources()) }
    }

    fun report(failure: TaskFailure, appName: String, attachmentsSampleRate: Double) {
        val start = pending.also { pending = null }
        val taskEpoch = epoch.get()
        val withImages = shouldSampleAttachment(failure.runId, failure.taskId, attachmentsSampleRate)
        scope.launch(workers + MaaDispatchers.IO) {
            val (logs, attachment) = try {
                collect(failure, start, taskEpoch, withImages)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null to AttachmentOutcome.Omitted("build_failed", e.toString())
            }
            // 用户在这当口关了遥测就不该再发
            ensureActive()
            send(FailureReport(failure, appName, logs, attachment))
        }
    }

    /** 遥测关掉或换了项目：排着队的事件与证据一概不发 */
    fun cancelAll() {
        workers.cancel()
        workers = SupervisorJob()
        pending = null
        epoch.incrementAndGet()
    }

    private suspend fun collect(
        failure: TaskFailure,
        start: Deferred<TaskEvidenceStart>?,
        taskEpoch: Long,
        withImages: Boolean,
    ): Pair<DiagnosticLogs?, AttachmentOutcome> {
        val snapshot = start?.await() ?: return null to AttachmentOutcome.NotSelected
        return TaskEvidence.captureEnd(snapshot).use { selection ->
            settleImages(selection, taskEpoch)
            val logs = TaskEvidence.buildLogs(selection).redacted(SecretRedaction.redactable(secrets()))
            val attachment = if (withImages) {
                TaskEvidence.buildImageBundle(selection, failure.runId, failure.taskId)
            } else {
                AttachmentOutcome.NotSelected
            }
            logs to attachment
        }
    }

    private suspend fun settleImages(selection: TaskEvidenceSelection, taskEpoch: Long) {
        repeat(IMAGE_SETTLE_POLLS) {
            delay(IMAGE_SETTLE_INTERVAL_MS)
            if (epoch.get() != taskEpoch) return
            val scan = TaskEvidence.scanImages(selection)
            // 扫的这会儿新任务可能已经开跑，这一眼不能算数
            if (epoch.get() != taskEpoch) {
                scan.close()
                return
            }
            selection.merge(scan)
        }
    }

    private fun DiagnosticLogs.redacted(secrets: List<String>): DiagnosticLogs {
        if (secrets.isEmpty()) return this
        return DiagnosticLogs(
            entries = entries.map {
                DiagnosticLog(it.source, it.kind, SecretRedaction.redact(it.content, secrets), it.rawBytes)
            },
            selectedRawBytes = selectedRawBytes,
            truncated = truncated,
            warnings = warnings,
        )
    }

    private companion object {
        /** MaaFramework 可能在终局回调之后才把 on_error 截图写完，等上一秒 */
        const val IMAGE_SETTLE_POLLS = 10
        const val IMAGE_SETTLE_INTERVAL_MS = 100L
    }
}

/**
 * 截图附件的采样，对应 PI 的 `failure_attachments_sample_rate`；失败事件与日志不受它影响
 *
 * 按轮次与任务算哈希而不是掷随机数：同一个失败无论重算几次结论都一样，与 MXU 同一套做法
 */
internal fun shouldSampleAttachment(runId: String, taskId: Long?, sampleRate: Double): Boolean {
    if (sampleRate <= 0.0) return false
    if (sampleRate >= 1.0) return true
    val digest = MessageDigest.getInstance("SHA-256").apply {
        update("maafwapp-failure-attachment-v1:".toByteArray(Charsets.UTF_8))
        update(runId.toByteArray(Charsets.UTF_8))
        update(ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(taskId ?: 0).array())
    }.digest()
    val bucket = ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).long
    // 无符号 64 位落到 [0, 1)
    return bucket.toULong().toDouble() / ULong.MAX_VALUE.toDouble() < sampleRate
}
