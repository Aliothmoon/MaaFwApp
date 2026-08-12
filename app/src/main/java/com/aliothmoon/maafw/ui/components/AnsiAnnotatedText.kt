package com.aliothmoon.maafw.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.aliothmoon.maafw.theme.MaaTheme

/**
 * ANSI 前景色 → 外壳既有语义色；null = 没有相似档，按普通文本展示
 *
 * 只认对得上设计系统的那几档，不为终端的 16 色另立一套色板：agent 用配色无非是分级别，
 * 分不出来的（亮色、洋红、背景色）宁可不上色也不引入与主题无关的硬编码颜色
 */
@Composable
fun rememberAnsiColorResolver(): (Int) -> Color? {
    val error = MaterialTheme.colorScheme.error
    val success = MaaTheme.palette.success.content
    val warning = MaaTheme.palette.warning.content
    val primary = MaterialTheme.colorScheme.primary
    val info = MaaTheme.palette.info.content
    return remember(error, success, warning, primary, info) {
        { code ->
            when (code) {
                31, 91 -> error
                32, 92 -> success
                33, 93 -> warning
                34, 94 -> primary
                36, 96 -> info
                else -> null
            }
        }
    }
}

/**
 * 把 SGR 转义翻成文本样式，转义符本身一律吃掉
 *
 * 非 SGR 的 CSI（光标移动、清屏之类）同样吃掉但不产生样式——Android 这头没有终端，
 * 那些指令没有对应语义，留着只会变成 `[2J` 这样的噪音
 */
fun ansiAnnotated(text: String, colorOf: (Int) -> Color?): AnnotatedString {
    if (!text.contains(ESC)) return AnnotatedString(text)
    return buildAnnotatedString {
        var index = 0
        var color: Color? = null
        var bold = false

        fun emit(chunk: String) {
            if (chunk.isEmpty()) return
            if (color == null && !bold) {
                append(chunk)
                return
            }
            val style = SpanStyle(
                color = color ?: Color.Unspecified,
                fontWeight = if (bold) FontWeight.Bold else null,
            )
            withStyle(style) { append(chunk) }
        }

        while (index < text.length) {
            val esc = text.indexOf(ESC, index)
            if (esc < 0) {
                emit(text.substring(index))
                break
            }
            emit(text.substring(index, esc))

            // ESC 之后必须紧跟 '['，否则不是 CSI：单独的 ESC 原样留着，免得吞掉正文
            if (esc + 1 >= text.length || text[esc + 1] != '[') {
                emit(ESC.toString())
                index = esc + 1
                continue
            }
            var end = esc + 2
            while (end < text.length && text[end] !in 'A'..'Z' && text[end] !in 'a'..'z') end++
            if (end >= text.length) {
                // 序列被截断（批处理边界切开了一行），剩下的按原文交出去
                emit(text.substring(esc))
                break
            }
            if (text[end] == 'm') {
                val params = text.substring(esc + 2, end)
                for (code in params.split(';')) {
                    when (val value = code.toIntOrNull() ?: if (code.isEmpty()) 0 else continue) {
                        0 -> {
                            color = null
                            bold = false
                        }

                        1 -> bold = true
                        22 -> bold = false
                        39 -> color = null
                        else -> colorOf(value)?.let { color = it }
                    }
                }
            }
            index = end + 1
        }
    }
}

private const val ESC = '\u001B'
