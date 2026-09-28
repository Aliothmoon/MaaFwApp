package com.aliothmoon.maafw.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.theme.MaaDesignTokens

/**
 * 列表为空（加载中或确实没有内容）时的提示，和 LazyColumn 叠在同一个 Box 里、画在它上面
 *
 * - 不要整页换成它、有数据才组合 LazyColumn：Compose 在手指按下那一刻就定下整次手势归谁，
 *   列表还不存在时按下的手指落不到列表上，数据回来列表出现了这一次拖动也滚不动
 * - 也不要做成列表的一项：列表在拿到第一次非空布局前会保留恢复出来的滚动位置，
 *   多一个占位项就把它冲掉了（换语言等 Activity 重建后回不到原来的位置）
 *
 * 本身没有指针处理，不占命中测试，按在它上面的手势照样落到下面的列表
 */
@Composable
fun ListPlaceholder(@StringRes text: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(text),
        modifier = modifier.padding(MaaDesignTokens.Spacing.lg),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
