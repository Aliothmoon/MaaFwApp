package com.aliothmoon.maafw.runner

/** agent child 的退出码怎么读；特权进程判要不要捞现场、app 侧合成日志行用同一套 */
object AgentExitCode {

    /** `Process.waitFor` 对被信号杀死的 child 返回 128 + 信号号 */
    fun signalOf(exitCode: Int): Int? = (exitCode - SIGNAL_BASE).takeIf { it in 1..MAX_SIGNAL }

    fun signalName(signal: Int): String = NAMES[signal] ?: "signal $signal"

    /** debuggerd 接管的那几个信号才会往 logcat 的 crash 缓冲区写现场；SIGKILL、SIGTERM 不会 */
    fun leavesCrashDump(signal: Int): Boolean = signal in DUMPED

    private const val SIGNAL_BASE = 128
    private const val MAX_SIGNAL = 64

    private val NAMES = mapOf(
        1 to "SIGHUP",
        2 to "SIGINT",
        3 to "SIGQUIT",
        4 to "SIGILL",
        5 to "SIGTRAP",
        6 to "SIGABRT",
        7 to "SIGBUS",
        8 to "SIGFPE",
        9 to "SIGKILL",
        11 to "SIGSEGV",
        13 to "SIGPIPE",
        15 to "SIGTERM",
        16 to "SIGSTKFLT",
        31 to "SIGSYS",
    )

    private val DUMPED = setOf(4, 5, 6, 7, 8, 11, 16, 31)
}
