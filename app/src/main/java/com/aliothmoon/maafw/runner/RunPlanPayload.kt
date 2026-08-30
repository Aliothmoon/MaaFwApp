package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.constant.DisplayMode
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
    /**
     * 必须与显示器的帧缓冲、触摸坐标空间一致，否则 screencap 立即失败
     * 主屏模式下这两个值被忽略：尺寸由特权进程侧的采集器供数，见 [com.aliothmoon.maafw.constant.DisplayMode]
     */
    val screenWidth: Int,
    val screenHeight: Int,
    /** [com.aliothmoon.maafw.constant.DisplayMode] 取值；决定 Runner 找谁要屏幕尺寸 */
    val displayMode: Int = DisplayMode.BACKGROUND,
    /**
     * 后台模式 StartApp 前是否无条件强停目标应用。
     * 线格式默认 true 是为了旧 payload 解码后保持既有行为；用户设置本身默认 true。
     */
    val forceRestartApp: Boolean = true,
    val tasks: List<RuntimeTaskPayload>,
    /** PI 声明的 agent，按声明顺序；空表示本次不起 agent */
    val agents: List<AgentPayload> = emptyList(),
    /** 特权进程从这个 APK 里读 agent 运行时的 assets；它自己解析包路径不如 app 侧直接给可靠 */
    val apkPath: String = "",
    /** agent child 的 LD_LIBRARY_PATH 与 MAAFW_BINARY_PATH 落点 */
    val nativeLibraryDir: String = "",
    /**
     * 注入每个 agent child 的 `PI_*`（见 [PiAgentEnv]）；对本轮所有 agent 相同，故放顶层
     * 特权进程侧还会补 `PI_CLIENT_MAAFW_VERSION`
     */
    val piEnv: Map<String, String> = emptyMap(),
)

@Serializable
data class AgentPayload(
    /** 只留作诊断：Android 上不解释也不执行，见 docs/pi-compatibility.md */
    val childExec: String,
    /** identifier 由 Runner 追加到末位，此处不含 */
    val childArgs: List<String>,
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
