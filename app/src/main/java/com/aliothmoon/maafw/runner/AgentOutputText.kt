package com.aliothmoon.maafw.runner

/**
 * agent 写给用户看的一行：有的 agent 拿不到 Context 发 focus，就直接往 stdout 打 HTML
 * （MaaEnd go-service 的分辨率警告、任务失败提示都是这样）。MXU 对 agent 输出同样按富文本渲染
 *
 * 只认常见的 HTML 标签，不认任意 `<x>`：C++ 日志里的 `vector<int>`、Python traceback 的 `<module>`
 * 都会撞上宽松的写法。带 ANSI 转义的是终端日志，交给原来的配色那条路
 */
internal fun isUserFacingAgentLine(line: String): Boolean =
    '\u001B' !in line && USER_FACING_TAG.containsMatchIn(line)

private val USER_FACING_TAG = Regex(
    "<(span|br|b|i|u|s|a|p|div|font|strong|em|del|small|big|sub|sup|code|pre|hr|img|ul|ol|li|h[1-6]|" +
        "table|thead|tbody|tr|td|th|style)\\b[^>]*>",
    RegexOption.IGNORE_CASE,
)

/** 整批都是写给用户看的行 */
internal val RunnerEvent.AgentOutput.isUserFacing: Boolean
    get() = line.lineSequence().all(::isUserFacingAgentLine)

/**
 * 特权进程按窗口攒批，一批里可能前一行是终端日志、后一行就是写给用户看的 HTML，
 * 两种渲染与归档都不一样：把后者单独拆出来，相邻的同类行仍归一条
 */
internal fun RunnerEvent.AgentOutput.splitUserFacing(): List<RunnerEvent.AgentOutput> {
    val lines = line.lines()
    if (lines.size == 1 || lines.none(::isUserFacingAgentLine)) return listOf(this)
    val parts = mutableListOf<RunnerEvent.AgentOutput>()
    var start = 0
    for (i in 1..lines.size) {
        if (i == lines.size || isUserFacingAgentLine(lines[i]) != isUserFacingAgentLine(lines[start])) {
            parts += RunnerEvent.AgentOutput(lines.subList(start, i).joinToString("\n"), fromStderr)
            start = i
        }
    }
    return parts
}
