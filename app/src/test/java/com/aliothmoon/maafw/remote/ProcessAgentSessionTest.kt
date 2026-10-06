package com.aliothmoon.maafw.remote

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 真起一个子进程：要测的就是「等在 child 上」这件事，替身替不出来 */
class ProcessAgentSessionTest {

    private val events = Collections.synchronizedList(mutableListOf<String>())
    private val exited = CountDownLatch(1)
    private var process: Process? = null

    @After
    fun tearDown() {
        process?.destroyForcibly()
    }

    private fun session(vararg command: String): ProcessAgentSession {
        val started = ProcessBuilder(*command).start().also { process = it }
        return ProcessAgentSession(
            executable = command.first(),
            process = started,
            onOutput = { line, fromStderr -> events += if (fromStderr) "err:$line" else "out:$line" },
            onUnexpectedExit = { _, exitCode ->
                events += "exit:$exitCode"
                exited.countDown()
            },
        )
    }

    /** 写一行 stderr 再以 3 退出 */
    private fun failing() = if (WINDOWS) arrayOf("cmd", "/c", "echo boom 1>&2 & exit 3") else arrayOf("sh", "-c", "echo boom >&2; exit 3")

    /** 一直等 stdin，关掉 stdin 就以 0 退出 */
    private fun waiting() = if (WINDOWS) arrayOf("cmd", "/c", "pause >nul") else arrayOf("cat")

    @Test
    fun `an exit nobody asked for is reported after the last output`() {
        session(*failing())
        assertTrue(exited.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(listOf("err:boom", "exit:3"), events.map(String::trim))
    }

    @Test
    fun `close does not report the kill it caused`() {
        session(*waiting()).close()
        assertEquals(false, exited.await(QUIET_MILLIS, TimeUnit.MILLISECONDS))
    }

    /** Disconnect 握手之后 child 自己退出，发生在 close 之前 */
    @Test
    fun `an expected exit is not reported`() {
        val session = session(*waiting())
        session.expectExit()
        process!!.outputStream.close()
        assertTrue(process!!.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(false, exited.await(QUIET_MILLIS, TimeUnit.MILLISECONDS))
        session.close()
    }

    private companion object {
        val WINDOWS = System.getProperty("os.name").orEmpty().startsWith("Windows")
        const val TIMEOUT_SECONDS = 10L
        const val QUIET_MILLIS = 500L
    }
}
