package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.constant.WakeUnlockResult
import com.aliothmoon.maafw.domain.GestureRecordResult
import com.aliothmoon.maafw.domain.UnlockCredential
import com.aliothmoon.maafw.domain.UnlockGestureJson
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import kotlinx.coroutines.withContext
import timber.log.Timber

/** 特权侧回的 [WakeUnlockResult] 码 → 带文案的结局 */
enum class WakeResult(val code: Int, val message: UiText) {
    OK(WakeUnlockResult.OK, uiTextOf(R.string.wake_result_ok)),
    WAKE_FAILED(WakeUnlockResult.WAKE_FAILED, uiTextOf(R.string.wake_result_wake_failed)),
    CREDENTIAL_REQUIRED(WakeUnlockResult.CREDENTIAL_REQUIRED, uiTextOf(R.string.wake_result_credential_required)),
    CREDENTIAL_REJECTED(WakeUnlockResult.CREDENTIAL_REJECTED, uiTextOf(R.string.wake_result_credential_rejected)),
    NO_KEYGUARD(WakeUnlockResult.NO_KEYGUARD, uiTextOf(R.string.wake_result_no_keyguard)),
    UNSUPPORTED(WakeUnlockResult.UNSUPPORTED, uiTextOf(R.string.wake_result_unsupported)),
    LOCK_FAILED(WakeUnlockResult.LOCK_FAILED, uiTextOf(R.string.wake_result_lock_failed)),
    GESTURE_EMPTY(WakeUnlockResult.GESTURE_EMPTY, uiTextOf(R.string.wake_result_gesture_empty)),
    GESTURE_SCREEN_MISMATCH(
        WakeUnlockResult.GESTURE_SCREEN_MISMATCH,
        uiTextOf(R.string.wake_result_gesture_screen_mismatch),
    ),
    RECORD_NO_DEVICE(WakeUnlockResult.RECORD_NO_DEVICE, uiTextOf(R.string.wake_result_record_no_device)),
    RECORD_TIMEOUT(WakeUnlockResult.RECORD_TIMEOUT, uiTextOf(R.string.wake_result_record_timeout)),
    RECORD_CANCELLED(WakeUnlockResult.RECORD_CANCELLED, uiTextOf(R.string.wake_result_record_cancelled)),
    RECORD_NO_TOUCH(WakeUnlockResult.RECORD_NO_TOUCH, uiTextOf(R.string.wake_result_record_no_touch)),
    IPC_FAILED(WakeUnlockResult.IPC_FAILED, uiTextOf(R.string.wake_result_ipc_failed)),
    ;

    /** 解开了，或者本来就没锁：两种都可以往下跑 */
    val isUnlocked: Boolean get() = this == OK || this == NO_KEYGUARD

    companion object {
        /** 认不出的码当 IPC 失败；要把码带进文案的用 [fromCodeOrNull] */
        fun fromCode(code: Int): WakeResult = fromCodeOrNull(code) ?: IPC_FAILED

        fun fromCodeOrNull(code: Int): WakeResult? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 唤醒解锁页用的特权调用（移植自 MaaMeow 的 WakeUnlockEngine）
 *
 * 定时触发那条路在 `WakeUnlockHook` 里，按同一份 [UnlockCredential] 分派；
 * 这里只服务设置页：自测与手势录制都是用户当面点的，没连上就该顺带授权、重绑
 */
class WakeUnlockEngine(private val servicePort: PrivilegedServicePort) {

    /** 先上锁息屏，再按凭证解一次；整段在特权进程里跑，app 息屏后被挂起也不影响 */
    suspend fun testUnlock(credential: UnlockCredential): WakeResult = when (credential) {
        UnlockCredential.Swipe -> call("testUnlock") { it.testUnlock("") }
        is UnlockCredential.Pin -> call("testUnlock") { it.testUnlock(credential.digits) }
        is UnlockCredential.Gesture -> call("testUnlockGesture") { it.testUnlockGesture(credential.json) }
    }

    /** 立即返回；特权进程先锁屏，随后等用户手动解锁一次 */
    suspend fun startGestureRecord(timeoutMs: Int): Boolean = withContext(MaaDispatchers.IO) {
        runCatching {
            servicePort.useService { it.startGestureRecord(timeoutMs) }
            true
        }.getOrElse {
            Timber.w(it, "startGestureRecord: IPC failed")
            false
        }
    }

    /**
     * 取一次录制结果；终态被取走一次即清，重复调用不会重复消费
     *
     * 只用现成连接、不拉起服务：每次进页都会问一次，没在录就不该为它去授权
     * @return null = 没连上或调用失败，调用方按「还在录」处理
     */
    suspend fun pollGestureRecord(): GestureRecordResult? = withContext(MaaDispatchers.IO) {
        val service = servicePort.serviceOrNull() ?: return@withContext null
        runCatching {
            UnlockGestureJson.decodeFromString(GestureRecordResult.serializer(), service.pollGestureRecord())
        }.getOrElse {
            Timber.w(it, "pollGestureRecord failed")
            null
        }
    }

    suspend fun cancelGestureRecord() {
        withContext(MaaDispatchers.IO) {
            runCatching { servicePort.serviceOrNull()?.cancelGestureRecord() }
                .onFailure { Timber.w(it, "cancelGestureRecord: IPC failed") }
        }
    }

    private suspend fun call(name: String, block: (RemoteService) -> Int): WakeResult =
        withContext(MaaDispatchers.IO) {
            val result = runCatching { WakeResult.fromCode(servicePort.useService { block(it) }) }
                .getOrElse {
                    Timber.w(it, "%s: IPC failed", name)
                    WakeResult.IPC_FAILED
                }
            Timber.i("%s -> %s", name, result)
            result
        }
}
