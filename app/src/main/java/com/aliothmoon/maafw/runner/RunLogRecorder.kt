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
 *
 * 多开改造：状态按 slot 隔离。屏上日志跟着「当前选中的实例」走，不是跟着「最后启动的那一路」——
 * 否则后启动的路会把先启动的路从屏上踢掉，看起来像「日志错乱不显示」。去重器、屏上缓冲、
 * 洪泛计数每路各一份，互不干扰。
 */
class RunLogRecorder(
    focusDispatcher: FocusDispatcher,
    private val store: RunSessionLogStore,
    /** [UiText] → 成品文本；写入那一刻的语言被冻进文件，取舍见 [RunSessionRecord.Line] */
    private val renderText: (UiText) -> String,
    /** 只有调试模式才把 details_json 一起落盘：它占掉文件的绝大部分体积 */
    private val includeDetails: () -> Boolean,
    private val scope: CoroutineScope,
    /** 当前 UI 选中的实例号：屏上日志跟着它走 */
    private val activeSlotFlow: StateFlow<Int>,
    /** 条目时间戳与 agent 洪泛滑窗都按它算；测试要能把 agent 行错开，否则一串就踩中洪泛阈值 */
    private val clock: () -> Long = System::currentTimeMillis,
) : RunJournal {

    private val _runLog = MutableStateFlow(RunLogSnapshot.EMPTY)
    val runLog: StateFlow<RunLogSnapshot> = _runLog.asStateFlow()

    private val _lastUserFacing = MutableStateFlow<String?>(null)
    val lastUserFacing: StateFlow<String?> = _lastUserFacing.asStateFlow()

    private val _lastFocus = MutableStateFlow<String?>(null)
    private val _lastPipelineNode = MutableStateFlow<String?>(null)
    private val _liveUpdateStatus = MutableStateFlow<String?>(null)
    val liveUpdateStatus: StateFlow<String?> = _liveUpdateStatus.asStateFlow()

    /** 每路一个去重/洪泛状态：多开时它们必须各自独立，否则一路的事件会被另一路的窗口误吞 */
    private val composers = ConcurrentHashMap<Int, RunLogComposer>()

    private val nextId = AtomicLong(0L)

    private class Session(
        /** 这一轮归属的实例号 */
        val slot: Int,
        val resourceLabel: String?,
        val resourceBundleCount: Int,
        val writer: RunSessionWriter?,
    ) {
        val pending = ConcurrentLinkedQueue<RunSessionRecord.Line>()
        val drained = CompletableDeferred<Unit>()
        var flushLoop: Job? = null
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    private val recentBySlot = HashMap<Int, ArrayDeque<RunLogEntry>>()

    private val _instanceLogs = MutableStateFlow<Map<Int, List<RunLogEntry>>>(emptyMap())

    /** 实例号 → 该路最近几行；「全部实例」开着显示日志时读它 */
    val instanceLogs: StateFlow<Map<Int, List<RunLogEntry>>> = _instanceLogs.asStateFlow()

    private val fileLock = Mutex()

    /** 每路各自的屏上缓冲；屏上只渲染 activeSlotFlow 当前值那一路的 */
    private val progressBuffers = ConcurrentHashMap<Int, ArrayDeque<RunLogEntry>>()
    private val rawBuffers = ConcurrentHashMap<Int, ArrayDeque<RunLogEntry>>()
    private val omittedRawBySlot = ConcurrentHashMap<Int, Long>()

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
        scope.launch {
            while (true) {
                uiDirty.receive()
                publishBuffered()
                delay(FLUSH_INTERVAL_MS)
            }
        }
        // 切实例时，屏上缓冲与 live status 换成新选中那一路的
        scope.launch {
            activeSlotFlow.collect {
                resetLiveStatus()
                publishBuffered()
            }
        }
    }

    override suspend fun begin(plan: RunPlan, executionId: String, slot: Int) {
        // 这一路换了新轮次：清它自己的屏上缓冲与去重状态，不动其他路
        synchronized(uiLock) {
            progressBuffers.remove(slot)?.clear()
            rawBuffers.remove(slot)?.clear()
            omittedRawBySlot[slot] = 0L
        }
        composers[slot] = RunLogComposer()
        if (slot == activeSlotFlow.value) resetLiveStatus()
        val writer = store.open(clock(), plan.tasks.map { it.taskName })
        val session = Session(slot, plan.resource.label, plan.resourceBundlePaths().size, writer)
        sessions[executionId] = session
        if (writer == null) return
        session.flushLoop = scope.launch(MaaDispatchers.IO) {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushPending(session)
            }
        }
    }

    override suspend fun end(executionId: String, reason: RunEndReason) {
        val session = sessions[executionId] ?: return
        if (reason is RunEndReason.NotRun) noteNotRun(executionId, session, reason)
        if (reason !is RunEndReason.NotRun &&
            withTimeoutOrNull(DRAIN_TIMEOUT_MS) { session.drained.await() } == null
        ) {
            Timber.w("session log drain timed out: %s", executionId)
        }
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
            slot = session.slot,
            session = session,
            current = isSelectedSlot(session.slot),
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
            slot = session.slot,
            session = session,
            current = isSelectedSlot(session.slot),
        )
    }

    override fun note(executionId: String, level: RunNote, text: UiText) {
        val session = sessions[executionId]
        val slot = session?.slot ?: -1
        publish(
            RunLogEntry(
                id = nextId.incrementAndGet(),
                atMillis = clock(),
                kind = level.asRunLogKind(),
                text = text,
            ),
            slot = slot,
            session = session,
            current = isSelectedSlot(slot),
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
        val slot = envelope.slot
        val session = sessions[envelope.executionId]
        if (event is RunnerEvent.ExecutionFinished) {
            session?.drained?.complete(Unit)
            return
        }
        // 关键：current = "这一路是不是当前选中的实例"，不是"是不是最后启动的 executionId"
        val current = isSelectedSlot(slot)
        if (!current && session == null) return
        if (current) when (event) {
            is RunnerEvent.Progress -> {
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
        // 去重器按 slot 取：每路一份，互不干扰
        val composer = composers.getOrPut(slot) { RunLogComposer() }
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
        rememberForSlot(slot, entry)
        publish(entry, slot, session, current, updateLiveStatus = event !is RunnerEvent.Progress)
    }

    /** 这一路是不是当前 UI 选中的实例 */
    private fun isSelectedSlot(slot: Int): Boolean = slot == activeSlotFlow.value

    /** 用户按「清空日志」：只清当前选中那一路的屏上缓冲，不动其他路 */
    fun clear() {
        val slot = activeSlotFlow.value
        composers[slot]?.reset()
        synchronized(uiLock) {
            progressBuffers.remove(slot)?.clear()
            rawBuffers.remove(slot)?.clear()
            omittedRawBySlot[slot] = 0L
        }
        publishBuffered()
    }

    private fun rememberForSlot(slot: Int, entry: RunLogEntry) {
        if (!entry.isEssential) return
        _instanceLogs.value = synchronized(uiLock) {
            val deque = recentBySlot.getOrPut(slot) { ArrayDeque(RECENT_PER_SLOT) }
            while (deque.size >= RECENT_PER_SLOT) deque.removeFirst()
            deque.addLast(entry)
            recentBySlot.mapValues { it.value.toList() }
        }
    }

    private fun publish(
        entry: RunLogEntry,
        /** 这条日志归属的实例号；决定写进哪一路缓冲，与当前是否选中无关 */
        slot: Int,
        session: Session?,
        current: Boolean,
        updateLiveStatus: Boolean = true,
        writeDetail: Boolean = includeDetails(),
    ) {
        // 每路日志都写进它自己的内存缓冲，不依赖「是不是当前选中」：
        // 否则用户停留在某一路时，其他路的日志只落盘不进缓冲，切过去就只剩空白。
        // 屏上刷新只在该路正被看时触发（下方 uiDirty），切到该路时 activeSlotFlow 也会立刻 publishBuffered()。
        if (slot >= 0) {
            synchronized(uiLock) {
                val progress = progressBuffers.getOrPut(slot) { ArrayDeque(RUN_LOG_CAPACITY) }
                val raw = rawBuffers.getOrPut(slot) { ArrayDeque(RUN_LOG_CAPACITY) }
                if (entry.isEssential) {
                    while (progress.size >= RUN_LOG_CAPACITY) progress.removeFirst()
                    progress.addLast(entry)
                } else {
                    while (raw.size >= RUN_LOG_CAPACITY) {
                        raw.removeFirst()
                        omittedRawBySlot[slot] = (omittedRawBySlot[slot] ?: 0L) + 1L
                    }
                    raw.addLast(entry)
                }
            }
            if (current) uiDirty.trySend(Unit)
        }

        // 「最近一条人话」/ live status 只反映当前选中那一路，其他路不覆盖它
        if (current && entry.isEssential) {
            val rendered = renderText(entry.text)
            (if (entry.kind == RunLogKind.Focus) focusPlainText(rendered) else rendered).lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.let {
                    _lastUserFacing.value = it
                    if (updateLiveStatus) refreshLiveStatus()
                }
        }

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
        val slot = activeSlotFlow.value
        _runLog.value = synchronized(uiLock) {
            val progress = progressBuffers[slot].orEmpty()
            val raw = rawBuffers[slot].orEmpty()
            RunLogSnapshot(
                progress = progress.toList(),
                all = mergeById(progress, raw),
                omittedRaw = omittedRawBySlot[slot] ?: 0L,
                firstRawId = raw.firstOrNull()?.id,
            )
        }
    }

    private suspend fun flushPending(session: Session) {
        if (session.pending.isEmpty()) return
        withContext(MaaDispatchers.IO) {
            fileLock.withLock {
                if (!sessions.containsValue(session)) return@withContext
                val writer = session.writer ?: return@withContext
                val batch = buildList {
                    while (true) add(session.pending.poll() ?: break)
                }
                writer.write(batch)
            }
        }
    }

    private companion object {
        const val FLUSH_INTERVAL_MS = 75L
        const val DRAIN_TIMEOUT_MS = 5_000L
        const val RECENT_PER_SLOT = 2
    }
}

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
