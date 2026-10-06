package com.aliothmoon.maafw.maa

import com.aliothmoon.maafw.third.Ln
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

interface MaaAgentClientLibrary : Library {

    fun MaaAgentClientCreateTcp(port: Short): Pointer?

    fun MaaAgentClientCreateV2(identifier: Pointer?): Pointer?

    fun MaaAgentClientDestroy(client: Pointer?)

    /** 出参走 MaaStringBuffer；调用方负责创建与销毁那个 buffer */
    fun MaaAgentClientIdentifier(client: Pointer?, identifier: Pointer?): Byte

    fun MaaAgentClientBindResource(client: Pointer?, res: Pointer?): Byte

    /**
     * 把该对象的事件转给 agent，agent 侧 `MaaAgentServerAdd*Sink` 的监听器才收得到
     * client 只记裸指针：再次登记、Disconnect、Destroy 都会对上一次登记的对象 remove_sink
     */
    fun MaaAgentClientRegisterResourceSink(client: Pointer?, res: Pointer?): Byte

    fun MaaAgentClientRegisterControllerSink(client: Pointer?, ctrl: Pointer?): Byte

    fun MaaAgentClientRegisterTaskerSink(client: Pointer?, tasker: Pointer?): Byte

    /** 阻塞直到 child 侧 StartUp 完成或超时；超时由 [MaaAgentClientSetTimeout] 决定 */
    fun MaaAgentClientConnect(client: Pointer?): Byte

    fun MaaAgentClientDisconnect(client: Pointer?): Byte

    fun MaaAgentClientAlive(client: Pointer?): Byte

    fun MaaAgentClientSetTimeout(client: Pointer?, milliseconds: Long): Byte
}

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
