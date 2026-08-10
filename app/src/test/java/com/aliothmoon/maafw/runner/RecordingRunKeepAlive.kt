package com.aliothmoon.maafw.runner

/** 记录保活被拉起几次；单测里替掉前台服务 */
class RecordingRunKeepAlive : RunKeepAlive {
    var startCount: Int = 0
        private set

    override fun start() {
        startCount++
    }
}

/** 固定屏幕尺寸，免得单测去问 WindowManager */
val TestDisplaySource = ScreenSizeSource { DisplayResolution(2340, 1080) }
