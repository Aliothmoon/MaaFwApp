package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * focus 模板的补完与分发
 *
 * **进程级，不挂在 ViewModel 上**：`display: notification` 那一档的用意就是「应用在后台也
 * 收得到」，而 Activity 一销毁 ViewModel 就没了。补完只做一遍，日志、toast、系统通知
 * 共用同一份结果——各自补一遍会重复 IO，还会各截一张图
 *
 * 补完顺序按协议 3.3「Client 处理流程」，**不能换**：
 *
 * 1. `$i18n` 查表——译文本身可能是个文件路径，所以要最先
 * 2. `{image}` 与文件路径形态（要 IO）
 * 3. 占位符替换——译文与读进来的正文都可能带着 `{name}`，先替换就轮不到它们
 */
class FocusDispatcher(
    private val projectRepository: ProjectRepository,
    private val resolver: FocusContentResolver,
    private val runnerPort: RunnerPort,
    scope: CoroutineScope,
) {

    data class Dispatch(
        val executionId: String,
        val focus: FocusMessage,
    )

    /** Recorder 专用流：Message 与 Drained 保序，Drained 表示之前的 Message 已全部入流 */
    sealed interface RecordingEvent {
        data class Message(val dispatch: Dispatch) : RecordingEvent
        data class Drained(val executionId: String) : RecordingEvent
    }

    /**
     * 补完后的模板消息
     *
     * SharedFlow 而不是 StateFlow：这些是一次性消息，订阅者晚到不该补看上一条。
     * 容量给足 32——补完带 IO，慢订阅者不该把消息挤掉
     */
    private val _resolved = MutableSharedFlow<Dispatch>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val resolved: Flow<Dispatch> = _resolved.asSharedFlow()

    private val _recording = MutableSharedFlow<RecordingEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * 日志不能同时订阅 resolved 与另一个独立 terminal 流：两个 SharedFlow 到达 recorder
     * 的顺序没有保证。这里用单流保序，Drained 只有在之前的 Message emit 完才会发出。
     */
    val recording: Flow<RecordingEvent> = _recording.asSharedFlow()

    /**
     * `trace` 命中的条目，未补完
     *
     * 上报侧要的是事件名与节点名，正文一概不带：PI 可以把用户输入拼进 focus 正文
     */
    private val _traced = MutableSharedFlow<Dispatch>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val traced: Flow<Dispatch> = _traced.asSharedFlow()

    init {
        scope.launch {
            runnerPort.events.collect { envelope ->
                if (envelope.event is RunnerEvent.ExecutionFinished) {
                    // Drained carries no user-visible stale content; it only unblocks that
                    // execution's recorder session. A newer run must not suppress it.
                    _recording.emit(RecordingEvent.Drained(envelope.executionId))
                    return@collect
                }

                val event = envelope.event as? RunnerEvent.Focus ?: return@collect
                if (!accepts(envelope.executionId)) return@collect
                val dispatch = Dispatch(envelope.executionId, event.focus)
                val completed = if (event.focus.displayable) {
                    dispatch.copy(focus = complete(event.focus))
                } else {
                    null
                }
                // IO 期间下一轮可能已经开始；补完结果不能再回到旧消费者面前
                if (!accepts(envelope.executionId)) return@collect
                if (event.focus.trace) _traced.emit(dispatch)
                if (completed != null) {
                    _resolved.emit(completed)
                    _recording.emit(RecordingEvent.Message(completed))
                }
            }
        }
    }

    private fun accepts(executionId: String): Boolean {
        val state = runnerPort.state.value
        val active = state.activeExecution
        if (active != null) return active.executionId == executionId

        val latest = state.latestExecutionId
        return latest == null || latest == executionId
    }

    private suspend fun complete(focus: FocusMessage): FocusMessage {
        val translated = resolveI18n(focus.content)
        val loaded = if (focusContentNeedsIo(translated)) resolver.resolve(translated) else translated
        return focus.copy(content = substituteFocusPlaceholders(loaded, focus.placeholders))
    }

    /** 查无此键回落键名本身，与加载期物化 label/description 的处理一致 */
    private fun resolveI18n(text: String): String {
        if (!text.startsWith("$")) return text
        val key = text.substring(1)
        val definition = (projectRepository.state.value as? ProjectState.Ready)?.definition
        return definition?.translations?.get(key) ?: key
    }
}
