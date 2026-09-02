package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.i18n.UiText

/** 谁发起的这一轮；决定「需要确认」时有没有人可问 */
sealed interface RunTrigger {
    data object Manual : RunTrigger
    /**
     * [options] 挂在 trigger 上而不是全局设置：这几项都是**逐条规则**的
     * （对齐 MaaMeow 定时编辑页的「高级选项」），而挂载物拿不到策略，只看得见 RunContext
     */
    data class Schedule(
        val strategyId: String,
        val options: ScheduleRunOptions = ScheduleRunOptions(),
    ) : RunTrigger
}

/**
 * 一次发起请求的身份
 *
 * 同一个 id 重复进来直接跳过：AlarmManager 在设备唤醒抖动时会把同一个 PendingIntent
 * 重投一次，没有这道去重就会多跑一轮（第二轮被 RunnerPort 拒，但会多一条失败记账，
 * 用户看着像「响了两次其中一次挂了」）
 *
 * 手动点按每次都是新 id——用户连点两下的去重靠那把投递锁，不靠这个
 */
@JvmInline
value class RunRequestId(val value: String)

/** 一次确认的身份；检查靠它认出「这条问过了，用户点了头」 */
@JvmInline
value class ConfirmToken(val value: String)

/** 定时规则上那几项只对本次触发生效的选项 */
data class ScheduleRunOptions(
    val autoSleepAfterTask: Boolean = false,
    val skipAutoSleepIfAwake: Boolean = true,
    val closeAppAfterTask: Boolean = false,
)

/**
 * 发起过程中来自用户的两个打断信号
 *
 * 用可轮询的标志而不是取消协程：「立即开始」不是取消，它要让挂载物**提前结束等待并继续**。
 * 两个都置位时以「立即开始」为准——用户最后点的是那个
 */
class RunSignals {

    private val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
    private val startNow = java.util.concurrent.atomic.AtomicBoolean(false)

    val cancelRequested: Boolean get() = cancel.get()
    val startNowRequested: Boolean get() = startNow.get()

    fun requestCancel() = cancel.set(true)
    fun requestStartNow() = startNow.set(true)
}

/**
 * 挂载物向外报「正在做什么」
 *
 * 不进 `SessionUiState`：定时触发时 Activity 多半不在，这条要能落到通知上
 */
fun interface RunProgress {
    fun report(hookId: String, detail: UiText)
}

/**
 * 一轮运行的冻结输入，与 [RunPlan] 同生命周期
 *
 * 条件判断只读它，不许现取外部状态：engage 时开关开着盖了屏保、用户中途关掉，
 * 收尾再去读开关就判成「没开」，屏保永远撤不掉
 *
 * 挂载物自己那份配置不进这里——[RunEnvHook.engage] 只在 Start 时刻跑一次，
 * 在里面读到的就已经是冻结值，捕获进闭包即可；塞进来只会让本类随挂载物数量膨胀
 */
class RunContext(
    /** 一轮日志、状态机与迟到回调共用的身份；由编排层生成 */
    val executionId: String = java.util.UUID.randomUUID().toString(),
    val trigger: RunTrigger,
    val runMode: RunMode,
    val plan: RunPlan,
    /** 用户已点头的项；检查靠它跳过自己，否则确认完重跑会再问一遍 */
    val acknowledged: Set<ConfirmToken> = emptySet(),
    /** 会等待的挂载物轮询它；没有打断面的调用方传默认值即可 */
    val signals: RunSignals = RunSignals(),
    /** 会等待的挂载物用它报进度；默认丢弃 */
    val progress: RunProgress = RunProgress { _, _ -> },
    /** 本轮运行日志；单测可传 [DiscardingRunJournal] */
    val journal: RunJournal,
)
