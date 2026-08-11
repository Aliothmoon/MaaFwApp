package com.aliothmoon.maafw.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.aliothmoon.maafw.runner.RunLogKind
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
