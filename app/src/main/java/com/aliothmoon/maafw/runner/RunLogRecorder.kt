package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.i18n.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 运行日志的唯一产地：合成、留在内存、落盘
 *
 * 进程级而非 Activity 级。原先合成在 `SessionViewModel` 里，切语言 Activity 一重建
 * 跑到一半的日志就没了；定时触发时更是压根没有 VM。屏保与前台服务也要读同一份
 *
 * 合成只在一条协程上跑（只收 [FocusDispatcher.recording] 这一条流）：[RunLogComposer] 的
 * 去重与洪泛滑窗都是可变状态，两个收集器并发进去会把窗口算乱
 *
 * 会话按 executionId 分开：上一轮收尾等终局 marker 的那几秒里，下一轮可能已经开了文件
 */
class RunLogRecorder(
    focusDispatcher: FocusDispatcher,
    private val store: RunSessionLogStore,
    /** [UiText] → 成品文本；写入那一刻的语言被冻进文件，取舍见 [RunSessionRecord.Line] */
    private val renderText: (UiText) -> String,
    /** 只有调试模式才把 details_json 一起落盘：它占掉文件的绝大部分体积 */
    private val includeDetails: () -> Boolean,
    private val scope: CoroutineScope,
    /** 条目时间戳与 agent 洪泛滑窗都按它算；测试要能把 agent 行错开，否则一串就踩中洪泛阈值 */
    private val clock: () -> Long = System::currentTimeMillis,
) : RunJournal {

    private val _runLog = MutableStateFlow(RunLogSnapshot.EMPTY)
    val runLog: StateFlow<RunLogSnapshot> = _runLog.asStateFlow()

    /**
     * 最近一条用户可见正文；合成当下就更新，不等屏上那份攒批
     *
     * Live Update 走 [liveUpdateStatus]：focus / 节点名 压过这一档
     */
    private val _lastUserFacing = MutableStateFlow<String?>(null)
    val lastUserFacing: StateFlow<String?> = _lastUserFacing.asStateFlow()

    private val _lastFocus = MutableStateFlow<String?>(null)
    private val _lastPipelineNode = MutableStateFlow<String?>(null)
    private val _liveUpdateStatus = MutableStateFlow<String?>(null)
    val liveUpdateStatus: StateFlow<String?> = _liveUpdateStatus.asStateFlow()

    private val composer = RunLogComposer()
    private val nextId = AtomicLong(0L)

    private class Session(
        /** 开会话时从 [RunPlan] 冻下来——运行中用户改了资源不该影响这一轮的正文 */
        val resourceLabel: String?,
        val resourceBundleCount: Int,
        val writer: RunSessionWriter?,
    ) {
        val pending = ConcurrentLinkedQueue<RunSessionRecord.Line>()
        val drained = CompletableDeferred<Unit>()
        var flushLoop: Job? = null
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    /** 只有最近一轮上屏、进通知栏 */
    @Volatile
    private var latestExecutionId: String? = null

    private val fileLock = Mutex()

    /**
     * 屏上那份的环形缓冲，进度行与原始行各一个、各自限额；[note] 与合成协程两边都写，靠 [uiLock] 串起来
     *
     * 分开是因为 agent 输出能一秒几十行，共用一个窗口时更早的进度行会被它们挤出去
     *
     * 不逐条改 [_runLog]：那要按条复制整份列表，识别期一秒几十条就是在刷垃圾
     */
    private val progressBuffer = ArrayDeque<RunLogEntry>(RUN_LOG_CAPACITY)
    private val rawBuffer = ArrayDeque<RunLogEntry>(RUN_LOG_CAPACITY)

    /** 原始行因限额丢掉的条数，「全部」档据此提示 */
    private var omittedRaw = 0L
    private val uiLock = Any()
    private val uiDirty = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            focusDispatcher.recording
                .filter {
                    val event = it.event
                    event !is RunnerEvent.Focus || FocusChannel.Log in event.focus.channels
                }
                .collect(::record)
        }
        // 先发后等：第一条即时可见，随后那串攒成一次。等待期间来的由 CONFLATED 留到下一轮
        scope.launch {
            while (true) {
                uiDirty.receive()
                publishBuffered()
                delay(FLUSH_INTERVAL_MS)
            }
        }
    }

    /** 只清屏上这份，不动已落盘的历史——用户按的是「清空」不是「删记录」 */
    fun clear() {
        composer.reset()
        synchronized(uiLock) {
            progressBuffer.clear()
            rawBuffer.clear()
            omittedRaw = 0
        }
        _runLog.value = RunLogSnapshot.EMPTY
        // FGS 状态跟这一轮走，begin 才换句子
    }

    /**
     * 开一轮的会话文件
     *
     * 屏上那份跟着换成这一轮的：几轮叠在一起分不清哪句是哪轮的，历史在会话文件里。
     * 顺带 [RunLogComposer.reset]：去重与洪泛滑窗是按轮的状态，跨轮留着会让新一轮
     * 的第一条被上一轮的末条去重掉
     */
    override suspend fun begin(plan: RunPlan, executionId: String) {
        // 先换 latestExecutionId 再清：上一轮晚到的事件从此不算当前轮，不会再上屏
        latestExecutionId = executionId
        clear()
        resetLiveStatus()
        val writer = store.open(clock(), plan.tasks.map { it.taskName })
        val session = Session(plan.resource.label, plan.resourceBundlePaths().size, writer)
        sessions[executionId] = session
        if (writer == null) return
        session.flushLoop = scope.launch(MaaDispatchers.IO) {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushPending(session)
            }
        }
    }

    /** 看到 Idle 时事件可能还没消费完，等本轮 marker 过了合成协程再写 Footer */
    override suspend fun end(executionId: String, reason: RunEndReason) {
        val session = sessions[executionId] ?: return
        if (reason is RunEndReason.NotRun) noteNotRun(executionId, session, reason)
        // NotRun 没进过 Runner，不会有 marker
        if (reason !is RunEndReason.NotRun &&
            withTimeoutOrNull(DRAIN_TIMEOUT_MS) { session.drained.await() } == null
        ) {
            Timber.w("session log drain timed out: %s", executionId)
        }
        // 等过 marker 再记，原因才排在本轮所有事件之后
        ((reason as? RunEndReason.Ran)?.result as? ExecutionResult.Failed)?.let {
            noteFailed(executionId, session, it)
        }
        sessions.remove(executionId)
        session.flushLoop?.cancel()
        val writer = session.writer ?: return
        fileLock.withLock {
            val records = buildList<RunSessionRecord> {
                while (true) add(session.pending.poll() ?: break)
                add(
                    RunSessionRecord.Footer(
                        endedAt = clock(),
                        outcome = reason.toSessionOutcome(),
                    ),
                )
            }
            writer.write(records)
            writer.close()
        }
    }

    /**
     * 没跑起来的那句原因，排在 Footer 之前；只有 NOT_RUN 的文件查不出是哪一步拦下的
     *
     * 堆栈放进 detail，与 details_json 不同，不看调试模式：它只在出事时有，一轮至多一条
     */
    private fun noteNotRun(executionId: String, session: Session, reason: RunEndReason.NotRun) {
        val text = reason.reason ?: return
        publish(
            RunLogEntry(
                id = nextId.incrementAndGet(),
                atMillis = clock(),
                kind = if (reason.cause == NotRunCause.Cancelled) RunLogKind.Warning else RunLogKind.Error,
                text = text,
                detail = reason.error?.stackTraceToString(),
            ),
            session = session,
            current = isCurrent(executionId),
            writeDetail = true,
        )
    }

    private fun noteFailed(executionId: String, session: Session, result: ExecutionResult.Failed) {
        publish(
            RunLogEntry(
                id = nextId.incrementAndGet(),
                atMillis = clock(),
                kind = RunLogKind.Error,
                text = result.reason,
            ),
            session = session,
            current = isCurrent(executionId),
        )
    }

    /**
     * 外壳自产的一行：不经过 [RunnerEvent]，不走合成器去重
     * 连续两句相同的警告多半是两处各自报的，丢掉会少现场
     */
    override fun note(executionId: String, level: RunNote, text: UiText) {
        publish(
            RunLogEntry(
                id = nextId.incrementAndGet(),
                atMillis = clock(),
                kind = level.asRunLogKind(),
                text = text,
            ),
            session = sessions[executionId],
            current = isCurrent(executionId),
        )
    }

    private fun record(envelope: RunnerEventEnvelope) {
        val event = envelope.event
        if (event is RunnerEvent.AgentOutput) {
            val parts = event.splitUserFacing()
            if (parts.size > 1) {
                parts.forEach { record(envelope.copy(event = it)) }
                return
            }
        }
        val session = sessions[envelope.executionId]
        if (event is RunnerEvent.ExecutionFinished) {
            session?.drained?.complete(Unit)
            return
        }
        val current = isCurrent(envelope.executionId)
        if (!current && session == null) return
        if (current) when (event) {
            is RunnerEvent.Progress -> {
                // Progress 行就是 contentText 的 done/total · label，再当 status 会叠一遍
                _lastPipelineNode.value = null
                _lastFocus.value = null
                _liveUpdateStatus.value = null
            }
            is RunnerEvent.Callback -> {
                PipelineNodeStatus.nameOf(event.message, event.details)?.let {
                    _lastPipelineNode.value = it
                    refreshLiveStatus()
                }
            }
            is RunnerEvent.Focus -> {
                focusPlainText(event.focus.content).lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    ?.trim()
                    ?.let {
                        _lastFocus.value = it
                        refreshLiveStatus()
                    }
            }
            else -> Unit
        }
        val entry = composer.compose(
            event = event,
            id = nextId.incrementAndGet(),
            atMillis = clock(),
            context = RunLogContext(
                currentTaskName = envelope.taskLabel,
                resourceLabel = session?.resourceLabel,
                resourceBundleCount = session?.resourceBundleCount,
            ),
        ) ?: return
        publish(entry, session, current, updateLiveStatus = event !is RunnerEvent.Progress)
    }

    private fun publish(
        entry: RunLogEntry,
        session: Session?,
        current: Boolean,
        updateLiveStatus: Boolean = true,
        /** 默认只有调试模式才落 detail（details_json 太占地方）；自带堆栈的行另说 */
        writeDetail: Boolean = includeDetails(),
    ) {
        if (current) {
            if (entry.isEssential) {
                // 这一句进通知栏，只有 focus 的正文带记号；落盘那份留原文，历史页照样渲染
                val rendered = renderText(entry.text)
                (if (entry.kind == RunLogKind.Focus) focusPlainText(rendered) else rendered).lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    ?.trim()
                    ?.let {
                        _lastUserFacing.value = it
                        if (updateLiveStatus) refreshLiveStatus()
                    }
            }
            synchronized(uiLock) {
                if (entry.isEssential) {
                    while (progressBuffer.size >= RUN_LOG_CAPACITY) progressBuffer.removeFirst()
                    progressBuffer.addLast(entry)
                } else {
                    while (rawBuffer.size >= RUN_LOG_CAPACITY) {
                        rawBuffer.removeFirst()
                        omittedRaw++
                    }
                    rawBuffer.addLast(entry)
                }
            }
            uiDirty.trySend(Unit)
        }

        // 没开会话就只留内存：会话之外的事件（比如空闲期的服务日志）不该凭空造出一个文件
        if (session?.writer == null) return
        session.pending.add(
            RunSessionRecord.Line(
                atMillis = entry.atMillis,
                kind = entry.kind,
                text = renderText(entry.text),
                detail = entry.detail?.takeIf { writeDetail },
            ),
        )
    }

    private fun isCurrent(executionId: String): Boolean =
        latestExecutionId.let { it == null || it == executionId }

    private fun resetLiveStatus() {
        _lastUserFacing.value = null
        _lastFocus.value = null
        _lastPipelineNode.value = null
        _liveUpdateStatus.value = null
    }

    private fun refreshLiveStatus() {
        _liveUpdateStatus.value = resolveLiveUpdateStatus(
            focus = _lastFocus.value,
            pipelineNode = _lastPipelineNode.value,
            fallback = _lastUserFacing.value,
        )
    }

    private fun publishBuffered() {
        _runLog.value = synchronized(uiLock) {
            RunLogSnapshot(
                progress = progressBuffer.toList(),
                all = mergeById(progressBuffer, rawBuffer),
                omittedRaw = omittedRaw,
                firstRawId = rawBuffer.firstOrNull()?.id,
            )
        }
    }

    /** 攒批落盘；整批只 flush 一次 */
    private suspend fun flushPending(session: Session) {
        if (session.pending.isEmpty()) return
        withContext(MaaDispatchers.IO) {
            fileLock.withLock {
                // end 已经把它摘掉并关了文件
                if (!sessions.containsValue(session)) return@withLock
                val writer = session.writer ?: return@withLock
                // poll 到空而不是 toList()+clear()：后者会和并发入队抢，中间那几条直接丢
                val batch = buildList {
                    while (true) add(session.pending.poll() ?: break)
                }
                writer.write(batch)
            }
        }
    }

    private companion object {
        /** 与 MaaMeow 的 `LOG_FLUSH_INTERVAL_MS` 同值：够把一串突发攒成一次写 */
        const val FLUSH_INTERVAL_MS = 75L

        /** 要盖过 focus `{image}` 取帧的 3s 超时：marker 排在那条 focus 后面 */
        const val DRAIN_TIMEOUT_MS = 5_000L
    }
}

/**
 * 两档各自保持入缓冲顺序，按 id 线性归并出「全部」档
 *
 * id 在入缓冲前分配，[RunLogRecorder.note] 与合成协程并发时两档之间可能差一位；
 * 归并只决定交错位置，不丢不重
 */
private fun mergeById(a: List<RunLogEntry>, b: List<RunLogEntry>): List<RunLogEntry> {
    val merged = ArrayList<RunLogEntry>(a.size + b.size)
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        merged.add(if (a[i].id <= b[j].id) a[i++] else b[j++])
    }
    while (i < a.size) merged.add(a[i++])
    while (j < b.size) merged.add(b[j++])
    return merged
}

private fun RunNote.asRunLogKind(): RunLogKind = when (this) {
    RunNote.Info -> RunLogKind.Info
    RunNote.Warning -> RunLogKind.Warning
    RunNote.Error -> RunLogKind.Error
}
