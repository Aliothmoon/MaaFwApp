package com.aliothmoon.maafw.maa

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Pointer

/**
 * MaaFramework C API 的 JNA 声明
 *
 * 官方没有 Java binding（`source/binding/` 只有 NodeJS 与 Python），这里按 `include/MaaFramework/` 的
 * 头文件手写，只声明本项目用到的子集。改动前先对照头文件，签名以头文件为准
 *
 * 类型映射：`MaaBool`(uint8) → Byte，`MaaId`/`MaaSize`(int64/uint64) → Long，`MaaStatus`(int32) → Int
 */
interface MaaFrameworkLibrary : Library {

    fun MaaVersion(): String?

    fun MaaGlobalSetOption(key: Int, value: Pointer?, valSize: Long): Byte

    // ── Resource ──

    fun MaaResourceCreate(): Pointer?

    fun MaaResourceDestroy(res: Pointer?)

    fun MaaResourceAddSink(res: Pointer?, sink: MaaEventCallback?, transArg: Pointer?): Long

    fun MaaResourcePostBundle(res: Pointer?, path: String): Long

    fun MaaResourceWait(res: Pointer?, id: Long): Int

    fun MaaResourceLoaded(res: Pointer?): Byte

    fun MaaResourceClear(res: Pointer?): Byte

    // ── Controller ──

    fun MaaAndroidNativeControllerCreate(configJson: String): Pointer?

    fun MaaControllerDestroy(ctrl: Pointer?)

    fun MaaControllerAddSink(ctrl: Pointer?, sink: MaaEventCallback?, transArg: Pointer?): Long

    fun MaaControllerPostConnection(ctrl: Pointer?): Long

    fun MaaControllerWait(ctrl: Pointer?, id: Long): Int

    fun MaaControllerConnected(ctrl: Pointer?): Byte

    // ── Tasker ──

    fun MaaTaskerCreate(): Pointer?

    fun MaaTaskerDestroy(tasker: Pointer?)

    fun MaaTaskerAddSink(tasker: Pointer?, sink: MaaEventCallback?, transArg: Pointer?): Long

    fun MaaTaskerAddContextSink(tasker: Pointer?, sink: MaaEventCallback?, transArg: Pointer?): Long

    fun MaaTaskerBindResource(tasker: Pointer?, res: Pointer?): Byte

    fun MaaTaskerBindController(tasker: Pointer?, ctrl: Pointer?): Byte

    fun MaaTaskerInited(tasker: Pointer?): Byte

    fun MaaTaskerPostTask(tasker: Pointer?, entry: String, pipelineOverride: String): Long

    fun MaaTaskerStatus(tasker: Pointer?, id: Long): Int

    fun MaaTaskerWait(tasker: Pointer?, id: Long): Int

    fun MaaTaskerRunning(tasker: Pointer?): Byte

    fun MaaTaskerPostStop(tasker: Pointer?): Long

    fun MaaTaskerStopping(tasker: Pointer?): Byte

    // ── StringBuffer ──
    // MaaAgentClient 的 identifier 走的是 buffer 而不是 char*，用完必须 Destroy

    fun MaaStringBufferCreate(): Pointer?

    fun MaaStringBufferDestroy(handle: Pointer?)

    fun MaaStringBufferGet(handle: Pointer?): String?

    // ── ImageBuffer ──
    // focus 模板的 {image} 占位符取的是 controller 手里那张缓存帧

    fun MaaImageBufferCreate(): Pointer?

    fun MaaImageBufferDestroy(handle: Pointer?)

    fun MaaImageBufferIsEmpty(handle: Pointer?): Byte

    /** 拿的是编码后（PNG）的字节，不是裸位图；长度另取 [MaaImageBufferGetEncodedSize] */
    fun MaaImageBufferGetEncoded(handle: Pointer?): Pointer?

    fun MaaImageBufferGetEncodedSize(handle: Pointer?): Long

    /**
     * 取 controller 的缓存截图
     *
     * 尺寸按 controller 的 screenshot target size 缩放过，与设备物理分辨率未必一致
     */
    fun MaaControllerCachedImage(ctrl: Pointer?, buffer: Pointer?): Byte

    /**
     * 对应 `MaaEventCallback`：`void(void* handle, const char* message, const char* details_json, void* trans_arg)`
     * 由 native 线程回调，实现不得抛异常穿回 native。普通事件不得阻塞；
     * blocking modal 是唯一例外，由 MaaRunner 的 modal gate 受控等待
     */
    fun interface MaaEventCallback : Callback {
        operator fun invoke(handle: Pointer?, message: String?, details: String?, transArg: Pointer?)
    }
}

/** `MaaStatusEnum` */
object MaaStatus {
    const val INVALID = 0
    const val PENDING = 1000
    const val RUNNING = 2000
    const val SUCCEEDED = 3000
    const val FAILED = 4000

    fun isDone(status: Int): Boolean = status == SUCCEEDED || status == FAILED
}

/** 本项目用到的 `MaaGlobalOptionEnum` */
object MaaGlobalOption {
    const val LOG_DIR = 1
    const val SAVE_DRAW = 2
    const val STDOUT_LEVEL = 4
    const val DEBUG_MODE = 6
    const val SAVE_ON_ERROR = 7
}

/** `MaaLoggingLevelEnum` */
object MaaLoggingLevel {
    const val OFF = 0
    const val ERROR = 2
    const val INFO = 4
    const val DEBUG = 5
}

/** `MaaMsg.h` 里本项目会分派的消息名 */
object MaaMsg {
    const val RESOURCE_LOADING_STARTING = "Resource.Loading.Starting"
    const val RESOURCE_LOADING_SUCCEEDED = "Resource.Loading.Succeeded"
    const val RESOURCE_LOADING_FAILED = "Resource.Loading.Failed"

    const val CONTROLLER_ACTION_STARTING = "Controller.Action.Starting"
    const val CONTROLLER_ACTION_SUCCEEDED = "Controller.Action.Succeeded"
    const val CONTROLLER_ACTION_FAILED = "Controller.Action.Failed"

    const val TASKER_TASK_STARTING = "Tasker.Task.Starting"
    const val TASKER_TASK_SUCCEEDED = "Tasker.Task.Succeeded"
    const val TASKER_TASK_FAILED = "Tasker.Task.Failed"

    const val NODE_PIPELINE_NODE_STARTING = "Node.PipelineNode.Starting"
    const val NODE_PIPELINE_NODE_SUCCEEDED = "Node.PipelineNode.Succeeded"
    const val NODE_PIPELINE_NODE_FAILED = "Node.PipelineNode.Failed"

    const val NODE_ACTION_STARTING = "Node.Action.Starting"
    const val NODE_ACTION_SUCCEEDED = "Node.Action.Succeeded"
    const val NODE_ACTION_FAILED = "Node.Action.Failed"
}
