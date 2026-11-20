package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.privileged.FakePrivilegedService
import com.aliothmoon.maafw.privileged.FakePrivilegedServicePort
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppBlacklistTest {

    private val plan = RunPlan(
        projectName = "demo",
        projectVersion = "1",
        controller = ControllerDefinition(),
        resource = ResourceDefinition("官服", listOf("./base")),
        runConfigurationId = RunConfigurationId("c1"),
        tasks = emptyList(),
    )

    private val service = FakePrivilegedService().apply { visiblePackages = listOf("com.example.chat", GAME) }
    private val port = FakePrivilegedServicePort(service)
    private var names = setOf(GAME)
    private var locked = false
    private val blacklist = AppBlacklist(port, { names }, { locked }) { "Game" }

    @Before
    fun setUp() {
        // callWithTimeout 在 IO 上跑：换成 Unconfined，免得虚拟时间先把 3 秒超时走完
        mockkObject(MaaDispatchers)
        every { MaaDispatchers.IO } returns Dispatchers.Unconfined
    }

    @After
    fun tearDown() {
        unmockkObject(MaaDispatchers)
    }

    private fun context(
        trigger: RunTrigger = RunTrigger.Schedule("s1"),
        runMode: RunMode = RunMode.BACKGROUND,
        journal: RunJournal = DiscardingRunJournal,
        device: DeviceAtStart? = IN_USE,
    ) = RunContext(trigger, runMode, plan, journal = journal).apply { deviceAtStart.set(device) }

    @Test
    fun `precheck blocks a background schedule while a blacklisted app is on screen`() = runTest {
        val verdict = AppBlacklistPrecheck(blacklist, port).evaluate(context(device = null))

        assertEquals(
            Verdict.Block(
                uiTextOf(
                    R.string.run_blacklist_blocked,
                    uiTextOf(R.string.run_blacklist_hit, "Game"),
                    "com.example.chat, $GAME",
                ),
            ),
            verdict,
        )
    }

    /** 没连着不为这个去连；交给 privileged-connect 之后的挂载物 */
    @Test
    fun `precheck passes when the privileged process is not connected`() = runTest {
        port.service = null

        assertEquals(Verdict.Pass, AppBlacklistPrecheck(blacklist, port).evaluate(context()))
    }

    @Test
    fun `precheck leaves manual, foreground and idle-device runs alone`() = runTest {
        val precheck = AppBlacklistPrecheck(blacklist, port)

        assertEquals(Verdict.Pass, precheck.evaluate(context(trigger = RunTrigger.Manual)))
        assertEquals(Verdict.Pass, precheck.evaluate(context(runMode = RunMode.FOREGROUND)))
        locked = true
        assertEquals(Verdict.Pass, precheck.evaluate(context()))
        locked = false
        service.screenOn = false
        assertEquals(Verdict.Pass, precheck.evaluate(context()))
    }

    @Test
    fun `hook logs the visible apps and fails on a hit`() = runTest {
        val journal = NoteRecordingJournal()

        val result = AppBlacklistHook(blacklist, afterCountdown = false).engage(context(journal = journal))

        assertEquals(uiTextOf(R.string.run_blacklist_hit, "Game"), (result as EngageResult.Failed).reason)
        assertEquals(
            listOf(RunNote.Info to uiTextOf(R.string.run_log_visible_apps, "com.example.chat, $GAME")),
            journal.notes,
        )
    }

    /** 唤醒前没在用就是接管一台闲着的设备 */
    @Test
    fun `hook skips when the device was idle before waking`() = runTest {
        val result = AppBlacklistHook(blacklist, afterCountdown = false).engage(context(device = IDLE))

        assertTrue(result is EngageResult.Skipped)
    }

    @Test
    fun `an unreadable task list lets the run go on and says so`() = runTest {
        service.visiblePackages = null
        val journal = NoteRecordingJournal()

        val result = AppBlacklistHook(blacklist, afterCountdown = false).engage(context(journal = journal))

        assertTrue(result is EngageResult.Skipped)
        assertEquals(listOf(RunNote.Info to uiTextOf(R.string.run_log_blacklist_unknown)), journal.notes)
    }

    /** 点了「立即开始」就是人在跟前点了头；倒计时里放下了手机也不再查 */
    @Test
    fun `recheck after the countdown skips start-now and a device put down`() = runTest {
        val hook = AppBlacklistHook(blacklist, afterCountdown = true)
        val startNow = context().apply { signals.requestStartNow() }
        assertTrue(hook.engage(startNow) is EngageResult.Skipped)

        service.screenOn = false
        assertTrue(hook.engage(context()) is EngageResult.Skipped)

        service.screenOn = true
        assertTrue(hook.engage(context()) is EngageResult.Failed)
    }

    @Test
    fun `block sources from either check count as the blacklist`() {
        assertTrue(BlockSource.Precheck(AppBlacklistPrecheck.ID).isAppBlacklist)
        assertTrue(BlockSource.Hook(AppBlacklistHook.RECHECK_ID, NotRunCause.HookFailed).isAppBlacklist)
        assertTrue(!BlockSource.Hook(PrivilegedConnectHook.ID, NotRunCause.HookFailed).isAppBlacklist)
    }

    private companion object {
        const val GAME = "com.example.game"
        val IDLE = DeviceAtStart(screenOn = false, keyguardLocked = true)
        val IN_USE = DeviceAtStart(screenOn = true, keyguardLocked = false)
    }
}
