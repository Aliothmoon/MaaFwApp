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
 * 这条是 PI 作者写给终端用户的话，外壳负责替换占位符再按 [channels] 投递
 */
data class FocusMessage(
    val content: String,
    val channels: Set<FocusChannel>,
)

/**
 * 协议的 `display` 有五档，Android 外壳只落地三档
 *
 * `dialog` / `modal` 一并归到 [Log]：modal 的语义是「弹出后任务暂停等待用户确认」，
 * 而回调是 oneway 单向通知，没有让外壳把 pipeline 卡住再放行的通道；桌面端 MXU 同样
 * 把这两档当日志处理。认不出的档也落 [Log]——协议加档时少显示一处，好过整条丢掉
 */
enum class FocusChannel { Log, Toast, Notification }

/**
 * 从回调的 `details_json` 里取出本条消息对应的模板
 *
 * 节点的整份 `focus` 字典会挂在**每一条**该节点的回调详情上，取的时候要按 message 名索引
 */
object FocusParser {

    /**
     * 带 focus 的回调是极少数，先按串筛掉其余的
     *
     * 一次长跑有几千条节点回调，为它们各解一次完整 JSON 只是白烧 binder 线程
     */
    private const val FOCUS_MARKER = "\"focus\""

    private const val FOCUS_KEY = "focus"
    private const val CONTENT_KEY = "content"
    private const val DISPLAY_KEY = "display"

    /** 闭括号必须转义：Android 的 ICU 正则不接受孤立的 `}`（与 RunPlanBuilder 同款坑） */
    private val PLACEHOLDER = Regex("""\{([^{}]+)\}""")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 返回 null 表示这条回调没有模板，按原始转储处理 */
    fun parse(message: String, detailsJson: String): FocusMessage? {
        if (message.isEmpty() || !detailsJson.contains(FOCUS_MARKER)) return null

        val details = runCatching { json.parseToJsonElement(detailsJson) }.getOrNull() as? JsonObject
            ?: return null
        val entry = (details[FOCUS_KEY] as? JsonObject)?.get(message) ?: return null

        val (rawContent, channels) = when (entry) {
            // 简写：等价于 display: "log"
            is JsonPrimitive -> entry.contentOrNullIfNotString() to setOf(FocusChannel.Log)
            is JsonObject -> {
                val content = (entry[CONTENT_KEY] as? JsonPrimitive)?.contentOrNullIfNotString()
                content to parseChannels(entry[DISPLAY_KEY])
            }

            else -> return null
        }
        // content 缺省是合法的：那种条目只用来配 trace，没有要展示的东西
        if (rawContent.isNullOrBlank()) return null

        return FocusMessage(substitute(rawContent, details), channels)
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
                else -> FocusChannel.Log
            }
        }
    }

    /** 未命中的占位符原样透传，与 RunPlanBuilder 一致：`{…}` 不全是要替换的东西 */
    private fun substitute(template: String, details: JsonObject): String =
        PLACEHOLDER.replace(template) { match ->
            (details[match.groupValues[1]] as? JsonPrimitive)?.content ?: match.value
        }

    /** JSON 的 `null` 字面量也是 JsonPrimitive，直接取 content 会拿到字符串 "null" */
    private fun JsonPrimitive.contentOrNullIfNotString(): String? =
        if (this is JsonNull) null else content
}
