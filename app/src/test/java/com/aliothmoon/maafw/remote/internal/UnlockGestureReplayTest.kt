package com.aliothmoon.maafw.remote.internal

import com.aliothmoon.maafw.domain.GesturePoint
import com.aliothmoon.maafw.domain.UnlockGesture
import com.aliothmoon.maafw.domain.UnlockGestureJson
import com.aliothmoon.maafw.domain.UnlockStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 步骤 → 注入时间轴，以及持久化往返 */
class UnlockGestureReplayTest {

    @Test
    fun tapExpandsToDownHoldUp() {
        val gesture = gestureOf(UnlockStep.Tap(100, 200))
        assertEquals(
            listOf(
                InjectAction.Down(100, 200),
                InjectAction.Sleep(60),
                InjectAction.Up(100, 200),
            ),
            UnlockGestureReplay.timeline(gesture, 1080, 2220),
        )
    }

    @Test
    fun swipeEndsWithUpAtLastPoint() {
        val gesture = gestureOf(
            UnlockStep.Swipe(
                listOf(
                    GesturePoint(540, 1800, 0),
                    GesturePoint(540, 1400, 40),
                    GesturePoint(540, 900, 90),
                ),
            ),
        )
        assertEquals(
            listOf(
                InjectAction.Down(540, 1800),
                InjectAction.Sleep(40),
                InjectAction.Move(540, 1400),
                InjectAction.Sleep(50),
                InjectAction.Up(540, 900),
            ),
            UnlockGestureReplay.timeline(gesture, 1080, 2220),
        )
    }

    @Test
    fun delayBeforeStepBecomesLeadingSleep() {
        val gesture = gestureOf(
            UnlockStep.Tap(10, 10),
            UnlockStep.Tap(20, 20, delayBeforeMs = 250),
        )
        assertEquals(InjectAction.Sleep(250), UnlockGestureReplay.timeline(gesture, 1080, 2220)[3])
    }

    @Test
    fun timelineScalesToTargetResolution() {
        val gesture = gestureOf(UnlockStep.Tap(540, 1110))
        val actions = UnlockGestureReplay.timeline(gesture, 540, 1110)
        assertEquals(InjectAction.Down(270, 555), actions.first())
    }

    @Test
    fun replayStopsBeforeNextPressOnceKeyguardIsGone() {
        val actions = UnlockGestureReplay.timeline(
            gestureOf(UnlockStep.Tap(10, 10), UnlockStep.Tap(20, 20)),
            1080,
            2220,
        )
        val injected = mutableListOf<InjectAction>()
        var asks = 0
        // 第一笔放行，第二笔前锁屏已经没了
        val finished = UnlockGestureReplay.play(actions, proceed = { asks++ == 0 }) { injected += it }

        assertFalse(finished)
        assertEquals(actions.take(3), injected)
    }

    @Test
    fun replayFinishesStrokeAlreadyPressed() {
        val actions = listOf(
            InjectAction.Down(1, 1),
            InjectAction.Move(2, 2),
            InjectAction.Up(3, 3),
        )
        val injected = mutableListOf<InjectAction>()
        var asks = 0
        val finished = UnlockGestureReplay.play(actions, proceed = { asks++ == 0 }) { injected += it }

        // 只在按下前问，按下后不会半路丢掉抬起留下悬空触点
        assertTrue(finished)
        assertEquals(actions, injected)
        assertEquals(1, asks)
    }

    @Test
    fun gestureSurvivesJsonRoundTrip() {
        val gesture = gestureOf(
            UnlockStep.Tap(1, 2),
            UnlockStep.LongPress(3, 4, holdMs = 800, delayBeforeMs = 120),
            UnlockStep.Swipe(listOf(GesturePoint(5, 6, 0)), delayBeforeMs = 30),
        )
        val json = UnlockGestureJson.encodeToString(UnlockGesture.serializer(), gesture)
        assertEquals(gesture, UnlockGestureJson.decodeFromString(UnlockGesture.serializer(), json))
    }

    private fun gestureOf(vararg steps: UnlockStep) = UnlockGesture(
        screenWidth = 1080,
        screenHeight = 2220,
        rotation = 0,
        steps = steps.toList(),
    )
}
