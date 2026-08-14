package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.maa.MaaMsg
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 从 `Node.PipelineNode.*` 取出给人看的节点名
 *
 * Starting 的 `name` 是「正在识别谁的 next」；Succeeded 另带命中节点
 * `node_details.name`。识别 / Action / NextList 不走这里——那些按帧刷
 */
object PipelineNodeStatus {

    fun nameOf(message: String, detailsJson: String): String? {
        if (message != MaaMsg.NODE_PIPELINE_NODE_STARTING &&
            message != MaaMsg.NODE_PIPELINE_NODE_SUCCEEDED
        ) {
            return null
        }
        val details = parse(detailsJson) ?: return null
        return details.obj("node_details")?.string("name") ?: details.string("name")
    }

    private fun parse(raw: String): JsonObject? =
        runCatching { JSON.parseToJsonElement(raw) }.getOrNull() as? JsonObject

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
}

/** Live Update 状态句：focus 优先，否则当前 pipeline 节点，再否则普通日志 */
fun resolveLiveUpdateStatus(focus: String?, pipelineNode: String?, fallback: String?): String? =
    firstNonBlank(focus) ?: firstNonBlank(pipelineNode) ?: firstNonBlank(fallback)

private fun firstNonBlank(text: String?): String? =
    text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
