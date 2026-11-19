package com.aliothmoon.maafw.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.runner.RunLogEntry
import com.aliothmoon.maafw.runner.RunLogGroup
import com.aliothmoon.maafw.runner.RunLogKind
import com.aliothmoon.maafw.runner.RunLogTaskStatus
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaMarkdown
import com.aliothmoon.maafw.ui.components.RunLogLevelPrefixes
import com.aliothmoon.maafw.ui.components.ansiAnnotated
import com.aliothmoon.maafw.ui.components.maaClickable
import com.aliothmoon.maafw.ui.components.rememberAnsiColorResolver
import com.aliothmoon.maafw.ui.components.runLogColor
import com.aliothmoon.maafw.ui.components.runLogLevelSemantics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 键按类型分开：表头是字符串、行是条目 id（Long），省略提示是固定串，互不相撞 */
internal sealed interface RunLogPanelRow {
    val key: Any
    val contentType: Int
}

internal data class RunLogHeaderRow(
    val group: RunLogGroup<RunLogEntry>,
    val startId: Long,
    val status: RunLogTaskStatus,
    val expanded: Boolean,
    val closesCard: Boolean,
) : RunLogPanelRow {
    override val key: Any get() = "h$startId"
    override val contentType: Int get() = 0
}

internal data class RunLogLineRow(val entry: RunLogEntry, val inCard: Boolean, val last: Boolean) : RunLogPanelRow {
    override val key: Any get() = entry.id
    override val contentType: Int get() = 1
}

internal data class RunLogOmittedRow(val inCard: Boolean) : RunLogPanelRow {
    override val key: Any get() = OMITTED_ROW_KEY
    override val contentType: Int get() = 2
}

private const val OMITTED_ROW_KEY = "raw-omitted"

/** 收起的组只出表头；省略提示跟着它前面那条原始行走，所在组收起时一起藏 */
internal fun buildRunLogRows(
    groups: List<RunLogGroup<RunLogEntry>>,
    running: Boolean,
    toggled: Map<Long, Boolean>,
    omittedBefore: Long?,
): List<RunLogPanelRow> {
    val rows = ArrayList<RunLogPanelRow>()
    fun addLines(lines: List<RunLogEntry>, inCard: Boolean) {
        lines.forEachIndexed { i, entry ->
            if (entry.id == omittedBefore) rows += RunLogOmittedRow(inCard)
            rows += RunLogLineRow(entry, inCard, last = inCard && i == lines.lastIndex)
        }
    }
    groups.forEachIndexed { index, group ->
        val start = group.start
        if (start == null) {
            addLines(group.lines, inCard = false)
            return@forEachIndexed
        }
        val isLast = index == groups.lastIndex
        val expanded = toggled[start.id] ?: group.expandedByDefault(running, isLast)
        val showLines = expanded && group.lines.isNotEmpty()
        rows += RunLogHeaderRow(group, start.id, group.status(running, isLast), expanded, closesCard = !showLines)
        if (showLines) addLines(group.lines, inCard = true)
    }
    return rows
}

/** 省略提示那一行；完整记录在会话文件里，导出即可看到 */
@Composable
internal fun RawOmittedRow(count: Long, modifier: Modifier = Modifier) {
    val quantity = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    Text(
        text = pluralStringResource(R.plurals.run_log_raw_omitted, quantity, count),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MaaDesignTokens.Spacing.xxs),
    )
}

@Composable
internal fun RunLogRow(
    entry: RunLogEntry,
    time: String,
    expanded: Boolean,
    prefixes: RunLogLevelPrefixes,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pretty = rememberPrettyDetail(entry)
    val body = entry.text.asString()
    // agent 按「输出到终端」配色，转义符只有它这两类会有；其余 kind 不必白跑一趟解析
    val ansiColor = rememberAnsiColorResolver()
    val rendered = remember(body, entry.kind, ansiColor) {
        if (entry.kind == RunLogKind.Agent || entry.kind == RunLogKind.AgentError) {
            ansiAnnotated(body, ansiColor)
        } else {
            AnnotatedString(body)
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .runLogLevelSemantics(prefixes, entry.kind, body)
            .then(if (entry.detail != null) Modifier.maaClickable(onClick = onToggle) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            // PI 模板正文按协议支持 Markdown 与 HTML 子集，等宽直出会把标签露给用户
            if (entry.kind == RunLogKind.Focus) {
                MaaMarkdown(
                    text = body,
                    style = MaterialTheme.typography.labelSmall,
                    color = runLogColor(entry.kind),
                )
                return@Column
            }
            Text(
                text = rendered,
                style = MaterialTheme.typography.labelSmall,
                // ANSI 指定的颜色写在 span 上，压过这里的整行色；没指定的段落仍按 kind 走
                color = runLogColor(entry.kind),
            )
            if (expanded) {
                Text(
                    text = pretty ?: entry.detail.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MaaDesignTokens.Spacing.xxs),
                )
            }
        }
    }
}

/**
 * 解析推迟到渲染这一刻
 *
 * 日志条目是在 ViewModel 的收集协程（主线程）上建的，在那里给每条都解一次 JSON
 * 会把主线程压死；LazyColumn 只组合可见行，配 remember 后一条最多解一次
 */
@Composable
private fun rememberPrettyDetail(entry: RunLogEntry): String? = remember(entry.id) {
    val detail = entry.detail ?: return@remember null
    val root = runCatching { LOG_JSON.parseToJsonElement(detail) }.getOrNull() as? JsonObject
        ?: return@remember null
    runCatching { PRETTY_JSON.encodeToString(JsonElement.serializer(), root) }.getOrNull()
}

private val LOG_JSON = Json { ignoreUnknownKeys = true; isLenient = true }
private val PRETTY_JSON = Json { prettyPrint = true }
