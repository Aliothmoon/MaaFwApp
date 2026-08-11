package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.notification.NotificationCenter

/**
 * 整轮结束时播报结局
 *
 * 借 [RunEnvHook] 的形状而不是让 UI 观察终态：定时触发时 Activity 可能压根不在，
 * 挂在 ViewModel 上的观察者会整轮漏掉。与 [SessionLogHook] 同一路数——
 * `Release(reason)` 正好是「结局已定」那一刻
 *
 * engage 什么都不做，只为占一个收尾位。不 gating：推送发不出去不该反过来拦住任务
 */
class NotificationHook(private val center: NotificationCenter) : RunEnvHook {

    override val id: String = "notification"
    override val anchor: Anchor = Anchor.BeforeDispatch
    override val order: Int = HookOrder.NOTIFICATION
    override val gating: Boolean = false

    override suspend fun engage(ctx: RunContext): Release =
        Release { reason -> center.onRunFinished(reason) }
}
