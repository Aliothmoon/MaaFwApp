package com.aliothmoon.maafw.util

import android.content.Context
import android.content.Intent
import android.os.Process
import kotlin.system.exitProcess

object Misc {
    /**
     * 重启 app：拉起启动 Intent 后杀当前进程（对齐 MaaMeow 的 Misc.restartApp）
     *
     * 调试模式切换需要重启让日志管线以新状态起来；调用方负责在设置落盘后再调
     */
    fun restartApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        intent?.let { context.startActivity(it) }
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
