package com.aliothmoon.maafw.runner

import android.view.Display
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.privileged.callWithTimeout
import kotlin.time.Duration.Companion.seconds

/** 主屏可见应用的探测结局 */
sealed interface BlacklistScan {
    /** 特权进程没连着、旧版特权进程、读不到任务表：放行 */
    data object Unknown : BlacklistScan

    /** 读到了主屏上的可见应用；[hit] 是其中命中名单的那个 */
    data class Visible(val packages: List<String>, val hit: String?) : BlacklistScan
}

/**
 * 定时的应用黑名单：后台模式的定时触发撞上用户正在用名单里的应用，跳过这一轮
 *
 * 判定只写这一份，挂两处：[AppBlacklistPrecheck] 在特权进程已连着时赶在 force 抢占之前查；
 * [AppBlacklistHook] 在 `privileged-connect` 之后补查，倒计时走完再查一次
 */
class AppBlacklist(
    private val servicePort: PrivilegedServicePort,
    private val blacklist: () -> Set<String>,
    private val keyguardLocked: () -> Boolean,
    /** 包名 → 应用名，查不到回包名 */
    private val appLabel: (String) -> String,
) {

    /** 前台模式本来就要占着屏幕，手动发起时人就在跟前 */
    fun applies(ctx: RunContext): Boolean =
        ctx.trigger is RunTrigger.Schedule && ctx.runMode == RunMode.BACKGROUND && blacklist().isNotEmpty()

    /** 此刻亮屏且没锁；读不到屏幕状态按亮着算，宁可多查一次 */
    suspend fun inUseNow(): Boolean {
        val screenOn = servicePort.callWithTimeout(PROBE_TIMEOUT) { it.isScreenOn() } ?: true
        return screenOn && !keyguardLocked()
    }

    /** 分屏、小窗都算：主屏上任一可见任务在名单里就命中 */
    suspend fun scan(): BlacklistScan {
        val names = blacklist()
        // 旧版特权进程没有这个方法，proxy 读回 null
        val packages = servicePort.callWithTimeout(PROBE_TIMEOUT) {
            it.getVisiblePackages(Display.DEFAULT_DISPLAY).orEmpty()
        }
        if (packages.isNullOrEmpty()) return BlacklistScan.Unknown
        return BlacklistScan.Visible(packages, hit = packages.firstOrNull { it in names })
    }

    fun hitReason(packageName: String): UiText = uiTextOf(R.string.run_blacklist_hit, appLabel(packageName))

    private companion object {
        val PROBE_TIMEOUT = 3.seconds
    }
}

/**
 * 特权进程已连着才查：在跑的一轮必然连着，正好盖住 force 抢占之前；没连着不为这个去连，
 * 交给 `privileged-connect` 之后的 [AppBlacklistHook]
 *
 * 此时还没唤醒，「在不在用」读的就是此刻；会话日志还没开，探到的包名进拦截原因
 */
class AppBlacklistPrecheck(
    private val blacklist: AppBlacklist,
    private val servicePort: PrivilegedServicePort,
) : RunPrecheck {

    override val id: String = ID

    override suspend fun evaluate(ctx: RunContext): Verdict {
        if (!blacklist.applies(ctx) || servicePort.serviceOrNull() == null) return Verdict.Pass
        if (!blacklist.inUseNow()) return Verdict.Pass
        val scan = blacklist.scan() as? BlacklistScan.Visible ?: return Verdict.Pass
        val hit = scan.hit ?: return Verdict.Pass
        return Verdict.Block(
            uiTextOf(R.string.run_blacklist_blocked, blacklist.hitReason(hit), scan.packages.joinToString(", ")),
        )
    }

    companion object {
        const val ID = "app-blacklist"
    }
}

/**
 * gating：命中就不往下走，没唤醒、没盖屏保、没倒计时
 *
 * [afterCountdown] 那份排在倒计时之后：倒计时里可能切进了名单内的应用。点了「立即开始」、
 * 倒计时里放下了手机都不再查。倒计时期间才打开名单内应用、而上一轮已被抢掉的，接受
 */
class AppBlacklistHook(
    private val blacklist: AppBlacklist,
    private val afterCountdown: Boolean,
) : RunEnvHook {

    override val id: String = if (afterCountdown) RECHECK_ID else ID
    override val anchor: Anchor = Anchor.BeforeDispatch
    override val order: Int = if (afterCountdown) HookOrder.APP_BLACKLIST_RECHECK else HookOrder.APP_BLACKLIST
    override val gating: Boolean = true

    override suspend fun engage(ctx: RunContext): EngageResult {
        if (!blacklist.applies(ctx)) return EngageResult.Skipped()
        // 唤醒前就没在用（或没采到）：接管一台闲着的设备，不打扰谁
        if (ctx.deviceAtStart.get()?.idle != false) return EngageResult.Skipped()
        if (afterCountdown && (ctx.signals.startNowRequested || !blacklist.inUseNow())) {
            return EngageResult.Skipped()
        }
        val scan = blacklist.scan()
        if (scan !is BlacklistScan.Visible) {
            ctx.journal.info(ctx.executionId, uiTextOf(R.string.run_log_blacklist_unknown))
            return EngageResult.Skipped()
        }
        ctx.journal.info(ctx.executionId, uiTextOf(R.string.run_log_visible_apps, scan.packages.joinToString(", ")))
        return scan.hit?.let { EngageResult.Failed(blacklist.hitReason(it)) } ?: EngageResult.Skipped()
    }

    companion object {
        const val ID = "app-blacklist"
        const val RECHECK_ID = "app-blacklist-recheck"
    }
}

/** 拦下这次的是不是黑名单：两道挂载物与启动前检查同算「跳过」 */
val BlockSource.isAppBlacklist: Boolean
    get() = when (this) {
        is BlockSource.Precheck -> id == AppBlacklistPrecheck.ID
        is BlockSource.Hook -> id == AppBlacklistHook.ID || id == AppBlacklistHook.RECHECK_ID
        BlockSource.Busy -> false
    }
