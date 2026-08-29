package com.aliothmoon.maafw.runner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class FocusDialogRequest(
    val id: String,
    val content: String,
    val modal: Boolean,
    val executionId: String? = null,
)

/**
 * dialog / modal 的进程级展示队列
 *
 * 不能挂 Activity ViewModel：定时运行时可能没有界面，旋转与重建也不该丢掉正在等待
 * 确认的 modal。runner 回到 Idle 时只清 modal；未读的非阻塞 dialog 保留。
 */
class FocusDialogController(
    focusDispatcher: FocusDispatcher,
    private val runnerPort: RunnerPort,
    scope: CoroutineScope,
) {

    private val _requests = MutableStateFlow<List<FocusDialogRequest>>(emptyList())
    val requests: StateFlow<List<FocusDialogRequest>> = _requests.asStateFlow()
    /** 判定/入队必须和执行轮次清理互斥，否则旧 modal 可以在清理之后重新入队 */
    private val modalMutex = Mutex()

    init {
        scope.launch {
            focusDispatcher.resolved.collect { focus ->
                if (!focus.displayable) return@collect
                val modalId = focus.modalId?.takeIf(String::isNotBlank)
                when {
                    FocusChannel.Modal in focus.channels && modalId != null -> {
                        modalMutex.withLock {
                            if (focus.isActionableModalFor(runnerPort.state.value)) {
                                offerModal(modalId, focus.content, focus.executionId)
                            }
                        }
                    }

                    FocusChannel.Dialog in focus.channels &&
                        (FocusChannel.Modal !in focus.channels || modalId == null) ->
                        offerDialog(focus.content)
                }
            }
        }
        scope.launch {
            runnerPort.state.collect { state ->
                modalMutex.withLock {
                    clearModalsFor(state)
                }
            }
        }
    }

    private fun offerDialog(content: String) {
        _requests.update { requests ->
            requests + FocusDialogRequest(
                id = UUID.randomUUID().toString(),
                content = content,
                modal = false,
            )
        }
    }

    private fun offerModal(id: String, content: String, executionId: String?) {
        val sourceExecutionId = executionId?.takeIf(String::isNotBlank) ?: return
        _requests.update { requests ->
            if (requests.any { it.modal && it.id == id }) {
                requests
            } else {
                requests + FocusDialogRequest(
                    id = id,
                    content = content,
                    modal = true,
                    executionId = sourceExecutionId,
                )
            }
        }
    }

    private fun clearModalsFor(state: RunnerState) {
        val activeExecutionId = state.activeExecution?.executionId?.takeIf(String::isNotBlank)
        _requests.update { requests ->
            requests.filterNot { request ->
                request.modal && (
                    state.phase == RunnerPhase.Idle ||
                        activeExecutionId == null ||
                        request.executionId != activeExecutionId
                    )
            }
        }
    }

    fun dismissDialog(id: String) {
        remove(id, modal = false)
    }

    fun confirmDialog(id: String) {
        remove(id, modal = false)
    }

    /** 只有特权进程确认放行成功才移除；失败保留请求，用户可以再点一次 */
    suspend fun confirmModal(id: String): Boolean {
        if (_requests.value.none { it.modal && it.id == id }) return true
        val acknowledged = runnerPort.acknowledgeModalFocus(id)
        if (acknowledged) remove(id, modal = true)
        return acknowledged
    }

    /** 停止请求交给现有 runner 流程；请求等 runner 回到 Idle 后统一清理 */
    suspend fun stopModal(id: String): Boolean {
        if (_requests.value.none { it.modal && it.id == id }) return true
        return runnerPort.stop() is RunnerCommandResult.Accepted
    }

    private fun remove(id: String, modal: Boolean) {
        _requests.update { requests ->
            requests.filterNot { it.id == id && it.modal == modal }
        }
    }
}
