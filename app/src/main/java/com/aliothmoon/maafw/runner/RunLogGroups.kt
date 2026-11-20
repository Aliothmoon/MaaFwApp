package com.aliothmoon.maafw.runner

import kotlinx.serialization.Serializable

/**
 * 任务起止行上的标记，由 [RunLogComposer] 打在「任务开始 / 完成 / 失败」那三句上
 *
 * 只记展示名不记 task_id：串行投递，同时只有一个任务在跑，起止之间的行都归它。
 * 按名字进会话文件，历史详情页靠它分组
 */
@Serializable
data class RunLogTaskMark(val edge: RunLogTaskEdge, val name: String)

@Serializable
enum class RunLogTaskEdge {
    Start,
    Succeeded,
    Failed,
}

/** 分组只看这三样；屏上的 [RunLogEntry] 与会话文件的 [RunSessionRecord.Line] 共用一套切法 */
interface RunLogLine {
    val atMillis: Long
    val kind: RunLogKind
    val task: RunLogTaskMark?
}

enum class RunLogTaskStatus {
    Running,
    Succeeded,
    Failed,

    /** 没等到结束行这一轮就停了：被叫停、进程被杀，或结束行没进窗口 */
    Interrupted,
}

class RunLogGroup<out T : RunLogLine>(
    /** null 是无标题组：首个任务前的连接与资源加载，或起点行被 500 条滑窗挤掉的那一段 */
    val start: T?,
    val end: T?,
    /**
     * 起点行不在内，表头就是它；成功的结束行也不在内，用时已经上了表头。
     * 失败的结束行留在组里：原因与 details_json 要看得见
     */
    val lines: List<T>,
) {
    val title: String? get() = start?.task?.name

    val warnings: Int = lines.count { it.kind == RunLogKind.Warning }

    /** 起止都在窗口里才有；中途接上的、还在跑的不算 */
    val elapsedMillis: Long? =
        if (start != null && end != null) (end.atMillis - start.atMillis).coerceAtLeast(0) else null

    /** [running] 指这一轮还没收尾；只有最后一组可能还在跑，前面没结束行的都是断掉的 */
    fun status(running: Boolean, isLast: Boolean): RunLogTaskStatus = when (end?.task?.edge) {
        RunLogTaskEdge.Succeeded -> RunLogTaskStatus.Succeeded
        RunLogTaskEdge.Failed -> RunLogTaskStatus.Failed
        else -> if (running && isLast) RunLogTaskStatus.Running else RunLogTaskStatus.Interrupted
    }

    /** 已结束的默认收起；最后一组与出错组展开，正在看的与要查的都不用再点一下 */
    fun expandedByDefault(running: Boolean, isLast: Boolean): Boolean =
        isLast || status(running, isLast) == RunLogTaskStatus.Failed
}

/**
 * 按任务起止切分
 *
 * - 首个任务前的行自成一个无标题组；agent 输出等没有标记的行按先后落进当时那一组
 * - 结束行之后、下一个开始之前的行仍挂在刚结束的任务下（收尾补充、整轮失败原因）
 * - 组外冒出来的结束行（起点被滑窗挤掉，或重复的结束）当普通行留着，不能丢
 * - 一条标记都没有（旧版本写的会话文件）就只有一个无标题组，等于原来的平铺
 */
fun <T : RunLogLine> groupRunLog(lines: List<T>): List<RunLogGroup<T>> {
    val groups = ArrayList<RunLogGroup<T>>()
    var start: T? = null
    var end: T? = null
    var body = ArrayList<T>()

    fun close() {
        if (start != null || body.isNotEmpty()) groups += RunLogGroup(start, end, body)
        start = null
        end = null
        body = ArrayList()
    }

    for (line in lines) {
        val edge = line.task?.edge
        when {
            edge == RunLogTaskEdge.Start -> {
                close()
                start = line
            }

            edge != null && start != null && end == null -> {
                end = line
                if (edge == RunLogTaskEdge.Failed) body += line
            }

            else -> body += line
        }
    }
    close()
    return groups
}
