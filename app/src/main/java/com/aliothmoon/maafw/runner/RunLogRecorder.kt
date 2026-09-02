package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.i18n.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 运行日志的唯一产地：合成、留在内存、落盘
 *
 * 进程级而非 Activity 级。原先合成在 `SessionViewModel` 里，切语言 Activity 一重建
 * 跑到一半的日志就没了；定时触发时更是压根没有 VM。屏保与前台服务也要读同一份
 *
 * Runner / Focus / Clear 都汇到 `recorderInputs`，合成只在一条消费协程上跑：
 * [RunLogComposer] 的去重与洪泛滑窗都是可变状态，多个收集器并发进去会把窗口算乱
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
) : RunJournal {

    private val _runLog = MutableStateFlow<List<RunLogEntry>>(emptyList())
    val runLog: StateFlow<List<RunLogEntry>> = _runLog.asStateFlow()

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

    private val nextId = AtomicLong(0L)

    private data class ExecutionContext(
        val taskLabels: Map<String, String>,
        val entryLabels: Map<String, String>,
        val resourceLabel: String?,
        val composer: RunLogComposer,
        val ended: AtomicBoolean = AtomicBoolean(false),
    )

    /** 迟到事件要带旧轮上下文进内存日志，不能借用当前轮映射 */
    private val executionContexts = ConcurrentHashMap<String, ExecutionContext>()
    private val executionOrder = ArrayDeque<String>()
    private val executionOrderLock = Any()

    /**
     * 上下文 ring 只留 8 轮是为了限内存；已结束身份要留得更久，
     * 否则极迟回调在上下文被逐出后又会被空闲期当成「无主事件」
     */
    private val endedExecutionIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val endedOrder = ArrayDeque<String>()

    private class ActiveSession(
        val executionId: String,
        val writer: RunSessionWriter?,
        val pending: ConcurrentLinkedQueue<RunSessionRecord.Line>,
        val rawDrained: CompletableDeferred<Unit> = CompletableDeferred(),
        val focusDrained: CompletableDeferred<Unit> = CompletableDeferred(),
        val closed: AtomicBoolean = AtomicBoolean(false),
    )

    private sealed interface RecorderInput {
        data class Runner(val envelope: RunnerEventEnvelope) : RecorderInput
        data class Focus(val event: FocusDispatcher.RecordingEvent) : RecorderInput
        data object Clear : RecorderInput
    }

    private val sessions = ConcurrentHashMap<String, ActiveSession>()
    private val activeExecutionId = AtomicReference<String?>(null)
    private val latestExecutionId = AtomicReference<String?>(null)
    private val flushJobs = ConcurrentHashMap<String, Job>()
    private val fileLock = Mutex()

    /**
     * 屏上那份的环形缓冲；[note] 与合成协程两边都写，靠 [uiLock] 串起来
     *
     * 不逐条改 [_runLog]：那要按条复制整份 500 元素列表，识别期一秒几十条就是在刷垃圾
     */
    private val uiBuffer = ArrayDeque<RunLogEntry>(RUN_LOG_CAPACITY)
    private val uiLock = Any()
    private val uiDirty = Channel<Unit>(Channel.CONFLATED)
    /** 所有日志输入都送这里；clear 也从 UI/调用方线程入队，不在写入方直接 reset composer */
    private val recorderInputs = Channel<RecorderInput>(Channel.UNLIMITED)

    init {
        scope.launch {
            // Focus 从 events 上过滤掉：它要先经 FocusDispatcher 补完（$i18n / 文件 / 占位符）
            runnerPort.events
                .filter { it.event !is RunnerEvent.Focus }
                .map(RecorderInput::Runner)
                .collect(recorderInputs::send)
        }
        scope.launch {
            focusDispatcher.recording
                .map(RecorderInput::Focus)
                .collect(recorderInputs::send)
        }
        scope.launch {
            while (true) record(recorderInputs.receive())
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
        synchronized(uiLock) { uiBuffer.clear() }
        _runLog.value = emptyList()
        // composer 只在 recorder 协程里用；清屏会改去重状态，不能从 UI 线程直接 reset
        recorderInputs.trySend(RecorderInput.Clear)
        // FGS 状态跟这一轮走，beginSession 才换句子
    }

    override suspend fun begin(plan: RunPlan, executionId: String) = beginSession(plan, executionId)

    override suspend fun end(executionId: String, reason: RunEndReason) =
        endSession(executionId, reason)

    override suspend fun endAfterDrain(executionId: String, reason: RunEndReason) {
        val active = sessions[executionId]
        // NotRun 路径没有 Runner 终局 marker；立即收尾，避免每个被拒绝的请求白等一次超时
        if (reason is RunEndReason.NotRun || active == null) {
            endSession(executionId, reason)
            return
        }

        try {
            withTimeout(DRAIN_TIMEOUT_MS) {
                active.rawDrained.await()
                active.focusDrained.await()
            }
        } catch (timeout: TimeoutCancellationException) {
            Timber.w(
                "session log drain timed out after %dms: %s",
                DRAIN_TIMEOUT_MS,
                executionId,
            )
        }
        endSession(executionId, reason)
    }

    /**
     * 外壳自产的一行：不经过 [RunnerEvent]，不走合成器去重
     * 连续两句相同的警告多半是两处各自报的，丢掉会少现场
     */
    override fun note(executionId: String, level: RunNote, text: UiText) {
        publish(
            RunLogEntry(
                id = nextId.incrementAndGet(),
                atMillis = System.currentTimeMillis(),
                kind = level.asRunLogKind(),
                text = text,
            ),
            executionId = executionId,
            displayInUi = shouldDisplayInUi(executionId),
            updateUserFacing = ownsActiveExecution(executionId),
            updateLiveStatus = ownsActiveExecution(executionId),
        )
    }

    /**
     * 开一轮的会话文件
     *
     * 每轮一个 composer 与映射：迟到旧事件可以进内存，但不能参与新一轮去重或落盘
     */
    suspend fun beginSession(plan: RunPlan, executionId: String) {
        rememberExecutionContext(plan, executionId)
        val active = ActiveSession(
            executionId = executionId,
            writer = store.open(System.currentTimeMillis(), plan.tasks.map { it.taskName }),
            pending = ConcurrentLinkedQueue(),
        )
        sessions[executionId] = active
        activeExecutionId.set(executionId)
        latestExecutionId.set(executionId)
        resetLiveStatus()
        // 旧轮文件留给旧收尾协程 drain；这里只冻结 live 归属，不能提前关，
        // 否则真的“上一轮失败但日志显示下轮失败”时队列里的失败行会被丢掉
        rememberStaleExecutions(executionId)
        if (active.writer == null) return
        flushJobs[executionId] = scope.launch(MaaDispatchers.IO) {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushPending(active)
            }
        }
    }

    suspend fun endSession(executionId: String, reason: RunEndReason) {
        executionContext(executionId).ended.set(true)
        rememberEndedExecution(executionId)
        activeExecutionId.compareAndSet(executionId, null)
        flushJobs.remove(executionId)?.cancel()
        val footer = RunSessionRecord.Footer(
            endedAt = System.currentTimeMillis(),
            outcome = reason.toSessionOutcome(),
        )
        fileLock.withLock {
            closeSession(sessions.remove(executionId), footer)
        }
    }

    private fun rememberExecutionContext(plan: RunPlan, executionId: String) {
        val context = ExecutionContext(
            taskLabels = plan.taskLabelMap(),
            entryLabels = plan.entryLabelMap(),
            resourceLabel = plan.resource.label,
            composer = RunLogComposer(),
        )
        synchronized(executionOrderLock) {
            executionContexts[executionId] = context
            executionOrder.remove(executionId)
            executionOrder.addLast(executionId)
            while (executionOrder.size > REMEMBERED_EXECUTIONS) {
                executionContexts.remove(executionOrder.removeFirst())
            }
        }
    }

    private fun executionContext(executionId: String): ExecutionContext {
        executionContexts[executionId]?.let { return it }
        val created = ExecutionContext(emptyMap(), emptyMap(), null, RunLogComposer())
        synchronized(executionOrderLock) {
            executionContexts.putIfAbsent(executionId, created)?.let { return it }
            executionOrder.remove(executionId)
            executionOrder.addLast(executionId)
            while (executionOrder.size > REMEMBERED_EXECUTIONS) {
                executionContexts.remove(executionOrder.removeFirst())
            }
        }
        return created
    }

    private fun record(input: RecorderInput) {
        when (input) {
            is RecorderInput.Runner -> record(input.envelope)
            RecorderInput.Clear -> executionContexts.values.forEach { it.composer.reset() }
            is RecorderInput.Focus ->
                when (val event = input.event) {
                    is FocusDispatcher.RecordingEvent.Message -> {
                        val focus = event.dispatch.focus
                        if (FocusChannel.Log in focus.channels) {
                            record(
                                RunnerEventEnvelope(
                                    executionId = event.dispatch.executionId,
                                    event = RunnerEvent.Focus(focus),
                                ),
                            )
                        }
                    }

                    is FocusDispatcher.RecordingEvent.Drained ->
                        sessions[event.executionId]?.focusDrained?.complete(Unit)
                }
        }
    }

    private fun record(envelope: RunnerEventEnvelope) {
        val event = envelope.event
        if (event is RunnerEvent.ExecutionFinished) {
            sessions[envelope.executionId]?.rawDrained?.complete(Unit)
            return
        }
        val belongsToActiveSession = ownsActiveExecution(envelope.executionId)
        when (event) {
            is RunnerEvent.Progress -> {
                // Progress 行就是 contentText 的 done/total · label，再当 status 会叠一遍
                if (belongsToActiveSession) {
                    _lastPipelineNode.value = null
                    _lastFocus.value = null
                    _liveUpdateStatus.value = null
                }
            }
            is RunnerEvent.Callback -> {
                if (belongsToActiveSession) {
                    PipelineNodeStatus.nameOf(event.message, event.details)?.let {
                        _lastPipelineNode.value = it
                        refreshLiveStatus()
                    }
                }
            }
            is RunnerEvent.Focus -> {
                if (belongsToActiveSession) {
                    event.focus.content.lineSequence()
                        .firstOrNull { it.isNotBlank() }
                        ?.trim()
                        ?.let {
                            _lastFocus.value = it
                            refreshLiveStatus()
                        }
                }
            }
            else -> Unit
        }
        val context = executionContext(envelope.executionId)
        val entry = context.composer.compose(
            event = event,
            id = nextId.incrementAndGet(),
            atMillis = System.currentTimeMillis(),
            context = RunLogContext(
                currentTaskName = envelope.currentTaskName,
                currentTaskLabel = envelope.currentTaskLabel,
                taskLabels = context.taskLabels,
                entryLabels = context.entryLabels,
                resourceLabel = context.resourceLabel,
            ),
        )
        if (entry == null) return
        publish(
            entry = entry,
            executionId = envelope.executionId,
            displayInUi = shouldDisplayInUi(envelope.executionId),
            updateUserFacing = belongsToActiveSession,
            updateLiveStatus = belongsToActiveSession && event !is RunnerEvent.Progress,
        )
    }

    private fun publish(
        entry: RunLogEntry,
        executionId: String?,
        displayInUi: Boolean,
        updateUserFacing: Boolean,
        updateLiveStatus: Boolean,
    ) {
        if (entry.isEssential && updateUserFacing) {
            renderText(entry.text).lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.let {
                    _lastUserFacing.value = it
                }
            if (updateLiveStatus) refreshLiveStatus()
        }
        if (displayInUi) {
            synchronized(uiLock) {
                while (uiBuffer.size >= RUN_LOG_CAPACITY) uiBuffer.removeFirst()
                uiBuffer.addLast(entry)
            }
            uiDirty.trySend(Unit)
        }

        val active = executionId?.let(sessions::get) ?: return
        if (active.writer == null || active.closed.get()) return
        val record = RunSessionRecord.Line(
            atMillis = entry.atMillis,
            kind = entry.kind,
            text = renderText(entry.text),
            detail = entry.detail?.takeIf { includeDetails() },
        )
        active.pending.add(record)
        // close 可能刚好和入队交错；复查失败时这条只留在内存，不能悄悄挂进已关闭的 session。
        if (active.closed.get()) active.pending.remove(record)
    }

    private fun resetLiveStatus() {
        _lastUserFacing.value = null
        _lastFocus.value = null
        _lastPipelineNode.value = null
        _liveUpdateStatus.value = null
    }

    private fun ownsActiveExecution(executionId: String): Boolean =
        when (val current = activeExecutionId.get()) {
            null -> when {
                executionId in endedExecutionIds -> false
                else -> executionContexts[executionId]?.let { !it.ended.get() } ?: true
            }
            else -> current == executionId
        }

    /**
     * UI 日志跟随当前轮：新轮开始后旧轮迟到事件不再插屏；
     * 空闲期仍显示最近一轮的尾巴，收尾警告与终局后的迟到回调不该凭空消失。
     * 从未开过轮时保持无主事件可见，兼容空闲期服务日志。
     */
    private fun shouldDisplayInUi(executionId: String): Boolean =
        when (val current = activeExecutionId.get()) {
            null -> when (val latest = latestExecutionId.get()) {
                null -> true
                else -> latest == executionId
            }
            else -> current == executionId
        }

    private fun refreshLiveStatus() {
        _liveUpdateStatus.value = resolveLiveUpdateStatus(
            focus = _lastFocus.value,
            pipelineNode = _lastPipelineNode.value,
            fallback = _lastUserFacing.value,
        )
    }

    private fun publishBuffered() {
        _runLog.value = synchronized(uiLock) { uiBuffer.toList() }
    }

    /** 攒批落盘；整批只 flush 一次 */
    private suspend fun flushPending(active: ActiveSession? = null) {
        val target = active ?: return
        if (target.pending.isEmpty()) return
        withContext(MaaDispatchers.IO) {
            fileLock.withLock {
                if (target.closed.get()) return@withLock
                // poll 到空而不是 toList()+clear()：后者会和并发入队抢，中间那几条直接丢
                val batch = buildList {
                    while (true) add(target.pending.poll() ?: break)
                }
                target.writer?.write(batch)
            }
        }
    }

    private fun closeSession(active: ActiveSession?, footer: RunSessionRecord.Footer?) {
        if (active == null || !active.closed.compareAndSet(false, true)) return
        active.rawDrained.complete(Unit)
        active.focusDrained.complete(Unit)
        val writer = active.writer ?: return
        val records = buildList {
            while (true) add(active.pending.poll() ?: break)
            footer?.let { add(it) }
        }
        writer.write(records)
        writer.close()
    }

    private fun rememberStaleExecutions(executionId: String) {
        sessions.keys.filterNot { it == executionId }.forEach {
            executionContexts[it]?.ended?.set(true)
            rememberEndedExecution(it)
        }
    }

    private fun rememberEndedExecution(executionId: String) {
        synchronized(executionOrderLock) {
            if (!endedExecutionIds.add(executionId)) return
            endedOrder.addLast(executionId)
            while (endedOrder.size > ENDED_EXECUTIONS) {
                endedExecutionIds.remove(endedOrder.removeFirst())
            }
        }
    }

    private companion object {
        /** 与 MaaMeow 的 `LOG_FLUSH_INTERVAL_MS` 同值：够把一串突发攒成一次写 */
        const val FLUSH_INTERVAL_MS = 75L
        const val DRAIN_TIMEOUT_MS = 2_000L
        const val REMEMBERED_EXECUTIONS = 8
        const val ENDED_EXECUTIONS = 64
    }
}

private fun RunNote.asRunLogKind(): RunLogKind = when (this) {
    RunNote.Info -> RunLogKind.Info
    RunNote.Warning -> RunLogKind.Warning
    RunNote.Error -> RunLogKind.Error
}
