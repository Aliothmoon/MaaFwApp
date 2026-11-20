package com.aliothmoon.maafw.telemetry

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.log.SecretRedaction
import com.aliothmoon.maafw.runner.AgentExitCode
import com.aliothmoon.maafw.runner.RunnerEvent
import io.sentry.Attachment
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/** 同一个 agent 栽在同一个信号（或退出码）上算一组 */
internal fun RunnerEvent.AgentExited.toSentryEvent(appName: String, runId: String, taskLabel: String?): SentryEvent {
    val cause = signal?.let(AgentExitCode::signalName) ?: "code $exitCode"
    return SentryEvent().also { event ->
        event.level = SentryLevel.ERROR
        event.logger = AGENT_LOGGER
        event.transaction = AGENT_TRANSACTION
        event.message = Message().apply { formatted = "Agent exited: $label ($cause)" }
        event.fingerprints = listOf(AGENT_FINGERPRINT, appName, label, cause)
        event.setTag("agent.name", label)
        event.setTag("agent.exit", cause)
        event.setTag("run.id", runId)
        event.setExtra("agent.exec", exec)
        event.setExtra("agent.exit_code", exitCode)
        event.setExtra("agent.crash_report", crashReport ?: "none")
        taskLabel?.let { event.setExtra("task.label", it) }
    }
}

/**
 * agent 没被要求退出却退了：debuggerd 接管的信号有 `log/crash/` 下的现场，
 * Go 的 panic 是退出码 2、栈只在 stderr，所以另带最近的 stderr
 *
 * [onOutput] 与 [report] 由调用方串行调用
 */
internal class AgentExitReporter(
    private val scope: CoroutineScope,
    private val crashDir: () -> File,
    /** 当前保存着的 PI password 明文，stderr 离开设备前换成掩码 */
    private val secrets: suspend () -> Collection<String>,
    private val send: (SentryEvent, List<Attachment>) -> Unit,
) {
    private val stderr = ArrayDeque<String>()

    fun onOutput(output: RunnerEvent.AgentOutput) {
        if (!output.fromStderr) return
        output.line.lineSequence().forEach { line ->
            stderr.addLast(line)
            if (stderr.size > MAX_STDERR_LINES) stderr.removeFirst()
        }
    }

    fun report(exit: RunnerEvent.AgentExited, runId: String, taskLabel: String?, appName: String) {
        val tail = stderr.toList()
        scope.launch(MaaDispatchers.IO) {
            val attachments = buildList {
                exit.crashReport?.let { name ->
                    // 特权进程写的文件可能对 App 不可读，读不到就不带
                    runCatching { File(crashDir(), name).readBytes() }
                        .onSuccess { add(Attachment(it, name, TEXT_CONTENT_TYPE)) }
                        .onFailure { Timber.w(it, "read agent crash report failed: %s", name) }
                }
                if (tail.isNotEmpty()) {
                    val redacted = SecretRedaction.redact(tail.joinToString("\n"), SecretRedaction.redactable(secrets()))
                    add(Attachment(redacted.toByteArray(), STDERR_ATTACHMENT, TEXT_CONTENT_TYPE))
                }
            }
            send(exit.toSentryEvent(appName, runId, taskLabel), attachments)
        }
    }

    private companion object {
        const val MAX_STDERR_LINES = 200
        const val STDERR_ATTACHMENT = "agent_stderr_tail.txt"
        const val TEXT_CONTENT_TYPE = "text/plain"
    }
}

private const val AGENT_LOGGER = "maafwapp.agent"
private const val AGENT_TRANSACTION = "maafwapp.agent.exited"
private const val AGENT_FINGERPRINT = "maafwapp-agent-exited"
