package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.constant.AppPaths
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScheduleTriggerLogTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val log = ScheduleTriggerLog()

    @Before
    fun setUp() {
        mockkObject(AppPaths, MaaDispatchers)
        every { AppPaths.LOG_DIR } returns temp.newFolder("log")
        every { MaaDispatchers.IO } returns Dispatchers.Unconfined
    }

    @After
    fun tearDown() {
        unmockkObject(AppPaths, MaaDispatchers)
    }

    private fun entry(scheduledAt: Long) = TriggerLogEntry(
        strategyId = "s1",
        strategyName = "rule",
        scheduledAt = scheduledAt,
        actualAt = scheduledAt + 1,
        result = TriggerResult.FAILED_START,
        failureReason = TriggerFailureReason.EXPIRED,
        detail = "detail",
    )

    @Test
    fun `删一条只去掉那一条，其余照原样读得回来`() = runTest {
        val entries = listOf(entry(1L), entry(2L), entry(3L))
        entries.forEach { log.append(it) }

        log.delete(entries[1].stableId)

        assertEquals(listOf(entries[2], entries[0]), log.readAll())
    }

    @Test
    fun `删到一条不剩时整份文件撤掉`() = runTest {
        val only = entry(1L)
        log.append(only)

        log.delete(only.stableId)

        assertEquals(emptyList<TriggerLogEntry>(), log.readAll())
    }
}
