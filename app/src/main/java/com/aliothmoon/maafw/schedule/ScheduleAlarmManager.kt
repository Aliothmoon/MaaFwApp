package com.aliothmoon.maafw.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.edit
import com.aliothmoon.maafw.BuildConfig
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.time.ZonedDateTime

/**
 * 把 [ScheduleStrategy] 翻成系统闹钟
 *
 * 一条策略同一时刻只挂一个闹钟，requestCode 由 id 推导，重复注册即覆盖
 * 闹钟不自续：每次触发由 [ScheduleExecutionService] 在发起执行前调 [scheduleNext] 接上下一环，
 * 服务起不来时由 [ScheduleReceiver] 兜底补注册——否则链一断就再也不响；
 * 规则暂时读不出时走 [scheduleRetry]，重试用尽仍读不出走 [scheduleReconnect]，读得出再接回正常链
 */
class ScheduleAlarmManager(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * 闹钟链的账本：每条规则最近投递的原定时刻、挂着的那发重试、正常链挂上的触发点、
     * 最近一次闹钟到达的原定时刻，以及判出来还没取走的漏触发
     *
     * AlarmManager 查不出挂着的是哪一发，进程一换就只剩这里。不进 [ScheduleStrategyStore]：
     * 这本账恰恰要在规则还没读出来的时候可用
     */
    private val ledger = context.getSharedPreferences(LEDGER_NAME, Context.MODE_PRIVATE)

    /**
     * 进程启动的重排与闹钟拉起的那次投递常常同时在跑：先读到重试、再在投递记账之后把它挂回去，
     * 就会顶掉投递刚接上的下一环。读账到挂闹钟这一段与 [markDelivered] 互斥
     */
    private val ledgerLock = Any()

    private val _missedQueued = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 漏触发入队即发，由应用进程取走补记（[drainMissed]） */
    val missedQueued: SharedFlow<Unit> = _missedQueued.asSharedFlow()

    /** [rebooted]：触发点之后设备重启过，可能到点时就是关机的 */
    data class MissedTrigger(val strategyId: String, val scheduledTimeMs: Long, val rebooted: Boolean)

    fun scheduleNext(strategy: ScheduleStrategy, afterEpochMs: Long = 0L) {
        if (!strategy.enabled) return
        val next = computeNextTrigger(strategy, afterEpochMs)
        if (next == null) {
            Timber.d("Strategy %s has no next trigger; not scheduling", strategy.id)
            clearPendingRetry(strategy.id)
            arm(strategy.id, 0L)
            return
        }
        val triggerMs = next.toInstant().toEpochMilli()
        // 没挂上也要撤掉旧触发点，否则过期后会被误报成漏触发
        val registered = register(strategy.id, scheduledTimeMs = triggerMs, triggerMs = triggerMs)
        arm(strategy.id, if (registered) triggerMs else 0L)
        Timber.i("Strategy %s next trigger %s", strategy.id, next)
    }

    /**
     * 规则暂时读不出来时，[RETRY_DELAY_MS] 后再投一次同一个原定时刻
     *
     * 原定时刻不变，requestId 就不变：重试与迟到的原投递撞上也只会跑一次。
     * 与正常闹钟共用一个槽位，重试成功后由 [scheduleNext] 接回正常的下一环
     *
     * @param retryCount 本次投递已经是第几次重试
     * @return false 表示已到上限、本次放弃
     */
    fun scheduleRetry(strategyId: String, scheduledTimeMs: Long, retryCount: Int): Boolean {
        if (retryCount >= MAX_RETRY_COUNT) {
            Timber.w("Strategy %s gave up after %d retries", strategyId, retryCount)
            return false
        }
        register(
            strategyId,
            scheduledTimeMs = scheduledTimeMs,
            triggerMs = System.currentTimeMillis() + RETRY_DELAY_MS,
            retryCount = retryCount + 1,
        )
        Timber.i("Strategy %s retry #%d in %dms", strategyId, retryCount + 1, RETRY_DELAY_MS)
        return true
    }

    /**
     * 重试用尽而规则文件仍读不出：算不出下一环，只能每 [RECONNECT_DELAY_MS] 来看一眼
     *
     * 这一发只接链不跑，原定那一次已经放弃；读到规则后以 [scheduledTimeMs] 为界接回 [scheduleNext]
     */
    fun scheduleReconnect(strategyId: String, scheduledTimeMs: Long) {
        register(
            strategyId,
            scheduledTimeMs = scheduledTimeMs,
            triggerMs = System.currentTimeMillis() + RECONNECT_DELAY_MS,
            retryCount = RECONNECT_RETRY_COUNT,
        )
        Timber.i("Strategy %s waits for rules, check again in %dms", strategyId, RECONNECT_DELAY_MS)
    }

    /**
     * 闹钟到了，在 [ScheduleReceiver.onReceive] 第一行记，抢在进程启动的重排之前落盘，
     * 否则晚到的投递会被当成漏触发
     *
     * 不复用 [markDelivered]：那一笔故意等规则读出来才记，提前记会让挂着的重试在重排时被判不可续
     */
    fun markFired(strategyId: String, scheduledTimeMs: Long) {
        if (scheduledTimeMs <= 0L) return
        synchronized(ledgerLock) {
            ledger.edit(commit = true) { putLong(FIRED_PREFIX + strategyId, scheduledTimeMs) }
        }
    }

    /** 这一次已投递、即将发起；整批重排以它为界，见 [resyncAfterEpochMs] */
    fun markDelivered(strategyId: String, scheduledTimeMs: Long) {
        if (scheduledTimeMs <= 0L) return
        synchronized(ledgerLock) {
            ledger.edit(commit = true) { putLong(DELIVERED_PREFIX + strategyId, scheduledTimeMs) }
        }
    }

    /** 换上新触发点前，旧的过了宽限还没到就记进漏触发队列；[scheduledTimeMs] 为 0 即撤掉 */
    private fun arm(strategyId: String, scheduledTimeMs: Long) {
        synchronized(ledgerLock) {
            val armed = ledger.getLong(ARMED_PREFIX + strategyId, 0L)
            val now = System.currentTimeMillis()
            if (isMissed(armed, ledger.getLong(FIRED_PREFIX + strategyId, 0L), now)) {
                val rebooted = now - SystemClock.elapsedRealtime() > armed
                Timber.w("Strategy %s missed its trigger at %d, rebooted since: %s", strategyId, armed, rebooted)
                ledger.edit(commit = true) { putBoolean("$MISSED_PREFIX$strategyId$MISSED_SEP$armed", rebooted) }
                _missedQueued.tryEmit(Unit)
            }
            ledger.edit(commit = true) {
                if (scheduledTimeMs > 0L) putLong(ARMED_PREFIX + strategyId, scheduledTimeMs) else remove(ARMED_PREFIX + strategyId)
            }
        }
    }

    /** 取走漏触发队列；同一触发点后来到了的（晚于重排）在此剔除 */
    fun drainMissed(): List<MissedTrigger> = synchronized(ledgerLock) {
        val entries = ledger.all.filterKeys { it.startsWith(MISSED_PREFIX) }
        if (entries.isEmpty()) return emptyList()
        ledger.edit(commit = true) { entries.keys.forEach(::remove) }
        entries.mapNotNull { (key, rebooted) ->
            val body = key.removePrefix(MISSED_PREFIX)
            val id = body.substringBeforeLast(MISSED_SEP)
            val time = body.substringAfterLast(MISSED_SEP).toLongOrNull() ?: return@mapNotNull null
            if (ledger.getLong(FIRED_PREFIX + id, 0L) == time) return@mapNotNull null
            MissedTrigger(id, time, rebooted as? Boolean ?: false)
        }.sortedBy { it.scheduledTimeMs }
    }

    /** @return 闹钟有没有挂上 */
    private fun register(strategyId: String, scheduledTimeMs: Long, triggerMs: Long, retryCount: Int = 0): Boolean {
        // 先记账再挂：挂上了却没记住，整批重排时这发重试会被正常的下一环顶掉
        if (retryCount > 0) {
            ledger.edit(commit = true) {
                putString(RETRY_PREFIX + strategyId, PendingRetry(scheduledTimeMs, retryCount, triggerMs).encode())
            }
        } else {
            clearPendingRetry(strategyId)
        }
        val pendingIntent = buildTriggerIntent(strategyId, scheduledTimeMs, retryCount)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                registerWithoutExactPermission(triggerMs, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
            }
        } catch (e: SecurityException) {
            // 检查与调用之间权限被撤：系统随后杀进程、清闹钟，下次进程启动会重排，这里不能把调用方带崩
            Timber.e(e, "Strategy %s alarm rejected: exact alarm permission revoked", strategyId)
            return false
        } catch (e: IllegalStateException) {
            // 部分 ROM 给单个 app 的闹钟数设了上限，超了直接抛
            Timber.e(e, "Strategy %s alarm rejected by the system", strategyId)
            return false
        }
        return true
    }

    /**
     * 没有 SCHEDULE_EXACT_ALARM 时的退路
     *
     * setAlarmClock 在 12+ 同样要这项权限，调了直接抛，不能拿它兜底。电池白名单里的 app 仍被允许
     * setExact…，先试它；白名单也没有只能退成 inexact：可能被 Doze 推迟，广播起前台服务也不在豁免内，
     * 健康卡会把这两项都标出来
     */
    private fun registerWithoutExactPermission(triggerMs: Long, pendingIntent: PendingIntent) {
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
        } catch (e: SecurityException) {
            Timber.w("Exact alarm not allowed; falling back to an inexact alarm")
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
        }
    }

    /** API 31 起用户可单独关掉精确闹钟；关了只能靠电池白名单维持准点，否则退成可能延后的闹钟 */
    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /** 31 以下没有那个系统开关页，入口要藏掉，否则点了什么也不会发生 */
    fun hasExactAlarmToggle(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * 编辑保存后换上新规则的闹钟
     *
     * 挂着的重试原样留着（连同旧触发点）：它补的是规则读不出时错过的那一次，投递时读到的就是新规则，
     * 那次投递再接上下一环。重试与正常闹钟共用一个槽位，留它就不能同时排下一环。没有可续的重试才撤了重排
     */
    fun replace(strategy: ScheduleStrategy) {
        if (!strategy.enabled) {
            cancel(strategy.id)
            return
        }
        synchronized(ledgerLock) {
            val retry = pendingRetry(strategy.id)
            if (retry != null && retry.isResumable(lastDelivered(strategy.id), System.currentTimeMillis())) {
                Timber.i("Strategy %s keeps pending retry #%d across the edit", strategy.id, retry.retryCount)
                return
            }
            cancel(strategy.id)
            scheduleNext(strategy)
        }
    }

    /** 禁用、删除、改规则：撤掉槽位与挂着的重试，旧触发点也不再算漏触发 */
    fun cancel(strategyId: String) {
        cancelSlot(strategyId)
        if (ledger.contains(ARMED_PREFIX + strategyId)) {
            ledger.edit { remove(ARMED_PREFIX + strategyId) }
        }
    }

    /** 只撤闹钟与重试；整批重排紧接着要拿旧触发点判漏触发，不能连它一起撤 */
    private fun cancelSlot(strategyId: String) {
        val pendingIntent = buildTriggerIntent(strategyId, 0L)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
        clearPendingRetry(strategyId)
    }

    /** 规则确定已不在（删了，或规则文件损坏被重置）：撤掉槽位，账也一起清 */
    fun forget(strategyId: String) {
        cancel(strategyId)
        val keys = listOf(DELIVERED_PREFIX + strategyId, FIRED_PREFIX + strategyId).filter(ledger::contains)
        if (keys.isNotEmpty()) ledger.edit { keys.forEach(::remove) }
    }

    /**
     * 整批重排，只在规则读盘之后调
     *
     * - 停用的先撤：禁用与删除都会留下孤儿闹钟
     * - 挂着的重试原样接回：触发日志写了「将重试」，不能被正常的下一环顶掉
     * - 正常的下一环以最近已投递的原定时刻为界，时间往回拨也不会把响过的那次再挂一遍
     * - 账上有、规则表里没有的是删掉或规则文件重置后的孤儿，撤掉
     */
    fun rescheduleAll(strategies: List<ScheduleStrategy>) {
        val now = System.currentTimeMillis()
        strategies.forEach { strategy ->
            if (!strategy.enabled) {
                cancel(strategy.id)
                return@forEach
            }
            synchronized(ledgerLock) {
                val delivered = lastDelivered(strategy.id)
                val retry = pendingRetry(strategy.id)
                if (retry != null && retry.isResumable(delivered, now)) {
                    register(strategy.id, retry.scheduledTimeMs, retry.resumeAt(now), retry.retryCount)
                    Timber.i("Strategy %s keeps pending retry #%d", strategy.id, retry.retryCount)
                } else {
                    cancelSlot(strategy.id)
                    scheduleNext(strategy, resyncAfterEpochMs(delivered, now))
                }
            }
        }
        val known = strategies.mapTo(HashSet()) { it.id }
        ledgerStrategyIds().filterNot { it in known }.forEach { orphan ->
            Timber.w("Strategy %s no longer exists; cancelling its alarm", orphan)
            forget(orphan)
        }
    }

    fun computeNextTrigger(strategy: ScheduleStrategy, afterEpochMs: Long = 0L): ZonedDateTime? =
        nextTriggerOf(strategy, systemNow(), afterEpochMs)

    private fun lastDelivered(strategyId: String): Long? =
        ledger.getLong(DELIVERED_PREFIX + strategyId, 0L).takeIf { it > 0L }

    private fun pendingRetry(strategyId: String): PendingRetry? =
        PendingRetry.decode(ledger.getString(RETRY_PREFIX + strategyId, null))

    private fun clearPendingRetry(strategyId: String) {
        if (ledger.contains(RETRY_PREFIX + strategyId)) {
            ledger.edit { remove(RETRY_PREFIX + strategyId) }
        }
    }

    private fun ledgerStrategyIds(): Set<String> =
        ledger.all.keys.mapNotNullTo(HashSet()) { key ->
            when {
                key.startsWith(DELIVERED_PREFIX) -> key.removePrefix(DELIVERED_PREFIX)
                key.startsWith(RETRY_PREFIX) -> key.removePrefix(RETRY_PREFIX)
                key.startsWith(ARMED_PREFIX) -> key.removePrefix(ARMED_PREFIX)
                key.startsWith(FIRED_PREFIX) -> key.removePrefix(FIRED_PREFIX)
                else -> null
            }
        }

    private fun buildTriggerIntent(
        strategyId: String,
        scheduledTimeMs: Long,
        retryCount: Int = 0,
    ): PendingIntent {
        val intent = Intent(ACTION_SCHEDULE_TRIGGER).apply {
            setClassName(context, ScheduleReceiver::class.java.name)
            putExtra(EXTRA_STRATEGY_ID, strategyId)
            putExtra(EXTRA_SCHEDULED_TIME, scheduledTimeMs)
            putExtra(EXTRA_RETRY_COUNT, retryCount)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(strategyId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** PendingIntent 只按 requestCode + Intent 过滤器区分，extras 不参与——同一策略必须落同一个码 */
    private fun requestCode(strategyId: String): Int = strategyId.hashCode() and 0x7FFFFFFF

    companion object {
        /** 跟 applicationId 走：分包出去的两个包装同一台设备时，同名 action 会让闹钟广播串到对方 */
        const val ACTION_SCHEDULE_TRIGGER = BuildConfig.APPLICATION_ID + ".SCHEDULE_TRIGGER"
        const val EXTRA_STRATEGY_ID = "strategy_id"
        const val EXTRA_SCHEDULED_TIME = "scheduled_time"
        const val EXTRA_RETRY_COUNT = "retry_count"
        const val MAX_RETRY_COUNT = 3
        const val RETRY_DELAY_MS = 60_000L

        /** 慢速接链那一发的 retryCount；超出快速重试的计数段，收到它就知道只接链不跑 */
        const val RECONNECT_RETRY_COUNT = MAX_RETRY_COUNT + 1
        const val RECONNECT_DELAY_MS = 15 * 60_000L

        /** 重排时还接回挂着的重试的时限，按原定时刻算 */
        const val RETRY_RESUME_WINDOW_MS = 60 * 60_000L

        /** 盖过重试（3 × 60 秒）和冷启动的投递延迟 */
        const val MISSED_GRACE_MS = 10 * 60_000L

        /** 挂上的触发点过了宽限、闹钟还没到过它，就是被漏掉了 */
        fun isMissed(armedMs: Long, firedMs: Long, nowMs: Long): Boolean =
            armedMs > 0L && armedMs + MISSED_GRACE_MS < nowMs && firedMs < armedMs

        private const val LEDGER_NAME = "schedule_alarm_ledger"
        private const val DELIVERED_PREFIX = "delivered:"
        private const val RETRY_PREFIX = "retry:"
        private const val ARMED_PREFIX = "armed:"
        private const val FIRED_PREFIX = "fired:"

        /** 漏触发队列，"missed:<id>@<原定时刻>" → 是否重启过 */
        private const val MISSED_PREFIX = "missed:"
        private const val MISSED_SEP = '@'
    }
}

/** 挂着的一发重试；落盘是为了整批重排时能原样接回 */
internal data class PendingRetry(
    val scheduledTimeMs: Long,
    val retryCount: Int,
    val triggerMs: Long,
) {
    /**
     * 整批重排时还该不该接回
     *
     * - 慢速接链那一档不接：能重排说明规则已读出，正常的下一环就是它要接的
     * - 这一次已经投递过的不接：重试的对象已经跑了
     * - 原定时刻过去太久的不接：关机几小时后开机补跑旧的一次，与系统丢掉错过的闹钟同一口径
     */
    fun isResumable(lastDeliveredMs: Long?, nowMs: Long): Boolean =
        retryCount in 1..ScheduleAlarmManager.MAX_RETRY_COUNT &&
            (lastDeliveredMs == null || scheduledTimeMs > lastDeliveredMs) &&
            nowMs - scheduledTimeMs <= ScheduleAlarmManager.RETRY_RESUME_WINDOW_MS

    /**
     * 还没到点就按原来的投递时刻，时钟往回拨了也不拖过一个重试间隔
     *
     * 已过点的不立即补：多半是这一发正在投递、拉起了本进程才触发的重排，再隔一个间隔，
     * 让那次投递先把槽位换成它自己的下一环
     */
    fun resumeAt(nowMs: Long): Long {
        val latest = nowMs + ScheduleAlarmManager.RETRY_DELAY_MS
        return if (triggerMs > nowMs) minOf(triggerMs, latest) else latest
    }

    fun encode(): String = "$scheduledTimeMs,$retryCount,$triggerMs"

    companion object {
        fun decode(raw: String?): PendingRetry? {
            val parts = raw?.split(',')?.takeIf { it.size == 3 } ?: return null
            return PendingRetry(
                scheduledTimeMs = parts[0].toLongOrNull() ?: return null,
                retryCount = parts[1].toIntOrNull() ?: return null,
                triggerMs = parts[2].toLongOrNull() ?: return null,
            )
        }
    }
}
