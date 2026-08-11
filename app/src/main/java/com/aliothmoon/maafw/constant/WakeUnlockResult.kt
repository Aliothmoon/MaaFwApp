package com.aliothmoon.maafw.constant

/**
 * [com.aliothmoon.maafw.remote.internal.WakeUnlockController] 的返回码
 *
 * 用 int 而不是枚举：要跨 AIDL，且值直接进 `RemoteService.unlock` 的返回位
 */
object WakeUnlockResult {
    const val OK = 0
    const val WAKE_FAILED = 1
    const val CREDENTIAL_REQUIRED = 2
    const val CREDENTIAL_REJECTED = 3

    /** 设备根本没设锁屏，不必解 */
    const val NO_KEYGUARD = 4

    /** 该 ROM 上对应的隐藏 API 不可用 */
    const val UNSUPPORTED = 5
    const val LOCK_FAILED = 6

    /** IPC 本身失败，只在 app 侧产生 */
    const val IPC_FAILED = -1
}
