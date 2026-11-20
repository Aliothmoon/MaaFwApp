package com.aliothmoon.maafw.ui.tasks

import com.aliothmoon.maafw.privileged.WatchdogState
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewBadgeStatusTest {

    @Test
    fun idleRunShowsReadyOnlyWhenAppRemainsOnDisplay() {
        assertEquals(PreviewBadgeStatus.READY, resolvePreviewBadgeStatus(WatchdogState.READY, running = false))
        assertEquals(PreviewBadgeStatus.IDLE, resolvePreviewBadgeStatus(WatchdogState.IDLE, running = false))
        // 任务刚停下但 2s 看门狗轮询尚未刷新时，不再残留运行态或坏态
        assertEquals(PreviewBadgeStatus.IDLE, resolvePreviewBadgeStatus(WatchdogState.WATCHING, running = false))
        assertEquals(PreviewBadgeStatus.IDLE, resolvePreviewBadgeStatus(WatchdogState.APP_DIED, running = false))
        assertEquals(PreviewBadgeStatus.IDLE, resolvePreviewBadgeStatus(WatchdogState.DISPLAY_DRIFT, running = false))
    }

    @Test
    fun activeRunShowsRunningImmediatelyAndSurfacesLostStates() {
        assertEquals(PreviewBadgeStatus.RUNNING, resolvePreviewBadgeStatus(WatchdogState.IDLE, running = true))
        assertEquals(PreviewBadgeStatus.RUNNING, resolvePreviewBadgeStatus(WatchdogState.READY, running = true))
        assertEquals(PreviewBadgeStatus.RUNNING, resolvePreviewBadgeStatus(WatchdogState.WATCHING, running = true))
        assertEquals(PreviewBadgeStatus.APP_STOPPED, resolvePreviewBadgeStatus(WatchdogState.APP_DIED, running = true))
        assertEquals(PreviewBadgeStatus.DISPLAY_DRIFT, resolvePreviewBadgeStatus(WatchdogState.DISPLAY_DRIFT, running = true))
    }
}
