package com.aliothmoon.maafw.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource

/**
 * 列表为空（加载中或确实没有内容）时放进 LazyColumn 的那一行
 *
 * 不要整页换成占位、有数据才组合 LazyColumn：Compose 在手指按下那一刻就定下整次手势归谁，
 * 列表还不存在时按下的手指只能落在占位上，数据回来列表出现了这一次拖动也滚不动——
 * 刚进页面就拖、偏偏拖不动，就是这么来的。列表从第一帧起就在，占位只是它的一项
 */
fun LazyListScope.placeholderItem(@StringRes text: Int) {
    item(key = PLACEHOLDER_KEY, contentType = PLACEHOLDER_KEY) {
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val PLACEHOLDER_KEY = "list-placeholder"
