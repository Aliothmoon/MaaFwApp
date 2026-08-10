package com.aliothmoon.maafw.service

import android.content.Context
import com.aliothmoon.maafw.runner.RunKeepAlive

/** [RunKeepAlive] 的设备实现；为什么非要前台服务见 [RunForegroundService] */
class ForegroundRunKeepAlive(private val context: Context) : RunKeepAlive {
    override fun start() = RunForegroundService.start(context)
}
