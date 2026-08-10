package com.aliothmoon.maafw.remote

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 可执行体的落点；两种形态的取舍见 docs/privileged-runtime.md 的 agent 一节 */
enum class AgentRuntimeLocation {
    /** 随 APK 装进 nativeLibraryDir，零解包；文件名须是 `lib*.so`，否则装机时不会被解压出来 */
    @SerialName("nativeLibs")
    NATIVE_LIBS,

    /** 随 assets 走，首启解包到可执行目录；解释器这类目录树只能走这条 */
    @SerialName("bundle")
    BUNDLE,
}

@Serializable
data class AgentRuntimeEntry(
    val location: AgentRuntimeLocation,
    /** 相对 [location] 所指的根 */
    val executable: String,
    /** 插在 PI 的 child_args 之前，例如解释器的 `-u` */
    val argsPrefix: List<String> = emptyList(),
    /** 值里可用 {bundle} 与 {nativeLibs} 两个占位符，别的一律原样 */
    val env: Map<String, String> = emptyMap(),
)

/**
 * `agent-runtime.json` 的投影：构建期由 `agent.sourceDir` 同步进 assets
 *
 * [runtimes] 按序与 PI 顶层 `agent[]` 一一对应——PI 声明几个 agent，这里就得有几条
 * 数量不符时宁可失败也不猜配对
 */
@Serializable
data class AgentRuntimeDescriptor(
    val runtimes: List<AgentRuntimeEntry> = emptyList(),
) {
    companion object {
        /** 与 bundle 同在 `assets/agent/` 下：那一层由 syncAgentAssets 整体接管，索引文件才落在外面 */
        const val ASSET_PATH = "agent/agent-runtime.json"

        @OptIn(ExperimentalSerializationApi::class)
        private val json = Json {
            ignoreUnknownKeys = true
            allowComments = true
            allowTrailingComma = true
        }

        fun parse(content: String): AgentRuntimeDescriptor = json.decodeFromString(content)
    }
}

/**
 * 只替换 {bundle} 与 {nativeLibs}，不做通用表达式
 * 未带 bundle 的运行时里出现 {bundle} 属于描述写错，直接失败而不是替成空串——
 * 替成空串会得到 `/prefix/lib` 这种看着像绝对路径的值，排查成本高得多
 */
internal fun String.resolveAgentPlaceholders(bundleDir: String?, nativeLibraryDir: String): String {
    if (contains(BUNDLE_PLACEHOLDER) && bundleDir == null) {
        throw AgentLaunchException("运行时描述用了 $BUNDLE_PLACEHOLDER，但该条目不是 bundle 形态")
    }
    return replace(BUNDLE_PLACEHOLDER, bundleDir.orEmpty())
        .replace(NATIVE_LIBS_PLACEHOLDER, nativeLibraryDir)
}

private const val BUNDLE_PLACEHOLDER = "{bundle}"
private const val NATIVE_LIBS_PLACEHOLDER = "{nativeLibs}"
