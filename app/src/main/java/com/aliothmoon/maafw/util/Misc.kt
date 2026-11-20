package com.aliothmoon.maafw.util

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process

object Misc {

    private const val RESTART_FALLBACK_KILL_MS = 3_000L

    /**
     * 重启 app：交给 `:restart` 进程里的 [RestartActivity] 杀掉本进程再拉起，它没起来时兜底自杀
     *
     * 调试模式切换需要重启让日志管线以新状态起来；调用方负责在设置落盘后再调
     */
    fun restartApp(context: Context) {
        context.startActivity(
            Intent(context, RestartActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(RestartActivity.EXTRA_MAIN_PID, Process.myPid())
        )
        Handler(Looper.getMainLooper()).postDelayed(
            { Process.killProcess(Process.myPid()) },
            RESTART_FALLBACK_KILL_MS
        )
    }
}
