package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.runner.RunLogKind
import com.aliothmoon.maafw.runner.RunLogTaskStatus
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaIcons
import com.aliothmoon.maafw.theme.MaaTheme

/**
 * 分级配色，对齐桌面端 MXU 的 `getLogColor`
 *
 * 运行日志面板与历史详情页共用一份：同一档在两处不同色，用户会以为是两种东西
 */
@Composable
fun runLogColor(kind: RunLogKind): Color = when (kind) {
    RunLogKind.Success -> MaaTheme.palette.success.content
    RunLogKind.Warning -> MaaTheme.palette.warning.content
    RunLogKind.Error -> MaterialTheme.colorScheme.error
    RunLogKind.Info -> MaterialTheme.colorScheme.primary
    // PI 作者写给用户的那条，颜色要压得住满屏灰字
    RunLogKind.Focus -> MaterialTheme.colorScheme.onSurface
    // stderr 上多半是 traceback 或加载器警告，切到「全部」时要能一眼挑出来
    RunLogKind.AgentError -> MaterialTheme.colorScheme.error
    RunLogKind.Agent, RunLogKind.Verbose -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * 等级只靠颜色表达，读屏要补一个前缀；与 [runLogColor] 同口径，标成错误色的两档都算错误
 *
 * 面板级解析一次传给各行：长列表不在每行调 stringResource
 */
@Immutable
class RunLogLevelPrefixes(private val warning: String, private val error: String) {

    /** null 表示照读正文 */
    fun describe(kind: RunLogKind, body: String): String? = template(kind)?.format(body)

    internal fun marks(kind: RunLogKind): Boolean = template(kind) != null

    private fun template(kind: RunLogKind): String? = when (kind) {
        RunLogKind.Warning -> warning
        RunLogKind.Error, RunLogKind.AgentError -> error
        else -> null
    }
}

@Composable
fun rememberRunLogLevelPrefixes(): RunLogLevelPrefixes {
    val warning = stringResource(R.string.run_log_a11y_warning)
    val error = stringResource(R.string.run_log_a11y_error)
    return remember(warning, error) { RunLogLevelPrefixes(warning, error) }
}

/** 告警与错误行读成「警告：正文」；描述只在读屏取语义时才拼 */
fun Modifier.runLogLevelSemantics(prefixes: RunLogLevelPrefixes, kind: RunLogKind, body: String): Modifier =
    if (!prefixes.marks(kind)) {
        this
    } else {
        semantics(mergeDescendants = true) {
            prefixes.describe(kind, body)?.let { contentDescription = it }
        }
    }

/**
 * 任务卡的配色与四种分段形状
 *
 * 一张卡拆成表头与逐行几个 LazyColumn 项：整张卡做成一项的话，跑着的那组几百行会一次全组合，
 * 跟随贴底也只能贴到卡顶
 */
@Immutable
class RunLogCardStyle(val fill: Color, val outline: Color, val radius: Dp) {
    internal val single: Shape = RoundedCornerShape(radius)
    internal val top: Shape = RoundedCornerShape(topStart = radius, topEnd = radius)
    internal val bottom: Shape = RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
    internal val middle: Shape = RectangleShape
}

@Composable
fun rememberRunLogCardStyle(): RunLogCardStyle {
    val fill = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline
    val radius = MaaTheme.style.radii.card
    return remember(fill, outline, radius) { RunLogCardStyle(fill, outline, radius) }
}

/**
 * 任务卡的一截：首截上方留组间距，首尾两截带圆角
 *
 * 描边与 [MaaCard] 同一档；中间几截的上下边伸到项外被裁掉，同组的行之间不出横线
 */
fun Modifier.runLogCardSegment(style: RunLogCardStyle, first: Boolean, last: Boolean): Modifier {
    val shape = when {
        first && last -> style.single
        first -> style.top
        last -> style.bottom
        else -> style.middle
    }
    return fillMaxWidth()
        .then(if (first) Modifier.padding(top = MaaDesignTokens.Spacing.sm) else Modifier)
        .clip(shape)
        .background(style.fill)
        .drawWithCache {
            val stroke = MaaDesignTokens.Separator.thickness.toPx()
            val radius = style.radius.toPx()
            val top = if (first) stroke / 2 else -radius * 2
            val bottom = if (last) size.height - stroke / 2 else size.height + radius * 2
            onDrawBehind {
                drawRoundRect(
                    color = style.outline,
                    topLeft = Offset(stroke / 2, top),
                    size = Size(size.width - stroke, bottom - top),
                    cornerRadius = CornerRadius(radius - stroke / 2),
                    style = Stroke(stroke),
                )
            }
        }
}

/**
 * 一行日志的外框：卡里的行是卡的中间或末截，卡外的（无标题组）照旧平铺
 *
 * 行距落在每行的下内边距上，不用 LazyColumn 的 spacedBy：那会在卡的两截之间切出缝
 */
fun Modifier.runLogLineFrame(style: RunLogCardStyle, inCard: Boolean, last: Boolean): Modifier =
    if (inCard) {
        runLogCardSegment(style, first = false, last = last)
            .padding(
                start = MaaDesignTokens.Spacing.md,
                end = MaaDesignTokens.Spacing.md,
                bottom = if (last) MaaDesignTokens.Spacing.sm else MaaDesignTokens.Spacing.xxs,
            )
    } else {
        padding(bottom = MaaDesignTokens.Spacing.xxs)
    }

/**
 * 任务卡的表头：状态、任务名、警告数、用时与展开箭头
 *
 * 运行日志面板与历史详情共用；整行一个读屏焦点，读成「任务名, 状态, 用时, n 条警告」
 */
@Composable
fun MaaRunLogTaskHeader(
    title: String,
    status: RunLogTaskStatus,
    elapsed: UiText?,
    warnings: Int,
    expanded: Boolean,
    /** 组里没有行时不可点，也不画箭头 */
    expandable: Boolean,
    style: RunLogCardStyle,
    /** 收起或组里没有行时表头就是整张卡，要画出底边 */
    closesCard: Boolean,
    onToggle: () -> Unit,
) {
    val statusLabel = stringResource(status.labelRes())
    val elapsedText = elapsed?.asString()
    val warningsLabel = if (warnings > 0) {
        pluralStringResource(R.plurals.run_log_group_warnings, warnings, warnings)
    } else {
        null
    }
    val stateLabel = stringResource(if (expanded) R.string.run_log_group_expanded else R.string.run_log_group_collapsed)
    val actionLabel = stringResource(if (expanded) R.string.common_collapse_action else R.string.common_expand_action)

    Row(
        modifier = Modifier
            .runLogCardSegment(style, first = true, last = closesCard)
            // 排在点击之前：同一节点上内层的语义先算，到这里被整份清掉重设，onClick 由这里带上「展开 / 收起」标签
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(title, statusLabel, elapsedText, warningsLabel).joinToString(", ")
                if (expandable) {
                    role = Role.Button
                    stateDescription = stateLabel
                    onClick(actionLabel) {
                        onToggle()
                        true
                    }
                }
            }
            .then(if (expandable) Modifier.maaClickable(pressScale = false, onClick = onToggle) else Modifier)
            .padding(horizontal = MaaDesignTokens.Spacing.md, vertical = MaaDesignTokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        RunLogTaskStatusIcon(status)
        // 任务名按自身宽度先排、超长截断，警告数紧跟其后；剩下的空白归这一段，用时与箭头靠右
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (warnings > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
                ) {
                    Icon(
                        imageVector = MaaIcons.Warning,
                        contentDescription = null,
                        tint = MaaTheme.palette.warning.content,
                        modifier = Modifier.size(MaaDesignTokens.IconSize.xs),
                    )
                    Text(
                        text = warnings.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaaTheme.palette.warning.content,
                    )
                }
            }
        }
        if (elapsedText != null) {
            Text(
                text = elapsedText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (expandable) MaaExpandChevron(expanded)
    }
}

@Composable
private fun RunLogTaskStatusIcon(status: RunLogTaskStatus) {
    val modifier = Modifier.size(MaaDesignTokens.IconSize.sm)
    when (status) {
        RunLogTaskStatus.Running -> CircularProgressIndicator(
            modifier = modifier,
            strokeWidth = MaaDesignTokens.Border.spinner,
        )

        RunLogTaskStatus.Succeeded -> Icon(
            imageVector = MaaIcons.Check,
            contentDescription = null,
            tint = MaaTheme.palette.success.content,
            modifier = modifier,
        )

        RunLogTaskStatus.Failed -> Icon(
            imageVector = MaaIcons.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )

        RunLogTaskStatus.Interrupted -> Icon(
            imageVector = MaaIcons.Skipped,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

private fun RunLogTaskStatus.labelRes(): Int = when (this) {
    RunLogTaskStatus.Running -> R.string.run_log_group_running
    RunLogTaskStatus.Succeeded -> R.string.run_log_group_succeeded
    RunLogTaskStatus.Failed -> R.string.run_log_group_failed
    RunLogTaskStatus.Interrupted -> R.string.run_log_group_interrupted
}
