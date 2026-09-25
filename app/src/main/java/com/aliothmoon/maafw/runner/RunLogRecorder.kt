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

    private val composer = RunLogComposer()
    private val nextId = AtomicLong(0L)

    private class Session(
        /** 开会话时从 [RunPlan] 冻下来——运行中用户改了资源不该影响这一轮的正文 */
        val resourceLabel: String?,
        /** null = 文件没开成，这一轮只留内存 */
        val writer: RunSessionWriter?,
    ) {
        val pending = ConcurrentLinkedQueue<RunSessionRecord.Line>()
        val drained = CompletableDeferred<Unit>()
        var flushLoop: Job? = null
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    /** 最近开的一轮；只有它的事件上屏、进通知栏，更早那轮的迟到事件只进它自己的文件 */
    @Volatile
    private var latestExecutionId: String? = null

    private val fileLock = Mutex()

    /**
     * 屏上那份的环形缓冲；[note] 与合成协程两边都写，靠 [uiLock] 串起来
     *
     * 不逐条改 [_runLog]：那要按条复制整份 500 元素列表，识别期一秒几十条就是在刷垃圾
     */
    private val uiBuffer = ArrayDeque<RunLogEntry>(RUN_LOG_CAPACITY)
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
        synchronized(uiLock) { uiBuffer.clear() }
        _runLog.value = emptyList()
        // FGS 状态跟这一轮走，begin 才换句子
    }

    /**
     * 开一轮的会话文件
     *
     * 顺带 [RunLogComposer.reset]：去重与洪泛滑窗是按轮的状态，跨轮留着会让新一轮
     * 的第一条被上一轮的末条去重掉
     */
    override suspend fun begin(plan: RunPlan, executionId: String) {
        latestExecutionId = executionId
        composer.reset()
        resetLiveStatus()
        val writer = store.open(System.currentTimeMillis(), plan.tasks.map { it.taskName })
        val session = Session(plan.resource.label, writer)
        sessions[executionId] = session
        if (writer == null) return
        session.flushLoop = scope.launch(MaaDispatchers.IO) {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushPending(session)
            }
        }
    }

    /**
     * 看到 Idle 时事件流可能还没消费完，先等这一轮的终局 marker 过了合成协程再写 Footer，
     * 否则尾巴上的失败行要么丢，要么落进下一轮的文件
     */
    override suspend fun end(executionId: String, reason: RunEndReason) {
        val session = sessions[executionId] ?: return
        // NotRun 没进过 Runner，不会有 marker
        if (reason !is RunEndReason.NotRun &&
            withTimeoutOrNull(DRAIN_TIMEOUT_MS) { session.drained.await() } == null
        ) {
            Timber.w("session log drain timed out: %s", executionId)
        }
        sessions.remove(executionId)
        session.flushLoop?.cancel()
        val writer = session.writer ?: return
        fileLock.withLock {
            val records = buildList<RunSessionRecord> {
                while (true) add(session.pending.poll() ?: break)
                add(
                    RunSessionRecord.Footer(
                        endedAt = System.currentTimeMillis(),
                        outcome = reason.toSessionOutcome(),
                    ),
                )
            }
            writer.write(records)
            writer.close()
        }
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
            session = sessions[executionId],
            current = isCurrent(executionId),
        )
    }

    private fun record(envelope: RunnerEventEnvelope) {
        val event = envelope.event
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
                event.focus.content.lineSequence()
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
            atMillis = System.currentTimeMillis(),
            context = RunLogContext(
                currentTaskName = envelope.taskLabel,
                resourceLabel = session?.resourceLabel,
            ),
        ) ?: return
        publish(entry, session, current, updateLiveStatus = event !is RunnerEvent.Progress)
    }

    private fun publish(
        entry: RunLogEntry,
        session: Session?,
        current: Boolean,
        updateLiveStatus: Boolean = true,
    ) {
        if (current) {
            if (entry.isEssential) {
                renderText(entry.text).lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    ?.trim()
                    ?.let {
                        _lastUserFacing.value = it
                        if (updateLiveStatus) refreshLiveStatus()
                    }
            }
            synchronized(uiLock) {
                while (uiBuffer.size >= RUN_LOG_CAPACITY) uiBuffer.removeFirst()
                uiBuffer.addLast(entry)
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
                detail = entry.detail?.takeIf { includeDetails() },
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
        _runLog.value = synchronized(uiLock) { uiBuffer.toList() }
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

private fun RunNote.asRunLogKind(): RunLogKind = when (this) {
    RunNote.Info -> RunLogKind.Info
    RunNote.Warning -> RunLogKind.Warning
    RunNote.Error -> RunLogKind.Error
}
