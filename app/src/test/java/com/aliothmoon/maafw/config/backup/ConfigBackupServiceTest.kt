package com.aliothmoon.maafw.config.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.aliothmoon.maafw.config.InMemoryUserConfigurationStore
import com.aliothmoon.maafw.config.backup.BackupFixtures.definition
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.project.FakeProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.RunnerState
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager
import com.aliothmoon.maafw.schedule.ScheduleStrategy
import com.aliothmoon.maafw.schedule.ScheduleStrategyStore
import com.aliothmoon.maafw.settings.AppSettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.InputStream
import java.time.DayOfWeek
import java.time.LocalTime

/** 服务层的拒绝路径：运行中、项目没加载、文件太大、定时没读出来 */
class ConfigBackupServiceTest {

    private val configId = RunConfigurationId("c1")
    private val initial = UserConfiguration(
        initialized = true,
        configurations = listOf(RunConfiguration(configId, "原有")),
        activeConfigurationId = configId,
    )

    private val store = InMemoryUserConfigurationStore(initial)
    private val runnerState = MutableStateFlow(RunnerState())
    private val schedulesLoaded = MutableStateFlow(true)
    private val oldSchedule = ScheduleStrategy(
        id = "old",
        name = "旧规则",
        runConfigurationId = configId.value,
        daysOfWeek = setOf(DayOfWeek.MONDAY),
        executionTimes = listOf(LocalTime.of(8, 0)),
    )
    private val scheduleStore = mockk<ScheduleStrategyStore> {
        every { isLoaded } returns schedulesLoaded
        every { strategies } returns MutableStateFlow(listOf(oldSchedule))
        coEvery { replaceAll(any()) } just runs
        coEvery { addAll(any()) } just runs
    }
    private val alarms = mockk<ScheduleAlarmManager>(relaxed = true)
    private val appSettings = mockk<AppSettingsManager> {
        coEvery { portableSettings() } returns emptyMap()
        coEvery { importPortableSettings(any()) } just runs
    }
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val context = mockk<Context> { every { contentResolver } returns resolver }
    private val project = FakeProjectRepository(ProjectState.Ready(definition, emptyList()))

    private val service = ConfigBackupService(
        context = context,
        configurationStore = store,
        scheduleStore = scheduleStore,
        alarms = alarms,
        appSettings = appSettings,
        projectRepository = project,
        runnerPort = mockk<RunnerPort> { every { state } returns runnerState },
        storeReadyTimeoutMs = 50,
    )

    private fun restoreImport(): ConfigImport.Restore {
        val other = RunConfigurationId("c2")
        val backup = ConfigBackupCodec.export(
            UserConfiguration(initialized = true, configurations = listOf(RunConfiguration(other, "备份里的"))),
            definition, emptyList(), emptyMap(), BackupAppInfo("a", "1"), 0L,
        )
        return ConfigImport.Restore(backup)
    }

    @Test
    fun `运行中不许导入，什么都不写`() = runTest {
        runnerState.value = RunnerState(phase = RunnerPhase.Running)

        assertEquals(ConfigApplyResult.Locked, service.apply(restoreImport()))
        assertEquals(initial, store.current)
        coVerify(exactly = 0) { scheduleStore.replaceAll(any()) }
        coVerify(exactly = 0) { appSettings.importPortableSettings(any()) }
    }

    @Test
    fun `项目还没加载完不许导入`() = runTest {
        project.emit(ProjectState.Loading)

        assertEquals(ConfigApplyResult.ProjectNotReady, service.apply(restoreImport()))
        assertEquals(initial, store.current)
    }

    @Test
    fun `定时没读出来就不恢复，配置原样不动`() = runTest {
        schedulesLoaded.value = false

        assertEquals(ConfigApplyResult.Failed, service.apply(restoreImport()))
        assertEquals(initial, store.current)
        coVerify(exactly = 0) { scheduleStore.replaceAll(any()) }
    }

    @Test
    fun `定时没读出来就不导出，免得导出一份空定时`() = runTest {
        schedulesLoaded.value = false

        assertNull(service.exportTo(mockk<Uri>()))
        verify(exactly = 0) { resolver.openOutputStream(any(), any()) }
    }

    @Test
    fun `恢复时撤掉备份里没有的旧规则的闹钟`() = runTest {
        assertEquals(ConfigApplyResult.Applied, service.apply(restoreImport()))

        verify { alarms.forget("old") }
        coVerify { scheduleStore.replaceAll(emptyList()) }
        verify { alarms.rescheduleAll(emptyList()) }
    }

    @Test
    fun `超过上限的文件读都不读完就拒`() = runTest {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 'a'.code.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill('a'.code.toByte(), off, off + len)
                served += len
                return len
            }
        }
        val uri = mockk<Uri>()
        every { resolver.openInputStream(uri) } returns endless

        assertEquals(ConfigReadResult.Unreadable, service.read(uri))
        // 读到上限再多一块就停，不会把整条流读完
        assertEquals(true, served <= 8L * 1024 * 1024 + 16 * 1024)
    }

    @Test
    fun `读文件抛异常按读不出处理`() = runTest {
        val uri = mockk<Uri>()
        every { resolver.openInputStream(uri) } throws SecurityException("permission revoked")

        assertEquals(ConfigReadResult.Unreadable, service.read(uri))
    }

    @Test
    fun `粘贴的内容认不出就报无法识别`() = runTest {
        assertEquals(ConfigReadResult.Failed(ConfigImportError.Unrecognized), service.parseText("随便一段文字"))
    }
}
