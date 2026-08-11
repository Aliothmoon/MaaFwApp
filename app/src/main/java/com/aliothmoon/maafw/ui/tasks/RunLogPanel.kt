package com.aliothmoon.maafw.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.runner.RunLogEntry
import com.aliothmoon.maafw.runner.RunLogKind
import com.aliothmoon.maafw.runner.isEssential
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaChoiceChip
import com.aliothmoon.maafw.ui.components.MaaMarkdown
import com.aliothmoon.maafw.ui.components.maaClickable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志，就地占掉任务列表那块，不再是盖住整屏的 sheet
 *
 * 内嵌而非弹层：看日志时多半同时要看任务进度与那颗启停按钮，全屏弹层把两者都挡了
 * 开关在配置行右侧，本组件不自带关闭钮
 *
 * 「关键」档是 `RunLogComposer` 合成过的人话；「全部」档另外露出没被合成的原始回调，
 * 那些保持等宽、可展开看原样 details_json——排障时对得上官方文档与源码的原文比什么都值钱
 */
@Composable
internal fun RunLogPanel(
    entries: List<RunLogEntry>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var essentialOnly by rememberSaveable { mutableStateOf(true) }
    val visible = remember(entries, essentialOnly) {
        if (essentialOnly) entries.filter { it.isEssential } else entries
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
        ) {
            MaaChoiceChip(
                label = stringResource(R.string.run_log_filter_essential),
                selected = essentialOnly,
                onClick = { essentialOnly = true },
            )
            MaaChoiceChip(
                label = stringResource(R.string.run_log_filter_all),
                selected = !essentialOnly,
                onClick = { essentialOnly = false },
            )
            Text(
                text = stringResource(R.string.run_log_count, visible.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear, enabled = entries.isNotEmpty()) {
                Text(stringResource(R.string.run_log_clear))
            }
        }

        if (visible.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.run_log_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        // 一次只展开一条：details_json 展开就是十来行，多条同时展开这个列表没法看了
        var expandedId by remember { mutableStateOf<Long?>(null) }
        val listState = rememberLazyListState()
        val pinnedToBottom by remember {
            derivedStateOf {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                last == null || last.index >= listState.layoutInfo.totalItemsCount - 2
            }
        }
        // 只在用户本来就贴着底时才跟；否则他往上翻着看，新行一来就被拽回去；
        // 不用 animateScrollToItem：高频事件下动画会排队打架
        LaunchedEffect(visible.lastOrNull()?.id) {
            if (pinnedToBottom) listState.scrollToItem(visible.lastIndex)
        }
        val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
        ) {
            items(visible, key = { it.id }) { entry ->
                RunLogRow(
                    entry = entry,
                    time = formatter.format(Date(entry.atMillis)),
                    expanded = expandedId == entry.id,
                    onToggle = { expandedId = if (expandedId == entry.id) null else entry.id },
                )
            }
        }
    }
}

@Composable
private fun RunLogRow(
    entry: RunLogEntry,
    time: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val parsed = rememberParsedDetail(entry)
    val body = entry.text.asString()
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
                    color = entry.color(),
                )
                return@Column
            }
            Text(
                text = body,
                style = MaterialTheme.typography.labelSmall,
                // 合成过的是人话，按正文排版；原始转储保持等宽，对得上官方文档
                fontFamily = if (entry.kind == RunLogKind.Verbose) FontFamily.Monospace else null,
                color = entry.color(),
            )
            // 原始转储旁只露一个主语（节点名 / 任务 entry / 动作名），其余留给折叠区；
            // 合成过的正文里主语已经在了，再露一次是重复
            if (entry.kind == RunLogKind.Verbose) {
                parsed.subject?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (expanded) {
                Text(
                    text = parsed.pretty ?: entry.detail.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MaaDesignTokens.Spacing.xxs),
                )
            }
        }
    }
}

/** [subject] 摘出来平铺，[pretty] 只在展开时用 */
private data class ParsedDetail(val subject: String?, val pretty: String?)

/**
 * 解析推迟到渲染这一刻
 *
 * 日志条目是在 ViewModel 的收集协程（主线程）上建的，在那里给每条都解一次 JSON
 * 会把主线程压死；LazyColumn 只组合可见行，配 remember 后一条最多解一次
 */
@Composable
private fun rememberParsedDetail(entry: RunLogEntry): ParsedDetail = remember(entry.id) {
    val detail = entry.detail ?: return@remember ParsedDetail(null, null)
    val root = runCatching { LOG_JSON.parseToJsonElement(detail) }.getOrNull() as? JsonObject
        ?: return@remember ParsedDetail(null, null)
    val subject = SUBJECT_KEYS.firstNotNullOfOrNull { (root[it] as? JsonPrimitive)?.contentOrNull }
    val pretty = runCatching { PRETTY_JSON.encodeToString(JsonElement.serializer(), root) }.getOrNull()
    ParsedDetail(subject?.takeIf { it.isNotBlank() }, pretty)
}

/** 分级配色对齐桌面端 MXU 的 `getLogColor` */
@Composable
private fun RunLogEntry.color(): Color = when (kind) {
    RunLogKind.Success -> MaaTheme.palette.success.content
    RunLogKind.Warning -> MaaTheme.palette.warning.content
    RunLogKind.Error -> MaterialTheme.colorScheme.error
    RunLogKind.Info -> MaterialTheme.colorScheme.primary
    // PI 作者写给用户的那条，颜色要压得住满屏灰字
    RunLogKind.Focus -> MaterialTheme.colorScheme.onSurface
    RunLogKind.Agent, RunLogKind.Verbose -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 按可辨识度排序取第一个命中的：节点名 > 任务 entry > 控制器动作 > 资源路径 */
private val SUBJECT_KEYS = listOf("name", "entry", "action", "path")

private val LOG_JSON = Json { ignoreUnknownKeys = true; isLenient = true }
private val PRETTY_JSON = Json { prettyPrint = true }
