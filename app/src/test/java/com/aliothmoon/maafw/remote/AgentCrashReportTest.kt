package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/** 样本取自真机 `logcat -b crash -d -v threadtime`（Android 13），只截短了寄存器与路径 */
class AgentCrashReportTest {

    private val otherCrash = listOf(
        "10-02 22:20:10.882 24410 24410 F libc    : Fatal signal 11 (SIGSEGV), code 0 (SI_USER from pid 24408, uid 2000) in tid 24410 (sleep), pid 24410 (sleep)",
        "10-02 22:20:10.930 24414 24414 F DEBUG   : *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***",
        "10-02 22:20:10.930 24414 24414 F DEBUG   : Cmdline: sleep 60",
        "10-02 22:20:10.930 24414 24414 F DEBUG   : pid: 24410, tid: 24410, name: sleep  >>> sleep <<<",
        "10-02 22:20:10.931 24414 24414 F DEBUG   :       #00 pc 00000000000e155c  /apex/com.android.runtime/lib64/bionic/libc.so (nanosleep+12)",
    )

    private val agentFatal =
        "10-02 22:20:39.353 24483 24483 F libc    : Fatal signal 11 (SIGSEGV), code 0 (SI_USER from pid 24480, uid 2000) in tid 24483 (libcpp-algo.so), pid 24483 (libcpp-algo.so)"

    private val agentDump = listOf(
        "10-02 22:20:39.436 24491 24491 F DEBUG   : *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***",
        "10-02 22:20:39.436 24491 24491 F DEBUG   : ABI: 'arm64'",
        "10-02 22:20:39.436 24491 24491 F DEBUG   : Cmdline: /data/app/x/lib/arm64/libcpp-algo.so 45678",
        "10-02 22:20:39.436 24491 24491 F DEBUG   : pid: 24483, tid: 24483, name: libcpp-algo.so  >>> /data/app/x/lib/arm64/libcpp-algo.so <<<",
        "10-02 22:20:39.436 24491 24491 F DEBUG   : signal 11 (SIGSEGV), code 0 (SI_USER from pid 24480, uid 2000), fault addr --------",
        "10-02 22:20:39.436 24491 24491 F DEBUG   :     x0  000000761ea7cd10  x1  0000000000000000",
        "10-02 22:20:39.437 24491 24491 F DEBUG   : backtrace:",
        "10-02 22:20:39.437 24491 24491 F DEBUG   :       #00 pc 0000000000089cf0  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+32)",
    )

    private val buffer = listOf("--------- beginning of crash") + otherCrash + agentFatal + agentDump

    @Test
    fun `picks the fatal line and the dump that names the pid`() {
        assertEquals(listOf(agentFatal) + agentDump, AgentCrashReport.extract(buffer, 24483))
    }

    /** 整块 DEBUG 挂在 crash_dump 的 pid 下，别的进程同时崩时两块会交错 */
    @Test
    fun `lines of another crash interleaved into the dump are left out`() {
        val interleaved = listOf(agentFatal) + agentDump.take(5) + otherCrash + agentDump.drop(5)
        assertEquals(listOf(agentFatal) + agentDump, AgentCrashReport.extract(interleaved, 24483))
    }

    /** crash_dump 的 pid 也会复用，下一块横幅就是别人的了 */
    @Test
    fun `the dump ends at the next banner from the same dumper pid`() {
        val later = otherCrash.drop(1).map { it.replace(" 24414 24414 ", " 24491 24491 ") }
        assertEquals(listOf(agentFatal) + agentDump, AgentCrashReport.extract(buffer + later, 24483))
    }

    /** pid 复用：缓冲区里留着更早那个同 pid 进程的现场，取最后一次 */
    @Test
    fun `the last crash wins when the pid was reused`() {
        val earlier = otherCrash.map { it.replace("24410", "24483") }
        assertEquals(listOf(agentFatal) + agentDump, AgentCrashReport.extract(earlier + agentFatal + agentDump, 24483))
    }

    /** crash_dump 还没写完时只有 child 自己那一行，调用方据此重试 */
    @Test
    fun `only the fatal line is returned while the dump has not landed`() {
        assertEquals(listOf(agentFatal), AgentCrashReport.extract(otherCrash + agentFatal, 24483))
    }

    @Test
    fun `nothing is returned for a pid the buffer does not mention`() {
        assertEquals(emptyList<String>(), AgentCrashReport.extract(buffer, 4242))
        // 24 是 24483 的前缀，不能算命中
        assertEquals(emptyList<String>(), AgentCrashReport.extract(buffer, 24))
    }
}
