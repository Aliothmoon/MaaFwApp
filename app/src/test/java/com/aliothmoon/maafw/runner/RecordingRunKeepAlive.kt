package com.aliothmoon.maafw.runner

/** 记录保活被拉起几次；单测里替掉前台服务 */
class RecordingRunKeepAlive : RunKeepAlive {
    var startCount: Int = 0
        private set

    override fun start() {
        startCount++
    }
}
