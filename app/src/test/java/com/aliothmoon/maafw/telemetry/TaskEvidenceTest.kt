package com.aliothmoon.maafw.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipInputStream

class TaskEvidenceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val logDir by lazy { temp.newFolder("log") }
    private val piRoot by lazy { temp.newFolder("pi") }
    private val sources by lazy { EvidenceSource.of(logDir, piRoot, listOf("debug/**/*.log")) }

    /** 任务开跑的时刻；之前的文件 mtime 拨到它前面，之后的拨到后面，不靠真实时钟的先后 */
    private val startedAt = 1_000_000_000_000L

    private fun file(root: File, path: String, content: String, modified: Long): File =
        File(root, path).apply {
            parentFile?.mkdirs()
            writeText(content)
            setLastModified(modified)
        }

    private fun before(root: File, path: String, content: String) = file(root, path, content, startedAt - 60_000)

    private fun during(root: File, path: String, content: String) = file(root, path, content, startedAt + 5_000)

    private fun start() = TaskEvidence.captureStart(sources, startedAt)

    private fun unzip(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { put(it.name, zip.readBytes().decodeToString()) }
        }
    }

    @Test
    fun `只取任务期间新写的日志与新出现的出错截图`() {
        before(logDir, "maafw.log", "old-log\n")
        before(logDir, "on_error/old.png", "old-image")
        before(logDir, "vision/ignored.png", "vision")
        before(logDir, "quiet.log", "untouched\n")
        val start = start()

        during(logDir, "maafw.log", "old-log\nnew-log\n")
        during(logDir, "on_error/new.png", "new-image")
        during(logDir, "vision/also-ignored.png", "vision")

        TaskEvidence.captureEnd(start).use { selection ->
            val logs = TaskEvidence.buildLogs(selection)
            // 开跑前那一截在 64 KiB 的前文窗口里，一并带上
            assertEquals(listOf("maafw.log"), logs.entries.map { it.source })
            assertEquals("old-log\nnew-log\n", logs.entries.single().content)
            assertEquals("maafw", logs.entries.single().kind)
            assertFalse(logs.truncated)

            val bundle = TaskEvidence.buildImageBundle(selection, "e1", 7) as AttachmentOutcome.Attached
            assertEquals(mapOf("on_error/new.png" to "new-image"), unzip(bundle.bytes))
            assertEquals("maafwapp-task-failure-e1-7-screenshots.zip", bundle.filename)
            assertEquals(1, bundle.imageCount)
            assertEquals(9L, bundle.selectedRawBytes)
        }
    }

    /** 同名截图被覆盖成新的一张也算这个任务的 */
    @Test
    fun `同名截图换了一代算新图`() {
        before(logDir, "on_error/failure.png", "old-image")
        val start = start()
        during(logDir, "on_error/failure.png", "new-image-generation")

        TaskEvidence.captureEnd(start).use { selection ->
            val bundle = TaskEvidence.buildImageBundle(selection, "e1", 7) as AttachmentOutcome.Attached
            assertEquals("new-image-generation", unzip(bundle.bytes)["on_error/failure.png"])
        }
    }

    @Test
    fun `任务期间才出现的日志整份带上，轮转过的也是`() {
        before(logDir, "maafw.log", "x".repeat(100))
        val start = start()
        during(logDir, "maafw.log", "after-rotation\n")
        during(logDir, "maafw.bak.2026.10.01.log", "rotated-out\n")

        TaskEvidence.captureEnd(start).use { selection ->
            val logs = TaskEvidence.buildLogs(selection)
            assertEquals(
                mapOf("maafw.bak.2026.10.01.log" to "rotated-out\n", "maafw.log" to "after-rotation\n"),
                logs.entries.associate { it.source to it.content },
            )
            assertEquals(
                listOf("new_log_included_whole:maafw.bak.2026.10.01.log", "rotated_log_included_whole:maafw.log"),
                logs.warnings,
            )
        }
    }

    /** agent 的日志在 PI 根下，只收配方点名的；外壳日志树里那几个不相干的目录不进 */
    @Test
    fun `agent 日志按配方收，不相干的目录不走`() {
        val start = start()
        during(piRoot, "debug/go-service.log", "agent\n")
        during(piRoot, "debug/cpp-algo/debug/maafw.log", "nested\n")
        during(piRoot, "debug/dump.json", "{}")
        during(piRoot, "resource/pipeline/notes.log", "not-listed\n")
        during(logDir, "app.log", "shell\n")
        during(logDir, "crash/crash.log", "crash\n")
        during(logDir, "export/old.log", "export\n")

        TaskEvidence.captureEnd(start).use { selection ->
            val logs = TaskEvidence.buildLogs(selection)
            // 框架日志排前，外壳自己的其次，其余按名字
            assertEquals(
                listOf("pi/debug/cpp-algo/debug/maafw.log" to "maafw", "app.log" to "maafwapp", "pi/debug/go-service.log" to "other"),
                logs.entries.map { it.source to it.kind },
            )
        }
    }

    @Test
    fun `日志只留尾巴并标记截断`() {
        before(logDir, "maafw.log", "")
        before(logDir, "app.log", "")
        val start = start()
        during(logDir, "maafw.log", "a".repeat(600 * 1024) + "TAIL")
        during(logDir, "app.log", "b".repeat(600 * 1024))

        TaskEvidence.captureEnd(start).use { selection ->
            val logs = TaskEvidence.buildLogs(selection)
            assertTrue(logs.truncated)
            assertEquals(1024L * 1024, logs.selectedRawBytes)
            assertEquals(listOf(512L * 1024, 512L * 1024), logs.entries.map { it.rawBytes })
            assertTrue(logs.entries.first().content.endsWith("TAIL"))
        }
    }

    @Test
    fun `没有新截图或截图太大时不出附件`() {
        val start = start()
        TaskEvidence.captureEnd(start).use { selection ->
            val outcome = TaskEvidence.buildImageBundle(selection, "e1", 7) as AttachmentOutcome.Omitted
            assertEquals("no_evidence", outcome.status)
        }

        during(logDir, "on_error/huge.png", "x".repeat(TaskEvidence.MAX_IMAGE_RAW_BYTES.toInt() + 1))
        TaskEvidence.captureEnd(start).use { selection ->
            val outcome = TaskEvidence.buildImageBundle(selection, "e1", 7) as AttachmentOutcome.Omitted
            assertEquals("raw_too_large", outcome.status)
            assertEquals(TaskEvidence.MAX_IMAGE_RAW_BYTES + 1, outcome.selectedRawBytes)
        }
    }

    /** 终局回调之后才写完的截图靠补采进来；下一个任务的不归这里管，由调用方按代次挡 */
    @Test
    fun `补采把后到的截图并进来`() {
        val start = start()
        during(logDir, "on_error/first.png", "first")

        TaskEvidence.captureEnd(start).use { selection ->
            during(logDir, "on_error/late.png", "late")
            selection.merge(TaskEvidence.scanImages(selection))

            val bundle = TaskEvidence.buildImageBundle(selection, "e1", null) as AttachmentOutcome.Attached
            assertEquals(mapOf("on_error/first.png" to "first", "on_error/late.png" to "late"), unzip(bundle.bytes))
            assertEquals("maafwapp-task-failure-e1-0-screenshots.zip", bundle.filename)
        }
    }
}
