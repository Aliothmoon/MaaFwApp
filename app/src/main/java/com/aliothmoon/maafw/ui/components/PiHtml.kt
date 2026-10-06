package com.aliothmoon.maafw.ui.components

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/** 相对路径资源（`![](a.png)`、`<img src="a.png">`）挂在这个虚拟源下，由 WebView 拦截后从 PI 解包目录供给 */
internal const val PI_HTML_BASE_URL = "https://pi.maafw.local/"

private val extensions = listOf(TablesExtension.create(), StrikethroughExtension.create())

private val parser: Parser = Parser.builder()
    .extensions(extensions)
    .enabledBlockTypes(PI_BLOCK_TYPES)
    .build()

/** 对齐 MXU 的 marked `breaks: true`：段内单个换行就是换行 */
private val renderer: HtmlRenderer = HtmlRenderer.builder()
    .extensions(extensions)
    .softbreak("<br />\n")
    .build()

/**
 * PI 正文（Markdown + 原样 HTML）转 HTML 片段，多段之间一条分隔线
 *
 * HTML 块原样透传，CSS 交给 WebView：公告常用定位、flex、渐变这类 Markwon 画不出的版式
 */
internal fun piBodiesToHtml(bodies: List<String>): String =
    bodies.joinToString("\n<hr class=\"maa-split\" />\n") { renderer.render(parser.parse(it)) }

/** 主题色注入 CSS 变量；`.rounded` 是 MXU 正文里常用的 Tailwind 类 */
internal fun piHtmlDocument(body: String, colors: PiHtmlColors): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
:root {
  color-scheme: ${if (colors.dark) "dark" else "light"};
  --text: ${colors.text};
  --muted: ${colors.muted};
  --link: ${colors.link};
  --line: ${colors.line};
  --code: ${colors.code};
}
html, body { margin: 0; padding: 0; background: transparent; }
::-webkit-scrollbar { display: none; }
body {
  color: var(--text);
  font-family: sans-serif;
  font-size: 0.875rem;
  line-height: 1.6;
  overflow-wrap: anywhere;
  padding-bottom: 1rem;
}
a { color: var(--link); }
img { max-width: 100%; height: auto; }
.rounded { border-radius: 0.25rem; }
h1, h2, h3, h4 { line-height: 1.35; margin: 1.2em 0 0.5em; }
h1 { font-size: 1.4em; }
h2 { font-size: 1.25em; }
h3 { font-size: 1.1em; }
h4 { font-size: 1em; }
body > :first-child { margin-top: 0; }
p, ul, ol, blockquote, pre, table { margin: 0.6em 0; }
ul, ol { padding-left: 1.4em; }
li { margin: 0.2em 0; }
blockquote { margin-left: 0; padding-left: 0.8em; border-left: 3px solid var(--line); color: var(--muted); }
code { background: var(--code); border-radius: 0.25rem; padding: 0.1em 0.3em; font-size: 0.9em; }
pre { background: var(--code); border-radius: 0.375rem; padding: 0.6em 0.8em; overflow-x: auto; }
pre code { background: none; padding: 0; }
table { border-collapse: collapse; display: block; overflow-x: auto; }
th, td { border: 1px solid var(--line); padding: 0.25em 0.5em; }
hr { border: none; border-top: 1px solid var(--line); margin: 1em 0; }
hr.maa-split { margin: 1.5em 0; }
</style>
</head>
<body>
$body
</body>
</html>
""".trimIndent()

/** CSS 颜色值（`rgba(...)`），由调用方从 Material 主题换算 */
internal data class PiHtmlColors(
    val dark: Boolean,
    val text: String,
    val muted: String,
    val link: String,
    val line: String,
    val code: String,
)
