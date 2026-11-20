package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.i18n.isResource
import com.aliothmoon.maafw.privileged.FakePrivilegedServicePort
import com.aliothmoon.maafw.privileged.FakeRemoteAccessPort
import com.aliothmoon.maafw.privileged.RemoteAccessState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivilegedAccessPrecheckTest {

    private val plan = RunPlan(
        projectName = "demo",
        projectVersion = "1",
        controller = ControllerDefinition(),
        resource = ResourceDefinition("官服", listOf("./base")),
        runConfigurationId = RunConfigurationId("c1"),
        tasks = emptyList(),
    )

    private val shizukuDown = RemoteAccessState(configuredBackend = RemoteBackend.SHIZUKU)
    private val shizukuNotGranted = shizukuDown.copy(shizukuAvailable = true)
    private val shizukuReady = shizukuNotGranted.copy(shizukuGranted = true)

    private fun context(trigger: RunTrigger) =
        RunContext(trigger, RunMode.BACKGROUND, plan, journal = DiscardingRunJournal)

    private val manual = context(RunTrigger.Manual)
    private val schedule = context(RunTrigger.Schedule("s1"))

    /** 默认没连着特权进程，判定只看授权快照 */
    private fun precheck(access: FakeRemoteAccessPort, port: FakePrivilegedServicePort = FakePrivilegedServicePort(null)) =
        PrivilegedAccessPrecheck(access, port, settleTimeoutMs = 5_000L)

    @Test
    fun `a stopped Shizuku blocks as not running`() = runTest {
        val verdict = precheck(FakeRemoteAccessPort(shizukuDown)).evaluate(manual) as Verdict.Block

        assertTrue(verdict.reason.isResource(R.string.msg_backend_not_running))
    }

    @Test
    fun `missing su blocks as unavailable`() = runTest {
        val access = FakeRemoteAccessPort(RemoteAccessState(configuredBackend = RemoteBackend.ROOT))

        val verdict = precheck(access).evaluate(manual) as Verdict.Block

        assertTrue(verdict.reason.isResource(R.string.msg_backend_unavailable))
    }

    /** 手动那轮用户就在跟前：放行，连接时当场请求授权 */
    @Test
    fun `manual start lets a missing grant through without requesting it`() = runTest {
        val access = FakeRemoteAccessPort(shizukuNotGranted)

        assertEquals(Verdict.Pass, precheck(access).evaluate(manual))
        assertTrue(access.requested.isEmpty())
    }

    @Test
    fun `a schedule without the grant is blocked after waiting`() = runTest {
        val access = FakeRemoteAccessPort(shizukuNotGranted)

        val verdict = precheck(access).evaluate(schedule) as Verdict.Block

        assertTrue(verdict.reason.isResource(R.string.msg_backend_auth_rejected))
        assertEquals(5_000L, currentTime)
        assertTrue(access.requested.isEmpty())
    }

    /** 闹钟拉起的冷进程：binder 晚到一会儿不算没运行 */
    @Test
    fun `a schedule waits for the binder of a cold process`() = runTest {
        val access = FakeRemoteAccessPort(shizukuDown).apply {
            upcoming += shizukuDown
            upcoming += shizukuDown
            upcoming += shizukuReady
        }

        assertEquals(Verdict.Pass, precheck(access).evaluate(schedule))
        assertTrue(currentTime < 5_000L)
    }

    /** Shizuku 停了，它拉起的特权进程照样活着 */
    @Test
    fun `a connected privileged process passes even when Shizuku is down`() = runTest {
        val verdict = precheck(FakeRemoteAccessPort(shizukuDown), FakePrivilegedServicePort()).evaluate(schedule)

        assertEquals(Verdict.Pass, verdict)
        assertEquals(0L, currentTime)
    }
}
