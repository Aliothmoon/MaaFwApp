package com.aliothmoon.maafw.i18n

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * 延迟到展示那一刻才解析的文本
 *
 * 领域层、Resolver、Builder、ViewModel 都拿不到 Context，产出文案的唯一办法就是携带
 * 资源 id 与参数，等 UI 解析。切语言后 Activity 重建
 */
@Immutable
sealed interface UiText {
    data object Empty : UiText

    /**
     * 已经是成品文本，**不参与本地化**
     *
     * 别直接构造，走 [uiTextFromProject] / [uiTextFromFramework] / [uiTextFormatted]——
     * 这三个名字说清了「为什么不翻译」，而裸的 `Verbatim("…")` 看不出是有意还是漏了。
     * 单模块下 `internal` 挡不住任何人，靠 `UiTextBoundaryTest` 扫源码兜底
     */
    data class Verbatim(val value: String) : UiText

    /** [args] 里可以再放 UiText，解析时递归展开 */
    data class Resource(
        @param:StringRes val resId: Int,
        val args: List<Any?> = emptyList(),
    ) : UiText

    /**
     * [count] 只用来选单复数形式，**不会自动进 [args]**——文案里要露出这个数就得再传一次
     *
     * 不能自动前置：`home_diagnostics_summary_with_errors` 与 `template_task_count`
     * 这两条的选形数都不是第一个占位符，自动塞会当场对不上
     */
    data class Plural(
        @param:PluralsRes val resId: Int,
        val count: Int,
        val args: List<Any?> = emptyList(),
    ) : UiText

    data class Joined(
        val parts: List<UiText>,
        val separator: UiText = Empty,
    ) : UiText
}

fun uiTextOf(@StringRes resId: Int, vararg args: Any?): UiText =
    UiText.Resource(resId = resId, args = args.toList())

fun uiTextPlural(@PluralsRes resId: Int, count: Int, vararg args: Any?): UiText =
    UiText.Plural(resId = resId, count = count, args = args.toList())

/** PI 作者写的文案：task / option 的 label 与 description */
fun uiTextFromProject(label: String?): UiText = verbatimOrEmpty(label)

/** MaaFramework 抛回的原文：错误信息、节点名  */
fun uiTextFromFramework(raw: String?): UiText = verbatimOrEmpty(raw)

/** java.time 或数值格式化的产物，本身已随 locale 变化，不需要再查资源 */
fun uiTextFormatted(value: String?): UiText = verbatimOrEmpty(value)

fun uiTextJoin(vararg parts: UiText, separator: String = ""): UiText =
    UiText.Joined(
        parts = parts.filterNot { it is UiText.Empty },
        // 不能走 verbatimOrEmpty：分隔符常是纯空白，那个会把它判成 Empty
        separator = if (separator.isEmpty()) UiText.Empty else UiText.Verbatim(separator),
    )

fun uiTextLines(vararg lines: UiText): UiText = uiTextJoin(*lines, separator = "\n")

fun UiText?.resolve(context: Context): String = when (this) {
    null, UiText.Empty -> ""
    is UiText.Verbatim -> value
    is UiText.Resource -> context.getString(resId, *resolveArgs(context))
    is UiText.Plural -> context.resources.getQuantityString(resId, count, *resolveArgs(context))
    is UiText.Joined -> parts.joinToString(separator.resolve(context)) { it.resolve(context) }
}

@Composable
fun UiText?.asString(): String {
    LocalConfiguration.current
    return resolve(LocalContext.current)
}

private fun verbatimOrEmpty(value: String?): UiText =
    if (value.isNullOrBlank()) UiText.Empty else UiText.Verbatim(value)

private fun UiText.resolveArgs(context: Context): Array<Any?> = when (this) {
    is UiText.Resource -> args
    is UiText.Plural -> args
    else -> emptyList()
}.map { if (it is UiText) it.resolve(context) else it }.toTypedArray()
