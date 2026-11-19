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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.runner.RunLogSnapshot
import com.aliothmoon.maafw.runner.elapsedText
import com.aliothmoon.maafw.runner.groupRunLog
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaChoiceChip
import com.aliothmoon.maafw.ui.components.MaaRunLogTaskHeader
import com.aliothmoon.maafw.ui.components.maaClickable
import com.aliothmoon.maafw.ui.components.rememberRunLogCardStyle
import com.aliothmoon.maafw.ui.components.rememberRunLogLevelPrefixes
import com.aliothmoon.maafw.ui.components.runLogLineFrame
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
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
 *
 * 按任务分组成卡片（切法见 [groupRunLog]），首个任务前的连接与加载照旧平铺在最上面
 */
@Composable
internal fun RunLogPanel(
    /** 取值而不是值：这里才是真正显示日志的地方，订阅落在这一层 */
    entries: () -> RunLogSnapshot,
    /** 这一轮还没收尾：最后一组没等到结束行时算「进行中」，否则算「已中断」 */
    running: Boolean,
    onExport: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var essentialOnly by rememberSaveable { mutableStateOf(true) }
    val snapshot = entries()
    val visible = if (essentialOnly) snapshot.progress else snapshot.all
    // 浮窗日志页也用这个面板，按快照记住：几百行不该每次重组都重切一遍
    val groups = remember(snapshot, essentialOnly) { groupRunLog(visible) }
    // 原始行因限额丢过时，在最老一条保留下来的原始行前面插一句，免得以为日志断档是 bug
    val omittedBefore = if (!essentialOnly && snapshot.omittedRaw > 0) snapshot.firstRawId else null

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
            LogToolbarAction(stringResource(R.string.run_log_export), enabled = true, onClick = onExport)
            LogToolbarAction(
                stringResource(R.string.run_log_clear),
                enabled = snapshot.all.isNotEmpty(),
                onClick = onClear,
            )
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
        // 两者都要 saveable：悬浮窗切到别的页会把这块撤掉，回来还得是原样
        var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
        // 用户点过的分组以点过的为准，按起点行 id 记；没点过的走 RunLogGroup.expandedByDefault
        val toggled = rememberSaveable(saver = ToggledGroupsSaver) { mutableStateMapOf() }
        val rows by remember(groups, running, omittedBefore) {
            derivedStateOf { buildRunLogRows(groups, running, toggled, omittedBefore) }
        }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val lastItemIndex = rows.lastIndex

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
        // 不用 animateScrollToItem：高频事件下动画会排队打架。
        // 按行结构而不只按末条 id：新任务开始时上一组默认收起、点开或收起一组，列表高度都会跳
        LaunchedEffect(rows) {
            if (following) listState.scrollToItem(lastItemIndex)
        }
        // 跟随中可视高度变了（转屏、分屏改大小）就重新贴底：LazyColumn 保住的是首行，底部会被挤出屏，
        // 要等下一条日志才回来
        val currentLastIndex by rememberUpdatedState(lastItemIndex)
        LaunchedEffect(listState) {
            snapshotFlow { listState.layoutInfo.viewportSize.height }
                .drop(1)
                .distinctUntilChanged()
                .collect { if (following) listState.scrollToItem(currentLastIndex) }
        }
        // 切档是换了一份列表，停在旧档的位置没有意义，直接回到底部接着跟
        LaunchedEffect(essentialOnly) {
            following = true
            listState.scrollToItem(lastItemIndex)
        }
        val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
        val cardStyle = rememberRunLogCardStyle()
        val prefixes = rememberRunLogLevelPrefixes()
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(rows, key = { it.key }, contentType = { it.contentType }) { row ->
                    when (row) {
                        is RunLogHeaderRow -> MaaRunLogTaskHeader(
                            title = row.title,
                            status = row.status,
                            elapsed = row.elapsedMillis?.let(::elapsedText),
                            warnings = row.warnings,
                            expanded = row.expanded,
                            expandable = row.expandable,
                            style = cardStyle,
                            closesCard = row.closesCard,
                            onToggle = { toggled[row.startId] = !row.expanded },
                        )

                        is RunLogLineRow -> RunLogRow(
                            entry = row.entry,
                            time = formatter.format(Date(row.entry.atMillis)),
                            expanded = expandedId == row.entry.id,
                            prefixes = prefixes,
                            onToggle = { expandedId = if (expandedId == row.entry.id) null else row.entry.id },
                            modifier = Modifier.runLogLineFrame(cardStyle, row.inCard, row.last),
                        )

                        is RunLogOmittedRow -> RawOmittedRow(
                            count = snapshot.omittedRaw,
                            modifier = Modifier.runLogLineFrame(cardStyle, row.inCard, last = false),
                        )
                    }
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

/**
 * 工具栏右侧的「导出 / 清空」：文字样式与内边距照抄 [MaaChoiceChip]，整行高度就等于筛选胶囊
 *
 * 换成 M3 TextButton 会被 40dp 最小高度与 48dp 触控区撑高，胶囊居中后上方多出一截，
 * 和任务列表顶上的间距对不齐
 */
@Composable
private fun LogToolbarAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(MaaTheme.style.radii.button))
            .alpha(if (enabled) 1f else 0.5f)
            .maaClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = MaaDesignTokens.Spacing.md, vertical = MaaDesignTokens.Spacing.sm),
    )
}

/** 只存用户点过的分组，摊平成 [id, expanded, id, expanded, …]；Long 与 Boolean 都进得了 Bundle */
private val ToggledGroupsSaver = listSaver<SnapshotStateMap<Long, Boolean>, Any>(
    save = { map -> map.flatMap { (id, expanded) -> listOf(id, expanded) } },
    restore = { flat ->
        mutableStateMapOf<Long, Boolean>().apply {
            flat.chunked(2).forEach { (id, expanded) -> put(id as Long, expanded as Boolean) }
        }
    },
)
