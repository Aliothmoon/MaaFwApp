package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.i18n.UiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 运行日志的唯一产地：合成、留在内存、落盘
 *
 * 进程级而非 Activity 级。原先合成在 `SessionViewModel` 里，切语言 Activity 一重建
 * 跑到一半的日志就没了；定时触发时更是压根没有 VM。屏保与前台服务也要读同一份
 *
 * 合成只在一条协程上跑（两条流先 merge 再收）：[RunLogComposer] 的去重与洪泛滑窗都是
 * 可变状态，两个收集器并发进去会把窗口算乱
 */
class RunLogRecorder(
    private val runnerPort: RunnerPort,
    focusDispatcher: FocusDispatcher,
    private val store: RunSessionLogStore,
    /** [UiText] → 成品文本；写入那一刻的语言被冻进文件，取舍见 [RunSessionRecord.Line] */
    private val renderText: (UiText) -> String,
    /** 只有调试模式才把 details_json 一起落盘：它占掉文件的绝大部分体积 */
    private val includeDetails: () -> Boolean,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private val _runLog = MutableStateFlow<List<RunLogEntry>>(emptyList())
    val runLog: StateFlow<List<RunLogEntry>> = _runLog.asStateFlow()

    private val composer = RunLogComposer()
    private val nextId = AtomicLong(0L)

    /** 本轮的资源展示名，开会话时从 [RunPlan] 冻下来——运行中用户改了资源不该影响这一轮的正文 */
    @Volatile
    private var resourceLabel: String? = null

    private val pending = ConcurrentLinkedQueue<RunSessionRecord.Line>()
    private val session = AtomicReference<RunSessionWriter?>(null)
    private val fileLock = Mutex()
    private var flushLoop: Job? = null

    init {
        scope.launch {
            merge(
                // Focus 从 events 上过滤掉：它要先经 FocusDispatcher 补完（$i18n / 文件 / 占位符）
                runnerPort.events.filter { it !is RunnerEvent.Focus },
                focusDispatcher.resolved
                    .filter { FocusChannel.Log in it.channels }
                    .map { RunnerEvent.Focus(it) },
            ).collect(::record)
        }
    }

    /** 只清屏上这份，不动已落盘的历史——用户按的是「清空」不是「删记录」 */
    fun clear() {
        composer.reset()
        _runLog.value = emptyList()
    }

    /**
     * 开一轮的会话文件
     *
     * 顺带 [RunLogComposer.reset]：去重与洪泛滑窗是按轮的状态，跨轮留着会让新一轮
     * 的第一条被上一轮的末条去重掉
     */
    suspend fun beginSession(plan: RunPlan) {
        resourceLabel = plan.resource.label
        composer.reset()
        val writer = store.open(System.currentTimeMillis(), plan.tasks.map { it.taskName })
        fileLock.withLock { session.getAndSet(writer)?.close() }
        // 开不出文件就不必起冲洗循环：每 75ms 空转一次，白烧
        if (writer == null) return
        flushLoop = scope.launch(ioDispatcher) {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushPending()
            }
        }
    }

    suspend fun endSession(reason: RunEndReason) {
        flushLoop?.cancel()
        flushLoop = null
        flushPending()
        fileLock.withLock {
            session.getAndSet(null)?.let { writer ->
                writer.write(
                    listOf(
                        RunSessionRecord.Footer(
                            endedAt = System.currentTimeMillis(),
                            outcome = reason.toSessionOutcome(),
                        ),
                    ),
                )
                writer.close()
            }
        }
        resourceLabel = null
    }

    private fun record(event: RunnerEvent) {
        val entry = composer.compose(
            event = event,
            id = nextId.incrementAndGet(),
            atMillis = System.currentTimeMillis(),
            context = RunLogContext(
                currentTaskName = runnerPort.state.value.activeExecution?.currentTaskName,
                resourceLabel = resourceLabel,
            ),
        ) ?: return

        _runLog.update { current ->
            val start = (current.size - RUN_LOG_CAPACITY + 1).coerceAtLeast(0)
            current.subList(start, current.size) + entry
        }

        // 没开会话就只留内存：会话之外的事件（比如空闲期的服务日志）不该凭空造出一个文件
        if (session.get() == null) return
        pending.add(
            RunSessionRecord.Line(
                atMillis = entry.atMillis,
                kind = entry.kind,
                text = renderText(entry.text),
                detail = entry.detail?.takeIf { includeDetails() },
            ),
        )
    }

    /** 攒批落盘；整批只 flush 一次 */
    private suspend fun flushPending() {
        if (pending.isEmpty()) return
        withContext(ioDispatcher) {
            fileLock.withLock {
                val writer = session.get() ?: return@withLock
                // poll 到空而不是 toList()+clear()：后者会和并发入队抢，中间那几条直接丢
                val batch = buildList {
                    while (true) add(pending.poll() ?: break)
                }
                writer.write(batch)
            }
        }
    }

    private companion object {
        /** 与 MaaMeow 的 `LOG_FLUSH_INTERVAL_MS` 同值：够把一串突发攒成一次写 */
        const val FLUSH_INTERVAL_MS = 75L
    }
}
