package com.aliothmoon.maafw.runner

/** 记录保活被拉起几次；单测里替掉前台服务 */
class RecordingRunKeepAlive : RunKeepAlive {
    var startCount: Int = 0
        private set

    override fun start() {
        startCount++
    }
}

/** 固定尺寸的物理屏，免得单测碰 `Resources.getSystem()` */
val TestDisplaySource = PhysicalDisplaySource { DisplayResolution(2340, 1080) }
