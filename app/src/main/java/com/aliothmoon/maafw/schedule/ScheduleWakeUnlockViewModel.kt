package com.aliothmoon.maafw.schedule

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maafw.domain.GestureRecordResult
import com.aliothmoon.maafw.domain.GestureRecordStatus
import com.aliothmoon.maafw.domain.UnlockCredential
import com.aliothmoon.maafw.domain.UnlockGesture
import com.aliothmoon.maafw.settings.AppSettingsGateway
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 定时页右上角「唤醒解锁」（移植自 MaaMeow 的 ScheduleWakeUnlockViewModel）
 *
 * 解锁方式与 PIN 落 AppSettings，手势落 [UnlockGestureStore]；
 * 自测与录制都整段在特权进程里跑，app 这边只发起、再轮询结果
 */
class ScheduleWakeUnlockViewModel(
    private val appSettings: AppSettingsGateway,
    private val engine: WakeUnlockEngine,
    private val gestureStore: UnlockGestureStore,
) : ViewModel() {

    val wakeUnlockType: StateFlow<String> = appSettings.wakeUnlockType
    val wakeCredential: StateFlow<String> = appSettings.wakeCredential
    val unlockGesture: StateFlow<UnlockGesture?> = gestureStore.gesture

    fun setWakeUnlockType(type: String) {
        viewModelScope.launch { appSettings.setWakeUnlockType(type) }
    }

    fun setWakeCredential(credential: String) {
        viewModelScope.launch { appSettings.setWakeCredential(credential) }
    }

    // ───────────────── 自测 ─────────────────

    sealed interface WakeTestState {
        data object Testing : WakeTestState
        data class Done(val result: WakeResult) : WakeTestState
    }

    private val _wakeTestState = MutableStateFlow<WakeTestState?>(null)

    /** null = 没在测 */
    val wakeTestState: StateFlow<WakeTestState?> = _wakeTestState.asStateFlow()

    fun runWakeTest() {
        if (_wakeTestState.value == WakeTestState.Testing) return
        viewModelScope.launch {
            _wakeTestState.value = WakeTestState.Testing
            val type = appSettings.wakeUnlockType.value
            val credential = UnlockCredential.of(
                type = type,
                pin = appSettings.wakeCredential.value,
                gestureJson = if (type == UnlockCredential.TYPE_GESTURE) gestureStore.readJson() else "",
            )
            _wakeTestState.value = WakeTestState.Done(engine.testUnlock(credential))
        }
    }

    fun clearWakeTestResult() {
        _wakeTestState.value = null
    }

    // ───────────────── 手势录制 ─────────────────

    /** 录制期间 app 在锁屏后面，只能靠轮询把结果收回来 */
    sealed interface GestureRecordState {
        /** 已发起，等特权进程锁屏再亮屏 */
        data object Preparing : GestureRecordState
        data object Recording : GestureRecordState
        data class Done(val steps: Int) : GestureRecordState
        data class Failed(val result: WakeResult) : GestureRecordState

        /** 录到了但没落盘：不当成已录，免得进程一死才发现手势没了 */
        data object SaveFailed : GestureRecordState
    }

    private val _gestureRecordState = MutableStateFlow<GestureRecordState?>(null)

    /** null = 空闲 */
    val gestureRecordState: StateFlow<GestureRecordState?> = _gestureRecordState.asStateFlow()

    private var recordJob: Job? = null

    fun startGestureRecord() {
        if (recordJob?.isActive == true) return
        recordJob = viewModelScope.launch {
            _gestureRecordState.value = GestureRecordState.Preparing
            if (!engine.startGestureRecord(RECORD_TIMEOUT_MS)) {
                _gestureRecordState.value = GestureRecordState.Failed(WakeResult.IPC_FAILED)
                return@launch
            }
            _gestureRecordState.value = GestureRecordState.Recording
            awaitRecordResult()
        }
    }

    /** 进页面时补一次：锁屏期间 VM 若被重建，轮询协程会跟着没了 */
    fun refreshGestureRecord() {
        if (recordJob?.isActive == true) return
        recordJob = viewModelScope.launch {
            val result = engine.pollGestureRecord() ?: return@launch
            if (result.status.isTerminal) {
                consume(result)
            } else if (result.status == GestureRecordStatus.RECORDING) {
                _gestureRecordState.value = GestureRecordState.Recording
                awaitRecordResult()
            }
        }
    }

    fun cancelGestureRecord() {
        recordJob?.cancel()
        recordJob = viewModelScope.launch {
            engine.cancelGestureRecord()
            // 远端取消不留终态，本地直接收尾
            _gestureRecordState.value = GestureRecordState.Failed(WakeResult.RECORD_CANCELLED)
        }
    }

    fun clearGestureRecordState() {
        _gestureRecordState.value = null
    }

    fun clearGesture() {
        viewModelScope.launch { gestureStore.clear() }
    }

    private suspend fun awaitRecordResult() {
        val deadline = SystemClock.elapsedRealtime() + RECORD_TIMEOUT_MS + RECORD_GRACE_MS
        // 先轮询再判超时：锁屏期间进程可能被冻结，解冻后这一轮仍要能把结果取回来
        while (true) {
            delay(RECORD_POLL_INTERVAL_MS)
            val result = engine.pollGestureRecord()
            // IDLE：oneway 的 start 还没落地，或远端重启过；继续等而不是当成结束
            if (result != null && result.status.isTerminal) {
                consume(result)
                return
            }
            if (SystemClock.elapsedRealtime() >= deadline) break
        }
        _gestureRecordState.value = GestureRecordState.Failed(WakeResult.RECORD_TIMEOUT)
    }

    private suspend fun consume(result: GestureRecordResult) {
        val gesture = result.gesture
        _gestureRecordState.value = if (result.status == GestureRecordStatus.DONE && gesture != null) {
            if (gestureStore.save(gesture)) GestureRecordState.Done(gesture.steps.size) else GestureRecordState.SaveFailed
        } else {
            GestureRecordState.Failed(WakeResult.fromCode(result.errorCode))
        }
    }

    private companion object {
        /** 与特权进程的录制超时对齐，再留一段宽限，免得两边同时判超时 */
        const val RECORD_TIMEOUT_MS = 90_000
        const val RECORD_GRACE_MS = 15_000
        const val RECORD_POLL_INTERVAL_MS = 1_000L
    }
}
