package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.constant.AppPaths
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class RunSessionLogStoreTest {

    private lateinit var logDir: File
    private lateinit var store: RunSessionLogStore

    @Before
    fun setUp() {
        logDir = createTempDirectory("run-session").toFile()
        // 落点与 IO 线程都是进程级固定值，store 内部直读；不打桩会写到真 LOG_DIR，
        // 且 runBlocking 等不到 Dispatchers.IO 上的写盘
        mockkObject(AppPaths)
        every { AppPaths.LOG_DIR } returns logDir
        mockkObject(MaaDispatchers)
        every { MaaDispatchers.IO } returns Dispatchers.Unconfined
        store = RunSessionLogStore()
    }

    @After
    fun tearDown() {
        unmockkObject(AppPaths)
        unmockkObject(MaaDispatchers)
        logDir.deleteRecursively()
    }

    private fun sessionFiles(): List<File> =
        File(logDir, "run").listFiles()?.toList().orEmpty()

    @Test
    fun `a session round trips through the file`() = runBlocking {
        val writer = checkNotNull(store.open(START, listOf("清体力", "签到")))
        writer.write(
            listOf(
                RunSessionRecord.Line(START + 1, RunLogKind.Info, "任务开始: 清体力"),
                RunSessionRecord.Line(START + 2, RunLogKind.Verbose, "Node.Action.Failed", """{"name":"A"}"""),
            ),
        )
        writer.write(listOf(RunSessionRecord.Footer(START + 3, RunSessionOutcome.COMPLETED)))
        writer.close()

        val records = store.read(sessionFiles().single().name)
        assertEquals(4, records.size)
        assertEquals(RunSessionRecord.Header(START, listOf("清体力", "签到")), records[0])
        assertEquals(RunLogKind.Info, (records[1] as RunSessionRecord.Line).kind)
        assertEquals("""{"name":"A"}""", (records[2] as RunSessionRecord.Line).detail)
        assertEquals(RunSessionOutcome.COMPLETED, (records[3] as RunSessionRecord.Footer).outcome)
    }

    /** 摘要全部来自文件名，列表页因此不必读内容 */
    @Test
    fun `the summary comes from the file name`() = runBlocking {
        checkNotNull(store.open(START, listOf("a", "b", "c"))).close()

        val info = store.list().single()
        assertEquals(3, info.taskCount)
        assertEquals(START / 1000, info.startedAt / 1000)
        assertTrue(info.sizeBytes > 0)
    }

    @Test
    fun `same-second sessions with the same task count get distinct files`() = runBlocking {
        checkNotNull(store.open(START, listOf("a"))).close()
        checkNotNull(store.open(START, listOf("a"))).close()

        val names = sessionFiles().map { it.name }
        assertEquals(2, names.size)
        assertNotEquals(names[0], names[1])
        val summaries = store.list()
        assertEquals(listOf(START, START), summaries.map { it.startedAt })
        assertEquals(listOf(1, 1), summaries.map { it.taskCount })
    }

    /** 被杀进程会留下半行；那一行跳过就是了，不该毁掉前面几百条 */
    @Test
    fun `a truncated line does not sink the whole file`() = runBlocking {
        val writer = checkNotNull(store.open(START, listOf("a")))
        writer.write(listOf(RunSessionRecord.Line(START + 1, RunLogKind.Info, "好的")))
        writer.close()
        val file = sessionFiles().single()
        file.appendText("""{"type":"line","atMillis":1,"kin""")

        assertEquals(2, store.read(file.name).size)
    }

    @Test
    fun `cleanup only removes sessions past the window`() = runBlocking {
        checkNotNull(store.open(System.currentTimeMillis(), listOf("新"))).close()
        val old = checkNotNull(store.open(START, listOf("旧")))
        old.close()

        assertEquals(1, store.cleanup(keepDays = 30))
        assertEquals(1, store.list().size)
    }

    @Test
    fun `listing an untouched directory is empty rather than a failure`() = runBlocking {
        assertEquals(emptyList<RunSessionLogFile>(), store.list())
    }

    private companion object {
        /** 2023-11-14；`cleanup` 读真实时钟，这个时刻必须确实是过去 */
        const val START = 1_700_000_000_000L
    }
}
