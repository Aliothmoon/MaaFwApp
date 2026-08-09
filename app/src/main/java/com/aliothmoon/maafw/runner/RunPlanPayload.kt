package com.aliothmoon.maafw.runner

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * RunPlan 过 binder 的线格式
 * 两端在同一个 APK 里，直接复用同一组类；binder 上只传这一个 JSON 串，不做逐字段 parcel
 */
@Serializable
data class RunPlanPayload(
    /** PI resource 的绝对路径，按声明顺序逐个 MaaResourcePostBundle */
    val resourcePaths: List<String>,
    /** 必须与虚拟显示器的帧缓冲、触摸坐标空间一致，否则 screencap 立即失败 */
    val screenWidth: Int,
    val screenHeight: Int,
    val tasks: List<RuntimeTaskPayload>,
)

@Serializable
data class RuntimeTaskPayload(
    val taskName: String,
    val entry: String,
    /**
     * 有序 patch，原样作为 JSON array 传给 MaaTaskerPostTask
     * MaaFramework 侧按数组顺序合并（Task/Context.cpp 的 is_array 分支），不能在这里提前合并成一个对象
     */
    val pipelineOverrides: List<JsonObject>,
)

/** 整轮执行的结果，取值随 IMaaRunnerCallback.onFinished 过 binder */
object RunOutcome {
    const val COMPLETED = 0
    const val COMPLETED_WITH_FAILURES = 1
    const val CANCELLED = 2
    const val FAILED = 3
}

/** 线格式的编解码；宽容未知字段，便于 app 与特权进程版本短暂不一致时不至于直接崩 */
val runPlanWireJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
