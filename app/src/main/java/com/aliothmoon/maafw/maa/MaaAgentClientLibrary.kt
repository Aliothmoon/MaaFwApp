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

    /**
     * 绑定回环 TCP，port 传 0 即由系统分配；identifier 随即变成实际端口的十进制串
     *
     * Android 上只能走这条：Shizuku 身份（shell 域）在 `/data/local/tmp` 建不了 sock_file，
     * 实测 bind 抛 `zmq::error_t: Permission denied` 直接 abort 掉整个特权进程，
     * 而那个目录是 shell 自己的且能建普通文件——SELinux 卡的是 socket 那一类，且不打 audit
     */
    fun MaaAgentClientCreateTcp(port: Short): Pointer?

    /** identifier 传 null 即由 MaaFramework 生成 uuid 并走 IPC；Android 上不可用，见上 */
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
