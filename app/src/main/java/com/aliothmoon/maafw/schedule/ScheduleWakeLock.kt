package com.aliothmoon.maafw.schedule

import android.content.Context
import android.os.PowerManager

/**
 * 定时触发从闹钟到投递这一段的 CPU 锁
 *
 * 闹钟只在 onReceive 期间替我们持锁，前台服务本身不拦 CPU 休眠；息屏又没开亮屏解锁时，
 * 倒计时的 delay 会跟着 CPU 一起停，通知卡在某一秒，直到有人点亮屏幕。一律限时，漏放也会自己过期
 */
internal object ScheduleWakeLock {

    private const val TAG = "MaaFw:schedule"

    fun acquire(context: Context, timeoutMs: Long): PowerManager.WakeLock {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
    }

    fun release(wakeLock: PowerManager.WakeLock) {
        if (wakeLock.isHeld) wakeLock.release()
    }
}
