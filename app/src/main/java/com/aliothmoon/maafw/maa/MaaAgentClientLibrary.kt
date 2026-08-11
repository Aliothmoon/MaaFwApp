package com.aliothmoon.maafw.maa

import com.aliothmoon.maafw.third.Ln
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * `libMaaAgentClient.so` 的 JNA 声明，按 `include/MaaAgentClient/MaaAgentClientAPI.h` 手写
 *
 * 只声明本项目用到的子集：注册 sink 的三个函数（把事件转发给 agent）上游 MaaPiCli 自己都没调，暂不接
 * 传输方式由 identifier 字符串单独决定：纯数字 1..65535 走 tcp://127.0.0.1:<port>，
 * 其余走 $TMPDIR/maafw-agent-<id>.sock（`AgentClient.cpp` / `AgentServer.cpp` 各自 parse 同一个串）
 */
interface MaaAgentClientLibrary : Library {

    fun MaaAgentClientCreateTcp(port: Short): Pointer?

    fun MaaAgentClientCreateV2(identifier: Pointer?): Pointer?

    fun MaaAgentClientDestroy(client: Pointer?)

    /** 出参走 MaaStringBuffer；调用方负责创建与销毁那个 buffer */
    fun MaaAgentClientIdentifier(client: Pointer?, identifier: Pointer?): Byte

    fun MaaAgentClientBindResource(client: Pointer?, res: Pointer?): Byte

    /** 阻塞直到 child 侧 StartUp 完成或超时；超时由 [MaaAgentClientSetTimeout] 决定 */
    fun MaaAgentClientConnect(client: Pointer?): Byte

    fun MaaAgentClientDisconnect(client: Pointer?): Byte

    fun MaaAgentClientAlive(client: Pointer?): Byte

    fun MaaAgentClientSetTimeout(client: Pointer?, milliseconds: Long): Byte
}

/**
 * 特权进程里加载 `libMaaAgentClient.so`
 *
 * 与 [MaaFrameworkLoader] 同款：失败不抛，返回 null 由调用方转成可读失败
 * 不配 agent 运行时的包永远不会走到这里，加载失败也不影响无 agent 的 PI
 */
object MaaAgentClientLoader {

    private const val LIBRARY_NAME = "MaaAgentClient"

    val library: MaaAgentClientLibrary? by lazy {
        runCatching {
            Native.load(LIBRARY_NAME, MaaAgentClientLibrary::class.java).also {
                Ln.i("MaaAgentClient loaded")
            }
        }.onFailure {
            Ln.e("Failed to load $LIBRARY_NAME: ${it.message}")
        }.getOrNull()
    }
}
