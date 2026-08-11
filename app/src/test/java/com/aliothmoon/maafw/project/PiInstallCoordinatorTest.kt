package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.constant.AppPaths
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * 只断言终态与「这一轮到底解没解」
 *
 * 中间的 [PiInstallState.Unpacking] 不断言：进度由解包线程池回报，StateFlow 会把连着来的
 * 几条合并掉，断言它必然出现过就是在赌调度。逐条目回报本身由 `PiInstallerTest` 覆盖
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PiInstallCoordinatorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun mockGlobals() {
        mockkObject(MaaDispatchers)
        every { MaaDispatchers.IO } returns dispatcher
        mockkObject(AppPaths)
    }

    @After
    fun unmockGlobals() {
        unmockkObject(MaaDispatchers)
        unmockkObject(AppPaths)
    }

    private val files = mapOf(
        "interface.json" to """{"interface_version":2}""",
        "tasks/a.json" to "{}",
    )

    private fun coordinatorFor(pkg: PiPackage, base: File, versionCode: Int = 11): PiInstallCoordinator {
        every { AppPaths.externalRoot } returns base
        return PiInstallCoordinator(PiInstaller(pkg, versionCode))
    }

    @Test
    fun `初始态是未检查`() = runTest(dispatcher) {
        val coordinator = coordinatorFor(MapPiPackage(files), temp.newFolder("external"))

        assertEquals(PiInstallState.NotChecked, coordinator.state.value)
    }

    @Test
    fun `首次解包完成后就绪`() = runTest(dispatcher) {
        val pkg = MapPiPackage(files)
        val coordinator = coordinatorFor(pkg, temp.newFolder("external"))

        assertTrue(coordinator.ensureInstalled())

        assertEquals(PiInstallState.Ready, coordinator.state.value)
        assertEquals(files.size, pkg.openCount)
    }

    @Test
    fun `已就绪时不再解包`() = runTest(dispatcher) {
        val pkg = MapPiPackage(files)
        val coordinator = coordinatorFor(pkg, temp.newFolder("external"))
        coordinator.ensureInstalled()

        assertTrue(coordinator.ensureInstalled())

        assertEquals(files.size, pkg.openCount)
    }

    @Test
    fun `reinstall 无条件重解`() = runTest(dispatcher) {
        val pkg = MapPiPackage(files)
        val coordinator = coordinatorFor(pkg, temp.newFolder("external"))
        coordinator.ensureInstalled()

        assertTrue(coordinator.reinstall())

        assertEquals(files.size * 2, pkg.openCount)
        assertEquals(PiInstallState.Ready, coordinator.state.value)
    }

    /** 失败不抛给调用方：返回 false，调用方据此跳过 reload */
    @Test
    fun `解包失败落到 Failed`() = runTest(dispatcher) {
        val coordinator = coordinatorFor(BrokenPiPackage, temp.newFolder("external"))

        assertFalse(coordinator.ensureInstalled())

        assertTrue(coordinator.state.value is PiInstallState.Failed)
    }

    /** 弹窗上的「重试」走的就是这条；Failed 不是终点 */
    @Test
    fun `Failed 之后仍能重试到就绪`() = runTest(dispatcher) {
        val base = temp.newFolder("external")
        coordinatorFor(BrokenPiPackage, base).ensureInstalled()

        val coordinator = coordinatorFor(MapPiPackage(files), base)
        assertTrue(coordinator.reinstall())

        assertEquals(PiInstallState.Ready, coordinator.state.value)
    }

    private object BrokenPiPackage : PiPackage {
        override fun manifest(): List<String> = listOf("interface.json")

        override fun open(path: String): InputStream = throw FileNotFoundException(path)
    }
}
