package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.runner.AgentPayload

/**
 * 拉起一个 agent child 所需的全部运行期输入
 * 除 [identifier] 由 MaaAgentClient 现场生成外，其余都来自 RunPlanPayload 与 PI 解包根
 */
data class AgentLaunchRequest(
    /** PI agent 数组里的序号；与运行时描述的 runtimes[] 一一对应 */
    val index: Int,
    val agent: AgentPayload,
    val identifier: String,
    val apkPath: String,
    val nativeLibraryDir: String,
    /** 对齐上游 MaaPiCli 的 `agent.cwd = resource_dir_` */
    val workingDir: String,
)

/** child 的句柄；[close] 之后进程必须已终止 */
interface AgentSession : AutoCloseable {
    fun isAlive(): Boolean
}

interface AgentHost {
    /** @throws AgentLaunchException 运行时缺失、序号越界、或 exec 失败 */
    fun launch(request: AgentLaunchRequest): AgentSession
}

/** 失败一律带可读原因：PI 声明了 agent 却起不来时，任务会整批失败，日志里必须能一眼看出为什么 */
class AgentLaunchException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 显式关闭 agent 支持；生产路径不用，留给不带运行时的构建与测试 */
object NoAgentHost : AgentHost {
    override fun launch(request: AgentLaunchRequest): Nothing =
        throw AgentLaunchException("本包未带 agent 运行时，无法拉起 ${request.agent.childExec}")
}
