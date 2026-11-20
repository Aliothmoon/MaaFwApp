package com.aliothmoon.maafw.remote.internal

import android.os.IBinder
import android.os.SystemClock
import com.aliothmoon.maafw.constant.AndroidVersions
import com.aliothmoon.maafw.third.Ln
import com.aliothmoon.maafw.third.wrappers.DisplayManager
import com.aliothmoon.maafw.third.wrappers.PowerManager
import com.aliothmoon.maafw.third.wrappers.SurfaceControl
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PowerControllerTest {
    private val display = mockk<IBinder>()
    private val displayManager = mockk<DisplayManager>()
    private val powerManager = mockk<PowerManager>()
    private val defaultDisplayManager = PowerController.displayManager
    private val defaultPowerManager = PowerController.powerManager
    private val defaultSystemAwake = PowerController.systemAwake

    @Volatile
    private var awake = true

    @Before
    fun setUp() {
        mockkStatic(SurfaceControl::class, Ln::class, SystemClock::class)
        PowerController.displayManager = { displayManager }
        PowerController.powerManager = { powerManager }
        PowerController.systemAwake = { awake }
        every { SurfaceControl.getBuiltInDisplay() } returns display
        every { SurfaceControl.setDisplayPowerMode(any(), any()) } returns true
        every { Ln.i(any()) } just runs
        every { Ln.w(any()) } just runs
        every { Ln.e(any()) } just runs
        every { Ln.e(any(), any()) } just runs
        every { SystemClock.sleep(any()) } just runs
    }

    @After
    fun tearDown() {
        PowerController.stopUserActivityKeepAlive()
        PowerController.setDisplayPower(true)
        PowerController.displayManager = defaultDisplayManager
        PowerController.powerManager = defaultPowerManager
        PowerController.systemAwake = defaultSystemAwake
        unmockkStatic(SurfaceControl::class, Ln::class, SystemClock::class)
    }

    /** 关屏失败也留恢复标记，多屏可能只关掉了一部分 */
    @Test
    fun destroy_restoresWhileAwake() {
        every { SurfaceControl.setDisplayPowerMode(any(), SurfaceControl.POWER_MODE_OFF) } returns false
        PowerController.setDisplayPower(false)
        PowerController.destroy()
        verify(exactly = 1) { SurfaceControl.setDisplayPowerMode(any(), SurfaceControl.POWER_MODE_NORMAL) }
    }

    @Test
    fun destroy_leavesPanelToSystemWhileAsleep() {
        PowerController.setDisplayPower(false)
        awake = false
        PowerController.destroy()
        PowerController.destroy()
        verify(exactly = 0) { SurfaceControl.setDisplayPowerMode(any(), SurfaceControl.POWER_MODE_NORMAL) }
    }

    @Test
    fun targetKeepAliveDisplay_previewOnlyKeepsOwnPowerGroupAlive() {
        assertNull(PowerController.targetKeepAliveDisplay(null, taskKeepAlive = true, previewKeepAlive = true))

        for (group in listOf(-1, 0)) {
            every { displayManager.getDisplayGroupId(8) } returns group
            assertNull(PowerController.targetKeepAliveDisplay(8, taskKeepAlive = false, previewKeepAlive = true))
            assertEquals(8, PowerController.targetKeepAliveDisplay(8, taskKeepAlive = true, previewKeepAlive = false))
        }

        every { displayManager.getDisplayGroupId(8) } returns 1
        assertEquals(8, PowerController.targetKeepAliveDisplay(8, taskKeepAlive = false, previewKeepAlive = true))
        assertEquals(8, PowerController.targetKeepAliveDisplay(8, taskKeepAlive = true, previewKeepAlive = false))
        assertNull(PowerController.targetKeepAliveDisplay(8, taskKeepAlive = false, previewKeepAlive = false))
    }

    @Test
    fun ensureDisplayAwake_sharedOrUnknownGroup_neverWakesMainScreen() {
        every { powerManager.isScreenOnOrNull(any()) } returns false

        for (group in listOf(-1, 0)) {
            every { displayManager.getDisplayGroupId(8) } returns group
            assertTrue(PowerController.ensureDisplayAwake(8, AndroidVersions.API_36_ANDROID_16))
            assertTrue(PowerController.ensureDisplayAwake(8, AndroidVersions.API_34_ANDROID_14))
        }
        verify(exactly = 0) { powerManager.isScreenOnOrNull(any()) }
        verify(exactly = 0) { powerManager.wakeUp(any()) }
    }

    @Test
    fun ensureDisplayAwake_ownGroupAwakeOrUnknown_skipsWake() {
        every { displayManager.getDisplayGroupId(8) } returns 1
        every { powerManager.isScreenOnOrNull(8) } returns true
        assertTrue(PowerController.ensureDisplayAwake(8, AndroidVersions.API_36_ANDROID_16))

        every { powerManager.isScreenOnOrNull(8) } returns null
        assertTrue(PowerController.ensureDisplayAwake(8, AndroidVersions.API_34_ANDROID_14))
        verify(exactly = 0) { powerManager.wakeUp(any()) }
    }

    @Test
    fun ensureDisplayAwake_api33CannotQueryPerDisplay_assumesAwake() {
        every { displayManager.getDisplayGroupId(8) } returns 1
        every { powerManager.isScreenOnOrNull(8) } returns false

        assertTrue(PowerController.ensureDisplayAwake(8, AndroidVersions.API_33_ANDROID_13))
        verify(exactly = 0) { powerManager.isScreenOnOrNull(any()) }
        verify(exactly = 0) { powerManager.wakeUp(any()) }
    }

    @Test
    fun ensureDisplayAwake_ownGroupAsleepApi34And35_returnsFalseForRecreate() {
        every { displayManager.getDisplayGroupId(8) } returns 1
        every { powerManager.isScreenOnOrNull(8) } returns false

        for (sdk in listOf(
            AndroidVersions.API_34_ANDROID_14,
            AndroidVersions.API_35_ANDROID_15,
        )) {
            assertFalse("sdk=$sdk should report asleep", PowerController.ensureDisplayAwake(8, sdk))
        }
        verify(exactly = 0) { powerManager.wakeUp(any()) }
    }

    @Test
    fun ensureDisplayAwake_ownGroupAsleepApi36_wakesAndVerifiesState() {
        every { displayManager.getDisplayGroupId(any()) } returns 1

        every { powerManager.isScreenOnOrNull(8) } returnsMany listOf(false, true)
        every { powerManager.wakeUp(8) } returns true
        assertTrue(PowerController.ensureDisplayAwake(8, AndroidVersions.API_36_ANDROID_16))
        verify(exactly = 1) { powerManager.wakeUp(8) }

        every { powerManager.isScreenOnOrNull(9) } returnsMany listOf(false, false)
        every { powerManager.wakeUp(9) } returns true
        assertFalse(PowerController.ensureDisplayAwake(9, AndroidVersions.API_36_ANDROID_16))
        verify(exactly = 1) { powerManager.wakeUp(9) }

        every { powerManager.isScreenOnOrNull(10) } returns false
        every { powerManager.wakeUp(10) } returns false
        assertFalse(PowerController.ensureDisplayAwake(10, AndroidVersions.API_36_ANDROID_16))
        verify(exactly = 1) { powerManager.wakeUp(10) }
    }

    @Test
    fun keepAlive_firesImmediatelySwitchesDisplayAndRestartsAfterStop() {
        val poked = LinkedBlockingQueue<Int>()
        val sleepEntered = LinkedBlockingQueue<CountDownLatch>()
        val stopped = LinkedBlockingQueue<Unit>()
        every { powerManager.userActivity(any()) } answers { poked.put(firstArg()) }
        every { SystemClock.sleep(any()) } answers {
            val gate = CountDownLatch(1)
            sleepEntered.put(gate)
            assertTrue(gate.await(5, TimeUnit.SECONDS))
        }
        every { Ln.i(any()) } answers {
            if (firstArg<String>().contains("userActivity keep-alive stopped")) {
                stopped.put(Unit)
            }
        }

        // 首拍立即发送
        PowerController.startUserActivityKeepAlive(8)
        assertEquals(8, poked.poll(2, TimeUnit.SECONDS))
        val firstSleep = sleepEntered.poll(2, TimeUnit.SECONDS)!!

        // 切屏复用现有线程
        PowerController.startUserActivityKeepAlive(10)
        firstSleep.countDown()
        assertEquals(10, poked.poll(2, TimeUnit.SECONDS))
        val secondSleep = sleepEntered.poll(2, TimeUnit.SECONDS)!!

        // 停后立刻重启（覆盖旧线程还在 sleep 与已退出两类时序）
        PowerController.stopUserActivityKeepAlive()
        secondSleep.countDown()
        PowerController.startUserActivityKeepAlive(12)
        assertEquals(12, poked.poll(2, TimeUnit.SECONDS))
        val thirdSleep = sleepEntered.poll(2, TimeUnit.SECONDS)!!

        // 确认旧线程完整退出后仍可重新拉起
        PowerController.stopUserActivityKeepAlive()
        thirdSleep.countDown()
        assertEquals(Unit, stopped.poll(2, TimeUnit.SECONDS))

        PowerController.startUserActivityKeepAlive(14)
        assertEquals(14, poked.poll(2, TimeUnit.SECONDS))
        val fourthSleep = sleepEntered.poll(2, TimeUnit.SECONDS)!!
        PowerController.stopUserActivityKeepAlive()
        fourthSleep.countDown()
        assertEquals(Unit, stopped.poll(2, TimeUnit.SECONDS))
    }
}
