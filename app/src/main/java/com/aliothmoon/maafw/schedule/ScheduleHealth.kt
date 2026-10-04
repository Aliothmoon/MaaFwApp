package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.domain.UnlockCredential

/**
 * 定时「调度环境检查」：哪些前提没满足，规则到点就可能不跑或跑不完
 *
 * 纯推导，列表页的健康卡与保存后的引导共用一套判定（对齐 MaaMeow 的 ScheduleHealthLogic）
 */
enum class ScheduleHealthIssue {
    /** 当前启动模式（Shizuku/Root）未授权：亮屏解锁、拉起、执行都走特权进程 */
    BACKEND,

    /** 未加入电池优化白名单：闹钟能响，前台服务却可能被系统拦下或延迟 */
    BATTERY,

    /** 精确闹钟未允许：仍会按时触发，只是退到 setAlarmClock，状态栏多一个闹钟图标 */
    EXACT_ALARM,

    /** 通知未开：倒计时上「立即开始 / 取消」两个按钮与失败提醒都看不见 */
    NOTIFICATION,

    /** 后台模式开了屏保却没有悬浮窗权限：屏保盖不上，整轮亮着屏跑 */
    OVERLAY,

    /** 设备有安全锁屏，选的解锁方式却解不开（「无密码」、没填 PIN、没录手势）：整轮被锁屏拦下 */
    WAKE_CREDENTIAL,
}

/** 全部为「是否满足」语义 */
data class ScheduleHealthSnapshot(
    /** 只看授权，不看特权进程连没连上：到点时服务本就未必在线，按连接态判会误报 */
    val backendGranted: Boolean,
    val batteryWhitelist: Boolean,
    val exactAlarmAllowed: Boolean,
    val notification: Boolean,
    val overlayGranted: Boolean,
    val overlayNeeded: Boolean,
    val wakeCredentialMissing: Boolean,
)

object ScheduleHealthLogic {

    /** 屏保只在后台模式挂（见 `ScreenSaverHook`），前台模式开着也用不上悬浮窗 */
    fun overlayNeeded(backgroundMode: Boolean, screenSaverEnabled: Boolean): Boolean =
        backgroundMode && screenSaverEnabled

    /**
     * 与 `WakeUnlockHook` 同一份凭证判定（[UnlockCredential.isReady]）：
     * 设备没设安全锁屏时滑一下就开，选什么方式都行
     */
    fun wakeCredentialMissing(
        unlockType: String,
        deviceSecure: Boolean,
        pin: String,
        hasGesture: Boolean,
    ): Boolean = deviceSecure && !UnlockCredential.isReady(unlockType, pin, hasGesture)

    /**
     * 未通过项，枚举顺序即展示顺序；空 = 健康卡不出现
     *
     * 不看有没有启用的规则：用户正是在建第一条规则之前最该知道环境缺什么
     */
    fun failingIssues(snapshot: ScheduleHealthSnapshot): List<ScheduleHealthIssue> =
        buildList {
            if (!snapshot.backendGranted) add(ScheduleHealthIssue.BACKEND)
            if (!snapshot.batteryWhitelist) add(ScheduleHealthIssue.BATTERY)
            if (!snapshot.exactAlarmAllowed) add(ScheduleHealthIssue.EXACT_ALARM)
            if (!snapshot.notification) add(ScheduleHealthIssue.NOTIFICATION)
            if (snapshot.overlayNeeded && !snapshot.overlayGranted) add(ScheduleHealthIssue.OVERLAY)
            if (snapshot.wakeCredentialMissing) add(ScheduleHealthIssue.WAKE_CREDENTIAL)
        }

    /**
     * 保存后逐项引导的那几项：都是跳一次系统页就能解决的
     *
     * 后端授权要走 Shizuku/Root 的整套流程，PIN 要去设置页填，都不是一个弹窗能收尾的，留给健康卡
     */
    fun wizardItems(snapshot: ScheduleHealthSnapshot): List<ScheduleHealthIssue> =
        failingIssues(snapshot).filterNot {
            it == ScheduleHealthIssue.BACKEND || it == ScheduleHealthIssue.WAKE_CREDENTIAL
        }
}
