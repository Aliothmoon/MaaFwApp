package com.aliothmoon.maafw.i18n

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * 延迟到展示那一刻才解析的文本
 *
 * 领域层、Resolver、Builder、ViewModel 都拿不到 Context，产出文案的唯一办法就是携带
 * 资源 id 与参数，等 UI 解析。切语言后 Activity 重建，同一个 [UiText] 自然出新语言的文案；
 * 若在产出处就 `getString`，那一刻的语言会被永久冻进值里
 *
 * **不可持久化**：`resId` 是构建期生成的 int，跨版本会变。要落盘的东西存稳定枚举或原文，
 * 读回来再包成 UiText
 */
@Immutable
sealed interface UiText {
    data object Empty : UiText

    /** 已经是成品文本：PI 里的 label、MaaFramework 抛回的技术原文等，不翻译 */
    data class Dynamic(val value: String) : UiText

    /** [args] 里可以再放 UiText，解析时递归展开 */
    data class Resource(
        @param:StringRes val resId: Int,
        val args: List<Any?> = emptyList(),
    ) : UiText

    data class Joined(
        val parts: List<UiText>,
        val separator: UiText = Empty,
    ) : UiText
}

fun uiTextOf(@StringRes resId: Int, vararg args: Any?): UiText =
    UiText.Resource(resId = resId, args = args.toList())

/** 空白归一成 [UiText.Empty]，免得调用方到处判 null 与 isBlank */
fun uiTextDynamic(value: String?): UiText =
    if (value.isNullOrBlank()) UiText.Empty else UiText.Dynamic(value)

fun uiTextDynamicOr(value: String?, @StringRes fallback: Int): UiText =
    if (value.isNullOrBlank()) uiTextOf(fallback) else UiText.Dynamic(value)

/** 拼接时先滤掉 [UiText.Empty]，否则分隔符会在两端多出来 */
fun uiTextJoin(vararg parts: UiText, separator: UiText = UiText.Empty): UiText =
    UiText.Joined(parts = parts.filterNot { it is UiText.Empty }, separator = separator)

fun uiTextLines(vararg lines: UiText): UiText =
    uiTextJoin(*lines, separator = UiText.Dynamic("\n"))

fun UiText?.resolve(context: Context): String = when (this) {
    null, UiText.Empty -> ""
    is UiText.Dynamic -> value
    is UiText.Resource -> {
        val resolved = args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray()
        context.getString(resId, *resolved)
    }

    is UiText.Joined -> parts.joinToString(separator.resolve(context)) { it.resolve(context) }
}

/**
 * 读一次 [LocalConfiguration] 建立依赖：locale 变化经配置更新到达时本组合要重跑，
 * 否则同一个 UiText 会留着旧语言的解析结果
 */
@Composable
fun UiText?.asString(): String {
    LocalConfiguration.current
    return resolve(LocalContext.current)
}
