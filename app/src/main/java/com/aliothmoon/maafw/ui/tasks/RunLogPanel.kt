package com.aliothmoon.maafw.ui.tasks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.runner.RunLogEntry
import com.aliothmoon.maafw.runner.RunLogKind
import com.aliothmoon.maafw.runner.RunLogSnapshot
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaChoiceChip
import com.aliothmoon.maafw.ui.components.MaaMarkdown
import com.aliothmoon.maafw.ui.components.ansiAnnotated
import com.aliothmoon.maafw.ui.components.maaClickable
import com.aliothmoon.maafw.ui.components.rememberAnsiColorResolver
import com.aliothmoon.maafw.ui.components.runLogColor
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志，就地占掉任务列表那块，不再是盖住整屏的 sheet
 *
 * 内嵌而非弹层：看日志时多半同时要看任务进度与那颗启停按钮，全屏弹层把两者都挡了
 * 开关在配置行右侧，本组件不自带关闭钮
 *
 * 「进度」档是 `RunLogComposer` 合成过的人话；「全部」档另外露出 agent 的 stdout / stderr。
 * 带 details_json 的行（失败类）可展开看原样；认不出的回调合成器已经丢掉，全份在 maa.log
 */
@Composable
internal fun RunLogPanel(
    /** 取值而不是值：这里才是真正显示日志的地方，订阅落在这一层 */
    entries: () -> RunLogSnapshot,
    onExport: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var essentialOnly by rememberSaveable { mutableStateOf(true) }
    val snapshot = entries()
    val visible = if (essentialOnly) snapshot.progress else snapshot.all
    // 原始行因限额丢过时，在最老一条保留下来的原始行前面插一句，免得以为日志断档是 bug
    val omittedAt = remember(snapshot, essentialOnly) {
        if (essentialOnly || snapshot.omittedRaw <= 0) {
            -1
        } else {
            visible.indexOfFirst { it.id == snapshot.firstRawId }
        }
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
                label = stringResource(R.string.run_log_filter_progress),
                selected = essentialOnly,
                onClick = { essentialOnly = true },
            )
            MaaChoiceChip(
                label = stringResource(R.string.run_log_filter_all),
                selected = !essentialOnly,
                onClick = { essentialOnly = false },
            )
            Text(
                text = pluralStringResource(R.plurals.run_log_count, visible.size, visible.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onExport) {
                Text(stringResource(R.string.run_log_export))
            }
            TextButton(onClick = onClear, enabled = snapshot.all.isNotEmpty()) {
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
        val scope = rememberCoroutineScope()
        val lastItemIndex = visible.lastIndex + if (omittedAt >= 0) 1 else 0

        // 跟随底部是一个独立状态，只随用户的滚动改变：
        // 不能每来一批新行再看「最后一行在不在屏上」——一批攒了好几行时，它们一进来最后一行就在屏外了，
        // 跟随就此断掉。手指按下即停跟，滚动（含惯性）停下时落在底部才恢复
        var following by rememberSaveable { mutableStateOf(true) }
        LaunchedEffect(listState) {
            listState.interactionSource.interactions.collect {
                if (it is DragInteraction.Start) following = false
            }
        }
        LaunchedEffect(listState) {
            // 跳过首个值：那是进场时的静止态，不是用户滚完了；进场回底由切档那条负责
            snapshotFlow { listState.isScrollInProgress }
                .drop(1)
                .filter { !it }
                .collect { following = !listState.canScrollForward }
        }
        // 不用 animateScrollToItem：高频事件下动画会排队打架
        LaunchedEffect(visible.lastOrNull()?.id) {
            if (following) listState.scrollToItem(lastItemIndex)
        }
        // 切档是换了一份列表，停在旧档的位置没有意义，直接回到底部接着跟
        LaunchedEffect(essentialOnly) {
            following = true
            listState.scrollToItem(lastItemIndex)
        }
        val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
            ) {
                val row: @Composable (RunLogEntry) -> Unit = { entry ->
                    RunLogRow(
                        entry = entry,
                        time = formatter.format(Date(entry.atMillis)),
                        expanded = expandedId == entry.id,
                        onToggle = { expandedId = if (expandedId == entry.id) null else entry.id },
                    )
                }
                if (omittedAt < 0) {
                    items(visible, key = { it.id }) { row(it) }
                } else {
                    items(visible.subList(0, omittedAt), key = { it.id }) { row(it) }
                    item(key = OMITTED_ROW_KEY) { RawOmittedRow(snapshot.omittedRaw) }
                    items(visible.subList(omittedAt, visible.size), key = { it.id }) { row(it) }
                }
            }
            // 往上翻着看时给一个回到底部的入口；点了就接着跟
            ScrollToBottomButton(
                visible = !following && listState.canScrollForward,
                onClick = {
                    following = true
                    scope.launch { listState.scrollToItem(lastItemIndex) }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(MaaDesignTokens.Spacing.sm),
            )
        }
    }
}

/**
 * 单拎出来：写在 Box 里时外层 Column 的 ColumnScope.AnimatedVisibility 会被隐式选中，
 * 那是给 Column 子项用的，在 Box 里调不了
 */
@Composable
private fun ScrollToBottomButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier,
    ) {
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.run_log_scroll_to_bottom),
            )
        }
    }
}

/** 省略提示那一行；完整记录在会话文件里，导出即可看到 */
@Composable
private fun RawOmittedRow(count: Long) {
    val quantity = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    Text(
        text = pluralStringResource(R.plurals.run_log_raw_omitted, quantity, count),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaaDesignTokens.Spacing.xxs),
    )
}

/** 与条目 id（Long）不同类型，不会撞键 */
private const val OMITTED_ROW_KEY = "raw-omitted"

@Composable
private fun RunLogRow(
    entry: RunLogEntry,
    time: String,
    expanded: Boolean,
    onToggle: () -> Unit,
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
