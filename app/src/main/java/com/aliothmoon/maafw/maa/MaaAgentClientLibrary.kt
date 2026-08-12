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
