package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.constant.WakeUnlockResult
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.domain.UnlockCredential
import com.aliothmoon.maafw.privileged.FakePrivilegedService
import com.aliothmoon.maafw.privileged.FakePrivilegedServicePort
import com.aliothmoon.maafw.privileged.RemoteAccessFailure
import com.aliothmoon.maafw.schedule.UnlockGestureReader
import com.aliothmoon.maafw.privileged.ServiceConnectResult
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.i18n.isResource
import java.util.concurrent.TimeoutException
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

    private fun EngageResult.releaseOrNull(): Release? = when (this) {
        is EngageResult.Engaged -> release
        is EngageResult.Skipped -> release
        is EngageResult.Failed -> null
    }

    private val plan = RunPlan(
        projectName = "demo",
        projectVersion = "1",
        controller = ControllerDefinition(),
        resource = ResourceDefinition("官服", listOf("./base")),
        runConfigurationId = RunConfigurationId("c1"),
        tasks = emptyList(),
    )

    private fun context(runMode: RunMode = RunMode.BACKGROUND) =
        RunContext(RunTrigger.Manual, runMode, plan, journal = DiscardingRunJournal)

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

    // ── 连接特权进程 ────────────────────────────────────────────────

    @Test
    fun `connect hook passes once the privileged process is connected`() = runTest {
        val port = FakePrivilegedServicePort(FakePrivilegedService())

        assertTrue(PrivilegedConnectHook(port).engage(context()) is EngageResult.Skipped)
        assertEquals(1, port.connectCount)
    }

    /** 后端没就绪给的是本地化原因，不是连接器抛出来的英文 message */
    @Test
    fun `connect hook reports a stopped Shizuku as not running`() = runTest {
        val port = FakePrivilegedServicePort().apply {
            connectResult = ServiceConnectResult.NotReady(RemoteBackend.SHIZUKU, RemoteAccessFailure.UNAVAILABLE)
        }

        val failed = PrivilegedConnectHook(port).engage(context()) as EngageResult.Failed

        assertTrue(failed.reason.isResource(R.string.msg_backend_not_running))
        assertEquals(NotRunCause.HookFailed, failed.notRun)
    }

    @Test
    fun `connect hook keeps the spawn failure as the error`() = runTest {
        val error = TimeoutException("binder not attached within 10000ms; launcher log tail: ...")
        val port = FakePrivilegedServicePort().apply {
            connectResult = ServiceConnectResult.Failed(RemoteBackend.SHIZUKU, error)
        }

        val failed = PrivilegedConnectHook(port).engage(context()) as EngageResult.Failed

        assertTrue(failed.reason.isResource(R.string.msg_backend_connect_failed))
        assertEquals(error, failed.error)
    }

    // ── 前台面板 ────────────────────────────────────────────────────

    /** 后台模式截的是虚拟屏，面板盖不着 */
    @Test
    fun `overlay clear only waits in foreground mode`() = runTest {
        var waited = 0
        val hook = OverlayClearHook { waited++ }

        assertTrue(hook.engage(context(RunMode.BACKGROUND)) is EngageResult.Skipped)
        assertEquals(0, waited)
        assertTrue(hook.engage(context(RunMode.FOREGROUND)) is EngageResult.Skipped)
        assertEquals(1, waited)
    }

    // ── 亮屏解锁 ────────────────────────────────────────────────────

    private fun wakeHook(
        service: FakePrivilegedService,
        settings: FakeAppSettingsGateway,
        gestureJson: String = "",
        gestureRecordedAtMs: Long? = null,
        keyguardLocked: Boolean = true,
        port: PrivilegedServicePort = FakePrivilegedServicePort(service),
    ) = WakeUnlockHook(
        servicePort = port,
        settings = settings,
        gestures = object : UnlockGestureReader {
            override suspend fun readJson() = gestureJson
            override suspend fun recordedAtMs() = gestureRecordedAtMs
        },
        keyguardLocked = { keyguardLocked },
        deviceSecure = { true },
    )

    /** 没有总开关：定时到点总要亮屏，「无密码」方式传空凭证，交给特权侧 dismissKeyguard */
    @Test
    fun `swipe unlock runs with an empty credential`() = runTest {
        val service = FakePrivilegedService()

        assertTrue(wakeHook(service, FakeAppSettingsGateway()).engage(scheduleContext()) is EngageResult.Skipped)
        assertEquals(listOf(""), service.unlockCalls)
    }

    /** 手动 Start 时用户正对着亮屏解锁的手机按按钮，解一次是空操作 */
    @Test
    fun `wake unlock never runs for a manual trigger`() = runTest {
        val service = FakePrivilegedService()
        val settings = FakeAppSettingsGateway().apply {
            wakeUnlockType.value = UnlockCredential.TYPE_PIN
            wakeCredential.value = "1234"
        }

        assertTrue(wakeHook(service, settings).engage(context()) is EngageResult.Skipped)
        assertTrue(service.unlockCalls.isEmpty())
    }

    @Test
    fun `wake unlock passes the configured pin through`() = runTest {
        val service = FakePrivilegedService()
        val settings = FakeAppSettingsGateway().apply {
            wakeUnlockType.value = UnlockCredential.TYPE_PIN
            wakeCredential.value = "1234"
        }

        wakeHook(service, settings).engage(scheduleContext())

        assertEquals(listOf("1234"), service.unlockCalls)
    }

    @Test
    fun `gesture unlock replays the recorded gesture`() = runTest {
        val service = FakePrivilegedService()
        val settings = FakeAppSettingsGateway().apply { wakeUnlockType.value = UnlockCredential.TYPE_GESTURE }

        wakeHook(service, settings, gestureJson = "{\"steps\":[]}").engage(scheduleContext())

        assertEquals(listOf("gesture:{\"steps\":[]}"), service.unlockCalls)
    }

    /** 选了手势却没录：退回「无密码」，至少还能把屏点亮 */
    @Test
    fun `gesture without a recording falls back to swipe`() = runTest {
        val service = FakePrivilegedService()
        val settings = FakeAppSettingsGateway().apply { wakeUnlockType.value = UnlockCredential.TYPE_GESTURE }

        wakeHook(service, settings).engage(scheduleContext())

        assertEquals(listOf(""), service.unlockCalls)
    }

    /** 没设锁屏的设备解不出东西也不算失败 */
    @Test
    fun `no keyguard counts as success`() = runTest {
        val service = FakePrivilegedService().apply { unlockResult = WakeUnlockResult.NO_KEYGUARD }

        assertTrue(wakeHook(service, FakeAppSettingsGateway()).engage(scheduleContext()) is EngageResult.Skipped)
    }

    /** gating：抛出去让 RunLauncher 中止整轮，别对着锁屏跑到超时 */
    @Test
    fun `a rejected pin aborts the run`() = runTest {
        val service = FakePrivilegedService().apply {
            unlockResult = WakeUnlockResult.CREDENTIAL_REJECTED
        }
        val settings = FakeAppSettingsGateway().apply {
            wakeUnlockType.value = UnlockCredential.TYPE_PIN
            wakeCredential.value = "9999"
        }
        val hook = wakeHook(service, settings)

        assertTrue(hook.gating)
        assertTrue(hook.engage(scheduleContext()) is EngageResult.Failed)
    }

    /** 报失败但锁屏已经不在（人脸先解开了、手机本就开着）：照常往下跑 */
    @Test
    fun `a failed unlock without a keyguard lets the run go on`() = runTest {
        val service = FakePrivilegedService().apply { unlockResult = WakeUnlockResult.WAKE_FAILED }

        val result = wakeHook(service, FakeAppSettingsGateway(), keyguardLocked = false).engage(scheduleContext())

        assertTrue(result is EngageResult.Skipped)
    }

    /** 冷启动时服务还没连上：要先连再解，不能拿「没连接」直接把整轮拦掉 */
    @Test
    fun `wake unlock connects the service before unlocking`() = runTest {
        val service = FakePrivilegedService()
        val port = object : PrivilegedServicePort by FakePrivilegedServicePort(service) {
            override fun serviceOrNull(): RemoteService? = null
        }

        val result = wakeHook(service, FakeAppSettingsGateway(), port = port).engage(scheduleContext())

        assertTrue(result is EngageResult.Skipped)
        assertEquals(listOf(""), service.unlockCalls)
    }

    /** 认不出的码不能说成服务没连上 */
    @Test
    fun `an unknown result code is reported as unknown`() = runTest {
        val service = FakePrivilegedService().apply { unlockResult = 99 }

        val result = wakeHook(service, FakeAppSettingsGateway()).engage(scheduleContext())

        assertEquals(uiTextOf(R.string.wake_result_unknown), (result as EngageResult.Failed).reason)
    }

    /** 失败多半没人在跟前：配置摘要与特权侧过程写进本轮运行日志，不带 PIN */
    @Test
    fun `a failed unlock records its setup and the privileged trace`() = runTest {
        val service = FakePrivilegedService().apply {
            unlockResult = WakeUnlockResult.CREDENTIAL_REJECTED
            lastWakeTrace = "+0ms unlock, pin"
        }
        val settings = FakeAppSettingsGateway().apply {
            wakeUnlockType.value = UnlockCredential.TYPE_PIN
            wakeCredential.value = "1234"
        }
        val journal = NoteRecordingJournal()
        val ctx = scheduleContext(journal = journal).apply {
            deviceAtStart.set(DeviceAtStart(screenOn = false, keyguardLocked = true))
        }

        wakeHook(service, settings).engage(ctx)

        assertEquals(listOf(RunNote.Warning, RunNote.Info), journal.notes.map { it.first })
        assertEquals(uiTextOf(R.string.run_log_wake_trace, "+0ms unlock, pin"), journal.notes[1].second)
        assertTrue(journal.notes.none { it.second.toString().contains("1234") })
    }

    @Test
    fun `a successful unlock records nothing`() = runTest {
        val journal = NoteRecordingJournal()

        wakeHook(FakePrivilegedService(), FakeAppSettingsGateway()).engage(scheduleContext(journal = journal))

        assertTrue(journal.notes.isEmpty())
    }

    // ── 屏保 ────────────────────────────────────────────────────────

    @Test
    fun `screen saver stays off in foreground mode`() = runTest {
        val saver = RecordingScreenSaver()
        val settings = FakeAppSettingsGateway().apply { screenSaverEnabled.value = true }

        assertTrue(ScreenSaverHook(settings, saver).engage(context(RunMode.FOREGROUND)) is EngageResult.Skipped)
        assertEquals(0, saver.shown)
    }

    /** 没盖上就不该登记撤销——否则会去掀用户自己手动盖的那份 */
    @Test
    fun `a screen saver that failed to show registers no release`() = runTest {
        val saver = RecordingScreenSaver(showSucceeds = false)
        val settings = FakeAppSettingsGateway().apply { screenSaverEnabled.value = true }

        assertNull(ScreenSaverHook(settings, saver).engage(context()).releaseOrNull())
        assertEquals(0, saver.hidden)
    }

    @Test
    fun `a shown screen saver is hidden on teardown`() = runTest {
        val saver = RecordingScreenSaver()
        val settings = FakeAppSettingsGateway().apply { screenSaverEnabled.value = true }

        val release = ScreenSaverHook(settings, saver).engage(context()).releaseOrNull()
        assertNotNull(release)
        release!!(RunEndReason.Ran(ExecutionResult.Completed(emptyList())))

        assertEquals(1, saver.shown)
        assertEquals(1, saver.hidden)
    }

    // ── 自动熄屏：采样必须发生在唤醒之前 ────────────────────────────

    /** 采样排在唤醒之前；手动发起人就在跟前，不采 */
    @Test
    fun `idle probe treats a dark or locked screen as idle and skips manual runs`() = runTest {
        val service = FakePrivilegedService().apply { screenOn = false }
        val dark = scheduleContext()
        IdleProbeHook(FakePrivilegedServicePort(service)) { false }.engage(dark)
        assertEquals(DeviceAtStart(screenOn = false, keyguardLocked = false), dark.deviceAtStart.get())

        service.screenOn = true
        val locked = scheduleContext()
        IdleProbeHook(FakePrivilegedServicePort(service)) { true }.engage(locked)
        assertEquals(true, locked.deviceAtStart.get()?.idle)

        val inUse = scheduleContext()
        IdleProbeHook(FakePrivilegedServicePort(service)) { false }.engage(inUse)
        assertEquals(false, inUse.deviceAtStart.get()?.idle)

        val manual = context()
        IdleProbeHook(FakePrivilegedServicePort(service)) { false }.engage(manual)
        assertNull(manual.deviceAtStart.get())
    }

    @Test
    fun `auto sleep is skipped when the phone was already awake`() = runTest {
        val service = FakePrivilegedService()
        val ctx = scheduleContext(ScheduleRunOptions(autoSleepAfterTask = true, skipAutoSleepIfAwake = true))
        ctx.deviceAtStart.set(IN_USE)

        val release = AutoSleepHook(FakePrivilegedServicePort(service)).engage(ctx).releaseOrNull()
        // 收尾时读到的不是此刻的屏幕：值在 engage 时已捕进闭包
        ctx.deviceAtStart.set(IDLE)
        release!!(RunEndReason.Ran(ExecutionResult.Completed(emptyList())))

        assertEquals(0, service.lockAndSleepCount)
    }

    @Test
    fun `auto sleep fires when the run took over an idle phone`() = runTest {
        val service = FakePrivilegedService()
        val ctx = scheduleContext(ScheduleRunOptions(autoSleepAfterTask = true, skipAutoSleepIfAwake = true))
        ctx.deviceAtStart.set(IDLE)

        AutoSleepHook(FakePrivilegedServicePort(service)).engage(ctx).releaseOrNull()!!(
            RunEndReason.Ran(ExecutionResult.Completed(emptyList())),
        )

        assertEquals(1, service.lockAndSleepCount)
    }

    /** 压根没跑起来就别熄屏：用户点了 Start 看到失败，屏幕还黑了 */
    @Test
    fun `auto sleep does not fire when the run never started`() = runTest {
        val service = FakePrivilegedService()
        val ctx = scheduleContext(ScheduleRunOptions(autoSleepAfterTask = true))
        ctx.deviceAtStart.set(IDLE)

        AutoSleepHook(FakePrivilegedServicePort(service)).engage(ctx).releaseOrNull()!!(
            RunEndReason.NotRun(NotRunCause.Rejected),
        )

        assertEquals(0, service.lockAndSleepCount)
    }

    // ── 关屏幕电源 ──────────────────────────────────────────────────

    /** 只关不恢复：收尾什么都不做，但步骤要记成 ENGAGED */
    @Test
    fun `screen power off turns the panel off for an idle background run`() = runTest {
        val service = FakePrivilegedService()
        val ctx = scheduleContext(ScheduleRunOptions(hardwareScreenOff = true))
        ctx.deviceAtStart.set(IDLE)

        val release = ScreenPowerOffHook(FakePrivilegedServicePort(service)).engage(ctx).releaseOrNull()
        release!!(RunEndReason.Ran(ExecutionResult.Completed(emptyList())))

        assertEquals(listOf(false), service.displayPowerSyncCalls)
    }

    @Test
    fun `screen power off leaves an in-use or foreground run alone`() = runTest {
        val service = FakePrivilegedService()
        val awake = scheduleContext(ScheduleRunOptions(hardwareScreenOff = true))
        awake.deviceAtStart.set(IN_USE)
        val foreground = scheduleContext(ScheduleRunOptions(hardwareScreenOff = true), runMode = RunMode.FOREGROUND)
        foreground.deviceAtStart.set(IDLE)

        ScreenPowerOffHook(FakePrivilegedServicePort(service)).engage(awake)
        ScreenPowerOffHook(FakePrivilegedServicePort(service)).engage(foreground)

        assertTrue(service.displayPowerSyncCalls.isEmpty())
    }

    /** 失败如实记进触发日志的步骤，但不拦整轮 */
    @Test
    fun `screen power off failure is reported`() = runTest {
        val service = FakePrivilegedService().apply { displayPowerSyncResult = false }
        val ctx = scheduleContext(ScheduleRunOptions(hardwareScreenOff = true))
        ctx.deviceAtStart.set(IDLE)

        assertTrue(ScreenPowerOffHook(FakePrivilegedServicePort(service)).engage(ctx) is EngageResult.Failed)
    }

    /** 规则改用关屏幕电源、设备没在用：屏保让位；在用时照盖 */
    @Test
    fun `screen saver steps aside for screen power off`() = runTest {
        val settings = FakeAppSettingsGateway().apply { screenSaverEnabled.value = true }
        val saver = RecordingScreenSaver()
        val idle = scheduleContext(ScheduleRunOptions(hardwareScreenOff = true))
        idle.deviceAtStart.set(IDLE)
        val inUse = scheduleContext(ScheduleRunOptions(hardwareScreenOff = true))
        inUse.deviceAtStart.set(IN_USE)

        ScreenSaverHook(settings, saver).engage(idle)
        assertEquals(0, saver.shown)
        ScreenSaverHook(settings, saver).engage(inUse)
        assertEquals(1, saver.shown)
    }

    // ── 倒计时 ──────────────────────────────────────────────────────

    private fun scheduleContext(
        options: ScheduleRunOptions = ScheduleRunOptions(),
        signals: RunSignals = RunSignals(),
        runMode: RunMode = RunMode.BACKGROUND,
        journal: RunJournal = DiscardingRunJournal,
    ) = RunContext(
        trigger = RunTrigger.Schedule("s1", options),
        runMode = runMode,
        plan = plan,
        signals = signals,
        journal = journal,
    )

    /** 手动 Start 不该被拖住：trigger 不是 Schedule 就没有倒计时 */
    @Test
    fun `manual trigger has no countdown`() = runTest {
        assertTrue(CountdownHook.engage(context()) is EngageResult.Skipped)
    }

    /** 秒数不开放配置，定时触发一律等这么久 */
    @Test
    fun `countdown waits thirty seconds and reports each one`() = runTest {
        val ticks = mutableListOf<String>()
        val ctx = RunContext(
            trigger = RunTrigger.Schedule("s1"),
            runMode = RunMode.BACKGROUND,
            plan = plan,
            progress = RunProgress { hookId, _ -> ticks += hookId },
            journal = DiscardingRunJournal,
        )

        CountdownHook.engage(ctx)

        assertEquals(30, ticks.size)
        assertEquals(30_000L, currentTime)
    }

    @Test
    fun `start now cuts the wait short`() = runTest {
        val signals = RunSignals().apply { requestStartNow() }

        CountdownHook.engage(scheduleContext(signals = signals))

        assertEquals(0L, currentTime)
    }

    /** gating：取消要中止整轮，不是等完照跑 */
    @Test
    fun `cancel aborts the run`() = runTest {
        val signals = RunSignals().apply { requestCancel() }

        assertTrue(CountdownHook.gating)
        assertTrue(CountdownHook.engage(scheduleContext(signals = signals)) is EngageResult.Failed)
    }

    /** 两个都点过说明用户改了主意，以「立即开始」为准 */
    @Test
    fun `start now wins over an earlier cancel`() = runTest {
        val signals = RunSignals().apply {
            requestCancel()
            requestStartNow()
        }

        assertTrue(CountdownHook.engage(scheduleContext(signals = signals)) is EngageResult.Skipped)
    }

    // ── 关目标应用 ──────────────────────────────────────────────────

    @Test
    fun `target app is closed after a completed run`() = runTest {
        val service = FakePrivilegedService()
        val hook = CloseTargetAppHook(FakePrivilegedServicePort(service), FakeAppSettingsGateway())

        hook.engage(scheduleContext(ScheduleRunOptions(closeAppAfterTask = true))).releaseOrNull()!!(
            RunEndReason.Ran(ExecutionResult.Completed(emptyList())),
        )

        assertEquals(1, service.stopTargetAppCount)
    }

    /** 用户手动停多半是想接手看看现场，别把人家应用关了 */
    @Test
    fun `target app survives a manual stop`() = runTest {
        val service = FakePrivilegedService()
        val hook = CloseTargetAppHook(FakePrivilegedServicePort(service), FakeAppSettingsGateway())

        hook.engage(scheduleContext(ScheduleRunOptions(closeAppAfterTask = true))).releaseOrNull()!!(
            RunEndReason.Ran(ExecutionResult.Cancelled(emptyList())),
        )

        assertEquals(0, service.stopTargetAppCount)
    }

    /** 时长上限停的结局同样是 Cancelled，但那不是用户接手，应用照关 */
    @Test
    fun `target app is closed after a stop at the duration limit`() = runTest {
        val service = FakePrivilegedService()
        val hook = CloseTargetAppHook(FakePrivilegedServicePort(service), FakeAppSettingsGateway())
        val ctx = scheduleContext(ScheduleRunOptions(closeAppAfterTask = true))

        val release = hook.engage(ctx).releaseOrNull()!!
        ctx.stoppedAtLimit.set(true)
        release(RunEndReason.Ran(ExecutionResult.Cancelled(emptyList())))

        assertEquals(1, service.stopTargetAppCount)
    }

    /** 特权进程断了时收尾不该反过来触发重连 */
    @Test
    fun `teardown is a no-op when the privileged process is gone`() = runTest {
        val port = FakePrivilegedServicePort(service = null)

        CloseTargetAppHook(port, FakeAppSettingsGateway()).engage(
            scheduleContext(ScheduleRunOptions(closeAppAfterTask = true)),
        ).releaseOrNull()!!(RunEndReason.Ran(ExecutionResult.Completed(emptyList())))
    }

    /** 全局开关管每一轮，手动 Start 那轮压根没有 ScheduleRunOptions 可看 */
    @Test
    fun `the global switch closes the target app on a manual run`() = runTest {
        val service = FakePrivilegedService()
        val settings = FakeAppSettingsGateway().apply { closeAppAfterTask.value = true }
        val hook = CloseTargetAppHook(FakePrivilegedServicePort(service), settings)

        hook.engage(context()).releaseOrNull()!!(RunEndReason.Ran(ExecutionResult.Completed(emptyList())))

        assertEquals(1, service.stopTargetAppCount)
    }

    @Test
    fun `both switches off leaves the target app alone`() = runTest {
        val port = FakePrivilegedServicePort(FakePrivilegedService())

        assertTrue(
            CloseTargetAppHook(port, FakeAppSettingsGateway())
                .engage(scheduleContext(ScheduleRunOptions(closeAppAfterTask = false)))
                is EngageResult.Skipped,
        )
    }

    /** 前台模式没有虚拟屏，看门狗从不起来，全局开着也没有目标可关 */
    @Test
    fun `foreground mode ignores the global switch`() = runTest {
        val port = FakePrivilegedServicePort(FakePrivilegedService())
        val settings = FakeAppSettingsGateway().apply { closeAppAfterTask.value = true }

        assertTrue(
            CloseTargetAppHook(port, settings).engage(context(RunMode.FOREGROUND))
                is EngageResult.Skipped,
        )
    }

    private companion object {
        val IDLE = DeviceAtStart(screenOn = false, keyguardLocked = true)
        val IN_USE = DeviceAtStart(screenOn = true, keyguardLocked = false)
    }
}
