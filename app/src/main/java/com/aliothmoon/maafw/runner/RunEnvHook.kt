package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.i18n.UiText

/** 挂载锚点；两者之间隔着一次 [RunnerPort.start] */
enum class Anchor {
    /**
     * 投递前。此刻虚拟屏还不存在，目标包名也拿不到——
     * `start_app` 的包名来自 pipeline，外壳不维护包名表（docs/privileged-runtime.md §4）
     */
    BeforeDispatch,

    /**
     * 受理后。保活只能挂这里：前台服务 onCreate 读 RunnerState 判去留
     * 禁止 gating：远端已经 Accepted，再拦会留下孤儿轮
     */
    AfterAccepted,
}

/** 收尾动作；整轮结束时按入栈的逆序调用 */
fun interface Release {
    suspend operator fun invoke(reason: RunEndReason)
}

/** [RunEnvHook.engage] 的预期结局；意外崩溃仍由编排层 catch */
sealed interface EngageResult {
    /** 本轮不适用，或做了但不必撤 */
    data class Skipped(val release: Release? = null) : EngageResult

    data class Engaged(val release: Release) : EngageResult

    /**
     * 预期失败；中不中止看 [RunEnvHook.gating]
     * [notRun] 仅 gating 失败时有意义：倒计时取消用 [NotRunCause.Cancelled]，其余默认 HookFailed
     */
    data class Failed(
        val reason: UiText,
        val notRun: NotRunCause = NotRunCause.HookFailed,
    ) : EngageResult
}

/**
 * 一轮运行期间对设备环境的一处可逆改动
 *
 * [engage] 返回 [EngageResult] 而不是配一对 before/after 回调，是为了让**采样值捕获进闭包**：
 * 「跑完自动熄屏」要知道本轮开始时手机是不是本来就待机，而那个值只能在唤醒**之前**采。
 * 它在自己的 engage 里采完写进闭包，别的挂载物看不见也不关心——否则就得有一个
 * 随挂载物数量膨胀的公共结构来存这些采样
 */
interface RunEnvHook {

    val id: String

    val anchor: Anchor

    /**
     * 同 anchor 内的 engage 顺序，小的先；收尾一律逆序
     *
     * 顺序不交给用户：环境动作之间有物理依赖（先亮屏才能解锁、撤屏保必须早于熄屏），
     * 用户排出来的顺序会是错的。逆序收尾能自动覆盖成对依赖
     */
    val order: Int

    /** [EngageResult.Failed] 是否中止整轮。解锁失败还硬跑 = 对着锁屏识别到超时 */
    val gating: Boolean

    suspend fun engage(ctx: RunContext): EngageResult
}

/** 收尾理由；投递之后的结局直接复用 [ExecutionResult]，不另造一套平行分类 */
sealed interface RunEndReason {
    /** 没投出去就结束，撤销栈里只有 [Anchor.BeforeDispatch] 那批 */
    data class NotRun(val cause: NotRunCause) : RunEndReason

    /** 投出去了；手动 Stop 落在 [ExecutionResult.Cancelled] */
    data class Ran(val result: ExecutionResult) : RunEndReason
}

enum class NotRunCause {
    /** 声明为 gating 的挂载物失败或超时 */
    HookFailed,

    /** RunnerPort 拒绝了投递 */
    Rejected,

    /** 投递前被取消 */
    Cancelled,
}
