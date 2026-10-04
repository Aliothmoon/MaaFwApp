package com.aliothmoon.maafw.ui.settings.search

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.resolve
import com.aliothmoon.maafw.settings.search.SearchableSetting
import com.aliothmoon.maafw.settings.search.SettingLocation
import com.aliothmoon.maafw.settings.search.SettingSearchEntry
import com.aliothmoon.maafw.settings.search.SettingSearchMatcher
import com.aliothmoon.maafw.settings.search.SettingSearchNavigator
import com.aliothmoon.maafw.settings.search.SettingSearchRequest
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.ITextField
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaNavigationRow
import kotlinx.coroutines.delay

private val LocalSettingSearchRequest = compositionLocalOf<SettingSearchRequest?> { null }
private val LocalSettingSearchNavigator = staticCompositionLocalOf<SettingSearchNavigator?> { null }

/**
 * 在 AppRoot 订阅一次，经 CompositionLocal 下发给各个锚点
 *
 * 不让每个锚点各自 collect：设置页一屏几十个锚点，各起一个收集器会掉帧。
 * compositionLocalOf（非 static）只让真正读它的锚点重组
 */
@Composable
fun ProvideSettingSearch(navigator: SettingSearchNavigator, content: @Composable () -> Unit) {
    val request by navigator.pending.collectAsStateWithLifecycle()
    CompositionLocalProvider(
        LocalSettingSearchNavigator provides navigator,
        LocalSettingSearchRequest provides request,
        content = content,
    )
}

/** 当前请求要不要这个分区展开；可折叠卡把它当 revealToken 传给 MaaCard */
@Composable
fun sectionRevealToken(sectionKey: String): Any? =
    LocalSettingSearchRequest.current?.takeIf { request ->
        val location = request.entry.location
        location is SettingLocation.Section && location.sectionKey == sectionKey && request.isFresh()
    }

/** 当前请求落在哪个位置；任务页用它决定要不要把快捷面板打开 */
@Composable
fun pendingSearchLocation(): SettingLocation? =
    LocalSettingSearchRequest.current?.takeIf { it.isFresh() }?.entry?.location

/**
 * 命中时滚进视野并闪两下，闪完消费请求
 *
 * 只包常显内容；锚点在折叠卡里时，卡先被 [sectionRevealToken] 展开，锚点这才进组合
 */
@Composable
fun SettingSearchTarget(
    anchor: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val request = LocalSettingSearchRequest.current?.takeIf { it.entry.anchor == anchor }
    val fresh = request?.isFresh() == true
    val flash = remember { Animatable(0f) }
    val requester = remember { BringIntoViewRequester() }
    if (request != null) {
        val navigator = LocalSettingSearchNavigator.current
        // 闪完再消费：请求不变，effect 就不会被取消
        LaunchedEffect(request) {
            if (fresh) {
                // 展开动画要几帧才把锚点量出来；滚一次，等动画走完再补一次，免得只露出标题
                withFrameNanos { }
                withFrameNanos { }
                requester.bringIntoView()
                delay(EXPAND_SETTLE_MS)
                requester.bringIntoView()
                repeat(2) {
                    flash.animateTo(1f, tween(180))
                    flash.animateTo(0f, tween(420))
                }
            }
            navigator?.consume(request)
        }
    }

    val highlight = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .bringIntoViewRequester(requester)
            .drawBehind {
                if (flash.value <= 0f) return@drawBehind
                // 往两侧各溢出一点，盖住卡片内边距，看起来是整行亮而不是文字底下一块
                val bleed = HIGHLIGHT_BLEED.toPx()
                drawRect(
                    color = highlight.copy(alpha = 0.18f * flash.value),
                    topLeft = Offset(-bleed, 0f),
                    size = Size(size.width + bleed * 2, size.height),
                )
            },
    ) {
        content()
    }
}

@Composable
fun SettingSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    ITextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = stringResource(R.string.settings_search_hint),
        trailingIcon = {
            if (query.isEmpty()) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.settings_search_clear),
                    )
                }
            }
        },
    )
}

/**
 * 搜索结果；进入搜索才把条目解析成文本，换语言时重算
 *
 * [entries] 由调用方合好：手写的静态条目 + 当前 PI 的选项，且已按条件滤掉此刻不会渲染的
 */
@Composable
fun SettingSearchResults(
    entries: List<SettingSearchEntry>,
    query: String,
    onClick: (SettingSearchEntry) -> Unit,
) {
    val context = LocalContext.current
    val locales = LocalConfiguration.current.locales
    val searchables = remember(entries, locales) {
        entries.map { entry ->
            SearchableSetting(
                entry = entry,
                title = entry.title.resolve(context),
                description = entry.description.resolve(context),
                keywords = entry.keywords.resolve(context),
                path = entry.location.path.joinToString(PATH_SEPARATOR) { it.resolve(context) },
            )
        }
    }
    val results = remember(searchables, query) { SettingSearchMatcher.filter(searchables, query) }

    if (results.isEmpty()) {
        Text(
            text = stringResource(R.string.settings_search_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = MaaDesignTokens.Spacing.lg),
        )
        return
    }
    MaaCard {
        results.forEach { item ->
            MaaNavigationRow(
                label = item.title,
                description = item.path,
                onClick = { onClick(item.entry) },
            )
        }
    }
}

private const val PATH_SEPARATOR = " › "
private const val EXPAND_SETTLE_MS = 280L
private val HIGHLIGHT_BLEED = 8.dp
