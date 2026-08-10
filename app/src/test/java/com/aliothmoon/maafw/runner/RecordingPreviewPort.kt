package com.aliothmoon.maafw.runner

import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 只记调用次数：Surface 是平台类型，JVM 单测里不构造也不解引用 */
class RecordingPreviewPort : PreviewPort {
    override val markers: StateFlow<List<PreviewTouchMarker>> = MutableStateFlow(emptyList())

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

    /** 预览上的手动触摸；按 (x, y, action) 记录顺序 */
    val touches = mutableListOf<Triple<Int, Int, String>>()

    override fun touchDown(x: Int, y: Int) {
        touches += Triple(x, y, "down")
    }

    override fun touchMove(x: Int, y: Int) {
        touches += Triple(x, y, "move")
    }

    override fun touchUp(x: Int, y: Int) {
        touches += Triple(x, y, "up")
    }
}
