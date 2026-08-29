package com.aliothmoon.maafw.runner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * PI 作者在 pipeline 节点上声明的消息模板
 * （MaaFramework `docs/zh_cn/3.3-ProjectInterfaceV2协议.md`「消息模板机制」）
 *
 * 与 [RunnerEvent] 其余成员的分工：那些是 MaaFramework 的原始转储，给排障看；
 * 这条是 PI 作者写给终端用户的话，外壳负责补完再按 [channels] 投递
 *
 * [content] 此刻**还没替换占位符**：协议的 Client 处理流程把替换放在最后一步，而 `$i18n`
 * 译文与文件形态的正文本身可能带着 `{name}`。替换要用的那份数据随 [placeholders] 一起走，
 * 因为到替换那一刻原始 details 早已不在手上
 */
data class FocusMessage(
    /** 回调事件名，也是 focus 字典里的键 */
    val message: String,
    /** 空 = 只配了 trace 的条目，没有要展示的东西 */
    val content: String,
    val channels: Set<FocusChannel>,
    /** v2.9.1 `trace`：这条节点结果要不要上报遥测 */
    val trace: Boolean,
    /** 同一条回调 details 里的标量字段；非标量取不出可比的文本，不收 */
    val placeholders: Map<String, String> = emptyMap(),
    /** 特权进程为 blocking modal 生成的确认句柄；普通事件与旧通道事件没有这个值 */
    val modalId: String? = null,
    /** 产生这条消息的执行轮次；blocking modal 展示前用它拒绝晚到的旧请求 */
    val executionId: String? = null,
) {
    val displayable: Boolean get() = content.isNotBlank()
}

/** 只有仍属于当前执行轮次的 blocking modal 才允许进入可操作 UI */
fun FocusMessage.isActionableModalFor(state: RunnerState): Boolean {
    val activeExecutionId = state.activeExecution?.executionId
        ?.takeIf(String::isNotBlank)
        ?: return false
    val sourceExecutionId = executionId?.takeIf(String::isNotBlank) ?: return false
    return sourceExecutionId == activeExecutionId &&
        modalId?.takeIf(String::isNotBlank) != null
}

/**
 * 协议的 `display` 五档全部落地
 *
 * [Dialog] 是非阻塞弹窗；[Modal] 由特权进程 gate 阻塞 native 回调，app 确认后才放行。
 * 认不出的档仍落 [Log]——协议加档时少显示一处，好过整条丢掉
 */
enum class FocusChannel { Log, Toast, Notification, Dialog, Modal }

/**
 * 把 `{key}` 换成 [placeholders] 里的值
 *
 * 放在补完的**最后一步**（协议 3.3「Client 处理流程」第 5 步）：查表译文与读进来的文件
 * 正文都可能带着占位符，先替换就轮不到它们
 *
 * 未命中的原样透传，与 RunPlanBuilder 一致：`{…}` 不全是要替换的东西
 */
fun substituteFocusPlaceholders(content: String, placeholders: Map<String, String>): String =
    FocusParser.PLACEHOLDER.replace(content) { match ->
        placeholders[match.groupValues[1]] ?: match.value
    }

/**
 * 从回调的 `details_json` 里取出本条消息对应的模板
 *
 * 节点的整份 `focus` 字典会挂在**每一条**该节点的回调详情上，取的时候要按 message 名索引
 */
object FocusParser {

    /**
     * 先按串筛掉没有模板的回调，别为它们各解一次完整 JSON
     *
     * **光看有没有 `"focus"` 不够**：MaaFramework 给每一条节点回调都带这个键，没配模板时
     * 值是 `null`。实测一轮冒烟 222 条回调里 184 条是 `"focus":null`、真模板 0 条，
     * 只判键名等于没筛。所以要把 null 那种也排掉
     *
     * 排的是字面量而不是解析结果：万一哪版输出带了空格没匹配上，也只是退回去解一次 JSON，
     * 结果照样对——宁可慢一次，不可漏一条
     */
    private const val FOCUS_MARKER = "\"focus\""
    private const val FOCUS_ABSENT = "\"focus\":null"

    private const val FOCUS_KEY = "focus"
    private const val CONTENT_KEY = "content"
    private const val DISPLAY_KEY = "display"
    private const val TRACE_KEY = "trace"

    /** `trace` 缺省时唯一按 true 算的事件（协议 v2.9.1） */
    private const val TRACED_BY_DEFAULT = "Node.PipelineNode.Failed"

    /** 闭括号必须转义：Android 的 ICU 正则不接受孤立的 `}`（与 RunPlanBuilder 同款坑） */
    internal val PLACEHOLDER = Regex("""\{([^{}]+)\}""")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 返回 null 表示这条回调没有模板，按原始转储处理 */
    fun parse(message: String, detailsJson: String): FocusMessage? {
        if (message.isEmpty()) return null
        if (!detailsJson.contains(FOCUS_MARKER) || detailsJson.contains(FOCUS_ABSENT)) return null

        val details = runCatching { json.parseToJsonElement(detailsJson) }.getOrNull() as? JsonObject
            ?: return null
        val entry = (details[FOCUS_KEY] as? JsonObject)?.get(message) ?: return null

        val (rawContent, channels, trace) = when (entry) {
            // 简写：等价于 display: "log"
            is JsonPrimitive -> Triple(
                entry.contentOrNullIfNotString(),
                setOf(FocusChannel.Log),
                message == TRACED_BY_DEFAULT,
            )

            is JsonObject -> Triple(
                (entry[CONTENT_KEY] as? JsonPrimitive)?.contentOrNullIfNotString(),
                parseChannels(entry[DISPLAY_KEY]),
                (entry[TRACE_KEY] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                    ?: (message == TRACED_BY_DEFAULT),
            )

            else -> return null
        }
        // content 缺省而 trace 为假的条目什么都不做，不必往下游发
        if (rawContent.isNullOrBlank() && !trace) return null

        return FocusMessage(
            message = message,
            content = rawContent.orEmpty(),
            channels = channels,
            trace = trace,
            placeholders = scalarFields(details),
        )
    }

    /** `focus` 自己是对象，不会混进来；其余非标量同样取不出可比的文本 */
    private fun scalarFields(details: JsonObject): Map<String, String> = buildMap {
        details.forEach { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNullIfNotString()?.let { put(key, it) }
        }
    }

    private fun parseChannels(display: JsonElement?): Set<FocusChannel> {
        val names = when (display) {
            null -> emptyList()
            is JsonPrimitive -> listOfNotNull(display.contentOrNullIfNotString())
            is JsonArray -> display.mapNotNull { (it as? JsonPrimitive)?.contentOrNullIfNotString() }
            else -> emptyList()
        }
        // display 缺省或写成空数组都按协议默认走日志
        if (names.isEmpty()) return setOf(FocusChannel.Log)
        return names.mapTo(mutableSetOf()) { name ->
            when (name.lowercase()) {
                "toast" -> FocusChannel.Toast
                "notification" -> FocusChannel.Notification
                "dialog" -> FocusChannel.Dialog
                "modal" -> FocusChannel.Modal
                else -> FocusChannel.Log
            }
        }
    }

    /** JSON 的 `null` 字面量也是 JsonPrimitive，直接取 content 会拿到字符串 "null" */
    private fun JsonPrimitive.contentOrNullIfNotString(): String? =
        if (this is JsonNull) null else content
}
