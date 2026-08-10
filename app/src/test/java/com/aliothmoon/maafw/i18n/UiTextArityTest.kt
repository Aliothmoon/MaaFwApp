package com.aliothmoon.maafw.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 补回统一到 UiText 之后失效的格式串检查
 *
 * `resolve` 里是 `context.getString(resId, *args)`——resId 是变量、args 是展开的数组，
 * lint 追不进去。参数少了不会在构建期报，只在那条文案真的被渲染时抛
 * `MissingFormatArgumentException`，而诊断这类文案本来就是低频路径，可能几个月不复现
 *
 * 静态扫源码补上，覆盖面比原来的 lint 更宽：产出层的 `DiagnosticMessages` 之类一并检查，
 * 而 lint 只看得懂 Compose 里的字面量调用
 */
class UiTextArityTest {

    @Test
    fun `每个 uiTextOf 调用点的参数个数与占位符数量一致`() {
        check(CALL_STRING, placeholderCounts("string"), "uiTextOf")
    }

    @Test
    fun `每个 uiTextPlural 调用点的参数个数与占位符数量一致`() {
        // 首个参数是选形数，不填占位符，比对时要去掉
        check(CALL_PLURAL, placeholderCounts("plurals"), "uiTextPlural", leadingNonFormatArgs = 1)
    }

    private fun check(
        pattern: Regex,
        expected: Map<String, Int>,
        label: String,
        leadingNonFormatArgs: Int = 0,
    ) {
        val problems = mutableListOf<String>()
        var skipped = 0
        var checked = 0

        sourceFiles().forEach { file ->
            // 注释里也有示例调用（KDoc 里就写着示范用法），先剥掉
            val text = stripComments(file.readText())
            pattern.findAll(text).forEach { match ->
                val name = match.groupValues[1]
                val argText = balancedArgs(text.substring(match.range.last + 1))
                if (argText == null) {
                    skipped++
                    return@forEach
                }
                val line = text.take(match.range.first).count { it == '\n' } + 1
                val want = expected[name]
                if (want == null) {
                    problems += "${file.name}:$line $label 引用了不存在的资源 $name"
                    return@forEach
                }
                checked++
                val actual = countArgsAfterResource(argText) - leadingNonFormatArgs
                if (actual != want) {
                    problems += "${file.name}:$line $name 需要 $want 个参数，实际给了 $actual 个"
                }
            }
        }

        // 一处都没扫到说明正则已失配，否则本测试会静默通过
        assertTrue("$label 一个调用点都没扫到，正则可能已失配", checked > 0)
        // 参数含嵌套逗号导致解析不了的调用点不静默吞掉
        println("$label: 检查 $checked 处，跳过 $skipped 处")
        assertTrue(problems.joinToString("\n", prefix = "\n"), problems.isEmpty())
    }

    private fun sourceFiles(): List<File> =
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    /** 取 `%N$s` 里最大的 N；没有占位符即 0 */
    private fun placeholderCounts(tag: String): Map<String, Int> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
        val nodes = document.getElementsByTagName(tag)
        return buildMap {
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as Element
                val max = PLACEHOLDER.findAll(element.textContent)
                    .maxOfOrNull { it.groupValues[1].toInt() } ?: 0
                put(element.getAttribute("name"), max)
            }
        }
    }

    /**
     * 行注释与块注释换成等长空白，字符串字面量原样保留
     * 换而不是删，行号才不会错位——报错要能直接定位
     */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        var inString = false
        while (i < source.length) {
            val c = source[i]
            if (inString) {
                out.append(c)
                if (c == BACKSLASH && i + 1 < source.length) {
                    out.append(source[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') inString = false
                i++
                continue
            }
            when {
                c == '"' -> {
                    inString = true
                    out.append(c)
                    i++
                }

                c == '/' && source.getOrNull(i + 1) == '/' -> {
                    while (i < source.length && source[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }

                c == '/' && source.getOrNull(i + 1) == '*' -> {
                    while (i < source.length &&
                        !(source[i] == '*' && source.getOrNull(i + 1) == '/')
                    ) {
                        out.append(if (source[i] == '\n') '\n' else ' ')
                        i++
                    }
                    out.append("  ")
                    i += 2
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** 从 `(` 之后数括号，返回参数区原文；括号不平衡返回 null */
    private fun balancedArgs(tail: String): String? {
        var depth = 1
        tail.forEachIndexed { index, c ->
            when (c) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return tail.take(index)
                }
            }
        }
        return null
    }

    /**
     * 资源 id 已被正则吃掉，[args] 是它之后那一段（以逗号开头），顶层逗号数即剩余参数数
     *
     * 嵌套括号与 lambda 花括号里的逗号不算；字符串字面量整段跳过，
     * 免得带逗号的文案参数被多数一个
     */
    private fun countArgsAfterResource(args: String): Int {
        var depth = 0
        var count = 0
        var inString = false
        var i = 0
        while (i < args.length) {
            val c = args[i]
            if (inString) {
                if (c == BACKSLASH) {
                    i += 2
                    continue
                }
                if (c == '"') inString = false
                i++
                continue
            }
            when (c) {
                '"' -> inString = true
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) count++
            }
            i++
        }
        // 结尾逗号是 Kotlin 的书写习惯，不代表多一个参数
        return if (args.trimEnd().endsWith(',')) count - 1 else count
    }

    private companion object {
        const val BACKSLASH = '\\'
        val CALL_STRING = Regex("""uiTextOf\(\s*R\.string\.(\w+)""")
        val CALL_PLURAL = Regex("""uiTextPlural\(\s*R\.plurals\.(\w+)""")
        val PLACEHOLDER = Regex("""%(\d+)\$[sd]""")
    }
}
