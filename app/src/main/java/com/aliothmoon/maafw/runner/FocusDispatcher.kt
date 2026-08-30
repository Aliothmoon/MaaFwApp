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
 * 4. MFA 富文本记号转 HTML——占位符的值也可能参与颜色或字号记号
 */
class FocusDispatcher(
    private val projectRepository: ProjectRepository,
    private val resolver: FocusContentResolver,
    runnerPort: RunnerPort,
    scope: CoroutineScope,
) {

    /**
     * 补完后的模板消息
     *
     * SharedFlow 而不是 StateFlow：这些是一次性消息，订阅者晚到不该补看上一条。
     * 容量给足 32——补完带 IO，慢订阅者不该把消息挤掉
     */
    private val _resolved = MutableSharedFlow<FocusMessage>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val resolved: Flow<FocusMessage> = _resolved.asSharedFlow()

    /**
     * `trace` 命中的条目，未补完
     *
     * 上报侧要的是事件名与节点名，正文一概不带：PI 可以把用户输入拼进 focus 正文
     */
    private val _traced = MutableSharedFlow<FocusMessage>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val traced: Flow<FocusMessage> = _traced.asSharedFlow()

    init {
        scope.launch {
            runnerPort.events.collect { event ->
                if (event !is RunnerEvent.Focus) return@collect
                val focus = event.focus
                if (focus.trace) _traced.emit(focus)
                if (focus.displayable) _resolved.emit(complete(focus))
            }
        }
    }

    private suspend fun complete(focus: FocusMessage): FocusMessage {
        val translated = resolveI18n(focus.content)
        val loaded = if (focusContentNeedsIo(translated)) resolver.resolve(translated) else translated
        return focus.copy(
            content = convertCustomFocusMarkup(substituteFocusPlaceholders(loaded, focus.placeholders)),
        )
    }

    /** 查无此键回落键名本身，与加载期物化 label/description 的处理一致 */
    private fun resolveI18n(text: String): String {
        if (!text.startsWith("$")) return text
        val key = text.substring(1)
        val definition = (projectRepository.state.value as? ProjectState.Ready)?.definition
        return definition?.translations?.get(key) ?: key
    }
}

private val COLOR_MARKUP = Regex("""\[color:([^]]+)]""", RegexOption.IGNORE_CASE)
private val COLOR_MARKUP_END = Regex("""\[/color]""", RegexOption.IGNORE_CASE)
private val SIZE_MARKUP = Regex("""\[size:(\d+)]""", RegexOption.IGNORE_CASE)
private val SIZE_MARKUP_END = Regex("""\[/size]""", RegexOption.IGNORE_CASE)

/** MFAAvalonia 的 focus 富文本记号；放在占位符替换后，值本身也能参与记号 */
private fun convertCustomFocusMarkup(content: String): String = content
    .replace(COLOR_MARKUP) { match -> """<span style="color: ${match.groupValues[1]};">""" }
    .replace(COLOR_MARKUP_END, "</span>")
    .replace(SIZE_MARKUP) { match -> """<span style="font-size: ${match.groupValues[1]}px;">""" }
    .replace(SIZE_MARKUP_END, "</span>")
