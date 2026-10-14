package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.privileged.RemoteAccessFailure
import com.aliothmoon.maafw.privileged.RemoteAccessPort
import com.aliothmoon.maafw.privileged.RemoteAccessState
import com.aliothmoon.maafw.privileged.remoteAccessMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 提权后端没就绪就别往下走：后面的解锁、拉起、投递全靠它
 *
 * 排第一道，也就早于 force 抢占：后端起不来时不该先把在跑的那一轮停掉
 *
 * 特权进程已经连着当前后端就直接放行：Shizuku / su 停了，它拉起的进程照样能跑
 *
 * 定时触发多半是闹钟拉起的冷进程，Shizuku 的 binder 与 Root 的授权都是异步到的，
 * 第一眼的快照会误报，等一小会儿再判。只等不请求：请求授权是副作用，留给 [PrivilegedConnectHook]
 *
 * 未授权只拦定时：手动与悬浮窗那轮用户就在跟前，连接时当场请求授权
 */
class PrivilegedAccessPrecheck(
    private val access: RemoteAccessPort,
    private val servicePort: PrivilegedServicePort,
    private val settleTimeoutMs: Long = SETTLE_TIMEOUT_MS,
) : RunPrecheck {

    override suspend fun evaluate(ctx: RunContext): Verdict {
        var state = access.refresh()
        if (servicePort.serviceOrNull() != null && servicePort.currentBackend == state.configuredBackend) {
            return Verdict.Pass
        }
        if (!state.ready() && ctx.trigger is RunTrigger.Schedule) state = settle()
        val backend = state.configuredBackend
        return when {
            !state.isAvailable(backend) ->
                Verdict.Block(remoteAccessMessage(backend, RemoteAccessFailure.UNAVAILABLE))

            !state.isGranted(backend) && ctx.trigger is RunTrigger.Schedule ->
                Verdict.Block(remoteAccessMessage(backend, RemoteAccessFailure.NOT_GRANTED))

            else -> Verdict.Pass
        }
    }

    // 轮询而不是等 state 推送：冷进程里状态监听在 postCreate 才挂上，可能晚于这里
    private suspend fun settle(): RemoteAccessState {
        val settled = withTimeoutOrNull(settleTimeoutMs) {
            var state = access.refresh()
            while (!state.ready()) {
                delay(POLL_INTERVAL_MS)
                state = access.refresh()
            }
            state
        }
        return settled ?: access.refresh()
    }

    private fun RemoteAccessState.ready(): Boolean = isGranted(configuredBackend)

    private companion object {
        const val SETTLE_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 250L
    }
}
