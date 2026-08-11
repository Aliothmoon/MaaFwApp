package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.constant.WakeUnlockResult
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.privileged.FakePrivilegedService
import com.aliothmoon.maafw.privileged.FakePrivilegedServicePort
import com.aliothmoon.maafw.settings.FakeAppSettingsGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EnvironmentHooksTest {

    private val plan = RunPlan(
        projectName = "demo",
        projectVersion = "1",
        controller = ControllerDefinition(),
        resource = ResourceDefinition("官服", listOf("./base")),
        runConfigurationId = RunConfigurationId("c1"),
        tasks = emptyList(),
    )

    private fun context(runMode: RunMode = RunMode.BACKGROUND) =
        RunContext(RunTrigger.Manual, runMode, plan)

    private class RecordingScreenSaver(private val showSucceeds: Boolean = true) : RunScreenSaver {
        var shown = 0
        var hidden = 0
        override suspend fun show(): Boolean {
            if (showSucceeds) shown++
            return showSucceeds
        }

        override suspend fun hide() {
            hidden++
        }
    }

    // ── 亮屏解锁 ────────────────────────────────────────────────────

    @Test
    fun `wake unlock is skipped when the switch is off`() = runTest {
        val service = FakePrivilegedService()
        val settings = FakeAppSettingsGateway()
        val hook = WakeUnlockHook(FakePrivilegedServicePort(service), settings)

        assertNull(hook.engage(scheduleContext()))
        assertTrue(service.unlockCalls.isEmpty())
    }

    /** 手动 Start 时用户正对着亮屏解锁的手机按按钮，解一次是空操作 */
    @Test
    fun `wake unlock never runs for a manual trigger`() = runTest {
        val service = FakePrivilegedService()
        val settings = FakeAppSettingsGateway().apply {
            wakeUnlockEnabled.value = true
            wakeCredential.value = "1234"
        }

        assertNull(WakeUnlockHook(FakePrivilegedServicePort(service), settings).engage(context()))
        assertTrue(service.unlockCalls.isEmpty())
    }

    @Test
    fun `wake unlock passes the configured pin through`() = runTest {
        val service = FakePrivilegedService()
        val settings = FakeAppSettingsGateway().apply {
            wakeUnlockEnabled.value = true
            wakeCredential.value = "1234"
        }
        val hook = WakeUnlockHook(FakePrivilegedServicePort(service), settings)

        hook.engage(scheduleContext())

        assertEquals(listOf("1234"), service.unlockCalls)
    }

    /** 没设锁屏的设备解不出东西也不算失败 */
    @Test
    fun `no keyguard counts as success`() = runTest {
        val service = FakePrivilegedService().apply { unlockResult = WakeUnlockResult.NO_KEYGUARD }
        val settings = FakeAppSettingsGateway().apply { wakeUnlockEnabled.value = true }
        val hook = WakeUnlockHook(FakePrivilegedServicePort(service), settings)

        hook.engage(scheduleContext())
    }

    /** gating：抛出去让 RunLauncher 中止整轮，别对着锁屏跑到超时 */
    @Test
    fun `a rejected pin aborts the run`() = runTest {
        val service = FakePrivilegedService().apply {
            unlockResult = WakeUnlockResult.CREDENTIAL_REJECTED
        }
        val settings = FakeAppSettingsGateway().apply {
            wakeUnlockEnabled.value = true
            wakeCredential.value = "9999"
        }
        val hook = WakeUnlockHook(FakePrivilegedServicePort(service), settings)

        assertTrue(hook.gating)
        runCatching { hook.engage(scheduleContext()) }.also { assertTrue(it.isFailure) }
    }

    // ── 屏保 ────────────────────────────────────────────────────────

    @Test
    fun `screen saver stays off in foreground mode`() = runTest {
        val saver = RecordingScreenSaver()
        val settings = FakeAppSettingsGateway().apply { screenSaverEnabled.value = true }

        assertNull(ScreenSaverHook(settings, saver).engage(context(RunMode.FOREGROUND)))
        assertEquals(0, saver.shown)
    }

    /** 没盖上就不该登记撤销——否则会去掀用户自己手动盖的那份 */
    @Test
    fun `a screen saver that failed to show registers no release`() = runTest {
        val saver = RecordingScreenSaver(showSucceeds = false)
        val settings = FakeAppSettingsGateway().apply { screenSaverEnabled.value = true }

        assertNull(ScreenSaverHook(settings, saver).engage(context()))
        assertEquals(0, saver.hidden)
    }

    @Test
    fun `a shown screen saver is hidden on teardown`() = runTest {
        val saver = RecordingScreenSaver()
        val settings = FakeAppSettingsGateway().apply { screenSaverEnabled.value = true }

        val release = ScreenSaverHook(settings, saver).engage(context())
        assertNotNull(release)
        release!!(RunEndReason.Ran(ExecutionResult.Completed(emptyList())))

        assertEquals(1, saver.shown)
        assertEquals(1, saver.hidden)
    }

    // ── 自动熄屏：采样必须发生在唤醒之前 ────────────────────────────

    @Test
    fun `auto sleep is skipped when the phone was already awake`() = runTest {
        val service = FakePrivilegedService().apply { screenOn = true }
        val hook = AutoSleepHook(FakePrivilegedServicePort(service))

        val release = hook.engage(
            scheduleContext(ScheduleRunOptions(autoSleepAfterTask = true, skipAutoSleepIfAwake = true)),
        )
        // 采样之后屏幕状态怎么变都不影响判断——值已经在闭包里了
        service.screenOn = false
        release!!(RunEndReason.Ran(ExecutionResult.Completed(emptyList())))

        assertEquals(0, service.lockAndSleepCount)
    }

    @Test
    fun `auto sleep fires when the run took over an idle phone`() = runTest {
        val service = FakePrivilegedService().apply { screenOn = false }
        val hook = AutoSleepHook(FakePrivilegedServicePort(service))

        hook.engage(
            scheduleContext(ScheduleRunOptions(autoSleepAfterTask = true, skipAutoSleepIfAwake = true)),
        )!!(RunEndReason.Ran(ExecutionResult.Completed(emptyList())))

        assertEquals(1, service.lockAndSleepCount)
    }

    /** 压根没跑起来就别熄屏：用户点了 Start 看到失败，屏幕还黑了 */
    @Test
    fun `auto sleep does not fire when the run never started`() = runTest {
        val service = FakePrivilegedService().apply { screenOn = false }
        val hook = AutoSleepHook(FakePrivilegedServicePort(service))

        hook.engage(scheduleContext(ScheduleRunOptions(autoSleepAfterTask = true)))!!(
            RunEndReason.NotRun(NotRunCause.Rejected),
        )

        assertEquals(0, service.lockAndSleepCount)
    }

    // ── 倒计时 ──────────────────────────────────────────────────────

    private fun scheduleContext(
        options: ScheduleRunOptions = ScheduleRunOptions(),
        signals: RunSignals = RunSignals(),
        runMode: RunMode = RunMode.BACKGROUND,
    ) = RunContext(
        trigger = RunTrigger.Schedule("s1", options),
        runMode = runMode,
        plan = plan,
        signals = signals,
    )

    /** 手动 Start 不该被拖住：trigger 不是 Schedule 就没有倒计时 */
    @Test
    fun `manual trigger has no countdown`() = runTest {
        assertNull(CountdownHook.engage(context()))
    }

    @Test
    fun `countdown waits and reports each second`() = runTest {
        val ticks = mutableListOf<String>()
        val ctx = RunContext(
            trigger = RunTrigger.Schedule("s1", ScheduleRunOptions(countdownSeconds = 3)),
            runMode = RunMode.BACKGROUND,
            plan = plan,
            progress = RunProgress { hookId, _ -> ticks += hookId },
        )

        CountdownHook.engage(ctx)

        assertEquals(3, ticks.size)
        assertEquals(3_000L, currentTime)
    }

    @Test
    fun `start now cuts the wait short`() = runTest {
        val signals = RunSignals().apply { requestStartNow() }

        CountdownHook.engage(scheduleContext(ScheduleRunOptions(countdownSeconds = 30), signals))

        assertEquals(0L, currentTime)
    }

    /** gating：取消要中止整轮，不是等完照跑 */
    @Test
    fun `cancel aborts the run`() = runTest {
        val signals = RunSignals().apply { requestCancel() }

        assertTrue(CountdownHook.gating)
        assertTrue(runCatching { CountdownHook.engage(scheduleContext(ScheduleRunOptions(countdownSeconds = 30), signals)) }.isFailure)
    }

    /** 两个都点过说明用户改了主意，以「立即开始」为准 */
    @Test
    fun `start now wins over an earlier cancel`() = runTest {
        val signals = RunSignals().apply {
            requestCancel()
            requestStartNow()
        }

        assertNull(CountdownHook.engage(scheduleContext(ScheduleRunOptions(countdownSeconds = 30), signals)))
    }

    // ── 关目标应用 ──────────────────────────────────────────────────

    @Test
    fun `target app is closed after a completed run`() = runTest {
        val service = FakePrivilegedService()
        val hook = CloseTargetAppHook(FakePrivilegedServicePort(service))

        hook.engage(scheduleContext(ScheduleRunOptions(closeAppAfterTask = true)))!!(
            RunEndReason.Ran(ExecutionResult.Completed(emptyList())),
        )

        assertEquals(1, service.stopTargetAppCount)
    }

    /** 用户手动停多半是想接手看看现场，别把人家应用关了 */
    @Test
    fun `target app survives a manual stop`() = runTest {
        val service = FakePrivilegedService()
        val hook = CloseTargetAppHook(FakePrivilegedServicePort(service))

        hook.engage(scheduleContext(ScheduleRunOptions(closeAppAfterTask = true)))!!(
            RunEndReason.Ran(ExecutionResult.Cancelled(emptyList())),
        )

        assertEquals(0, service.stopTargetAppCount)
    }

    /** 特权进程断了时收尾不该反过来触发重连 */
    @Test
    fun `teardown is a no-op when the privileged process is gone`() = runTest {
        val port = FakePrivilegedServicePort(service = null)

        CloseTargetAppHook(port).engage(
            scheduleContext(ScheduleRunOptions(closeAppAfterTask = true)),
        )!!(RunEndReason.Ran(ExecutionResult.Completed(emptyList())))
    }
}
