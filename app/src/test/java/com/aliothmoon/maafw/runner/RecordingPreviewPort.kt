package com.aliothmoon.maafw.runner

import android.view.Surface

/** 只记调用次数：Surface 是平台类型，JVM 单测里不构造也不解引用 */
class RecordingPreviewPort : PreviewPort {
    var attachCount: Int = 0
        private set
    var detachCount: Int = 0
        private set

    override fun attachSurface(surface: Surface) {
        attachCount++
    }

    override fun detachSurface() {
        detachCount++
    }
}
