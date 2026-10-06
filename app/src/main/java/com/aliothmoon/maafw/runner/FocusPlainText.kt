package com.aliothmoon.maafw.runner

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser

/**
 * focus 正文去掉 Markdown 与 HTML 记号后的纯文本
 *
 * 正文按协议是 Markdown + HTML 子集，运行日志交给 `MaaMarkdown` 渲染；系统通知、snackbar
 * 这类落点只认纯文本，原样塞进去标签就直接露给用户
 *
 * 一行一块、不留空行；图片只留 alt，链接只留文字
 */
fun focusPlainText(content: String): String {
    val visitor = PlainTextVisitor()
    PARSER.parse(content).accept(visitor)
    return visitor.out.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString("\n")
}

private val PARSER: Parser = Parser.builder()
    .extensions(listOf(StrikethroughExtension.create()))
    .build()

private class PlainTextVisitor : AbstractVisitor() {
    val out = StringBuilder()

    override fun visit(text: Text) {
        out.append(text.literal)
    }

    override fun visit(code: Code) {
        out.append(code.literal)
    }

    // 软换行按 Markdown 是空格；这里留成换行，没带记号的正文进出不变
    override fun visit(softLineBreak: SoftLineBreak) {
        out.append('\n')
    }

    override fun visit(hardLineBreak: HardLineBreak) {
        out.append('\n')
    }

    /** 行内 HTML 一个标签一个节点，标签之间的文字是普通 [Text]，所以丢掉节点本身就够了 */
    override fun visit(htmlInline: HtmlInline) {
        if (LINE_BREAK_TAG.matches(htmlInline.literal)) out.append('\n')
    }

    /** 块级 HTML（`<div>` / `<p>` 起头）整块是原文，commonmark 不拆，得自己剥标签 */
    override fun visit(htmlBlock: HtmlBlock) = block { out.append(stripHtml(htmlBlock.literal)) }

    override fun visit(fencedCodeBlock: FencedCodeBlock) = block { out.append(fencedCodeBlock.literal) }

    override fun visit(indentedCodeBlock: IndentedCodeBlock) = block { out.append(indentedCodeBlock.literal) }

    override fun visit(paragraph: Paragraph) = block { visitChildren(paragraph) }

    override fun visit(heading: Heading) = block { visitChildren(heading) }

    private inline fun block(body: () -> Unit) {
        if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
        body()
    }
}

private val LINE_BREAK_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val BLOCK_END_TAG = Regex("""</(?:p|div|li|tr|h[1-6])\s*>""", RegexOption.IGNORE_CASE)

/** 标签名必须字母起头：正文里的 `HP < 50` 不是标签 */
private val HTML_TAG = Regex("""<!--.*?-->|</?[A-Za-z][^>]*>""", RegexOption.DOT_MATCHES_ALL)
private val HTML_ENTITY = Regex("""&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z]+);""")

private val NAMED_ENTITIES = mapOf(
    "lt" to "<",
    "gt" to ">",
    "amp" to "&",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
)

private fun stripHtml(html: String): String =
    html.replace(LINE_BREAK_TAG, "\n")
        .replace(BLOCK_END_TAG, "\n")
        .replace(HTML_TAG, "")
        .replace(HTML_ENTITY) { decodeEntity(it.groupValues[1]) ?: it.value }

/** 认不出的实体原样留着，好过吞掉一段字 */
private fun decodeEntity(name: String): String? {
    if (!name.startsWith("#")) return NAMED_ENTITIES[name.lowercase()]
    val codePoint = if (name[1] == 'x' || name[1] == 'X') {
        name.substring(2).toIntOrNull(16)
    } else {
        name.substring(1).toIntOrNull()
    }
    return codePoint?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) }
}
