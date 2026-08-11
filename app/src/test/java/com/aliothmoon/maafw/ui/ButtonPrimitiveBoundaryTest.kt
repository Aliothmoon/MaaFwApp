package com.aliothmoon.maafw.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守住「按钮一律走 `MaaButton` / `MaaOutlinedButton`」
 *
 * 规则本身是 CLAUDE.md 的 UI 红线，但规则写在文档里挡不住任何人。这一条尤其容易被绕过：
 * M3 的 `Button` 默认形状取自它自己的 token（`CornerFull` → 胶囊），**不经过主题的 `Shapes`**，
 * 所以直接用 M3 的写出来能编译、能跑、只是圆角跟全屏别的地方对不上，评审时也看不出来
 *
 * 只管 `Button` 与 `OutlinedButton` 两种：`TextButton` / `IconButton` 没有实心容器，
 * 圆角在视觉上不成立；`FilledTonalButton` 目前只在空状态用一处，还没到要包的份上
 */
class ButtonPrimitiveBoundaryTest {

    @Test
    fun `界面层不直接使用 M3 的 Button`() {
        val offenders = File(SOURCE_ROOT).walkTopDown()
            .filter { it.extension == "kt" }
            .filterNot { it.toRelative() == PRIMITIVE_FILE }
            .flatMap { file ->
                stripComments(file.readText()).lines().withIndex()
                    .filter { (_, line) -> BARE_BUTTON.containsMatchIn(line) }
                    .map { (index, _) -> "${file.toRelative()}:${index + 1}" }
            }
            .toList()

        assertTrue(
            "以下位置直接用了 M3 的按钮，改用 MaaButton / MaaOutlinedButton：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** Windows 下 File.path 是反斜杠，先归一再截，否则前缀永远对不上 */
    private fun File.toRelative(): String =
        path.replace('\\', '/').substringAfter("$SOURCE_ROOT/")

    private fun stripComments(source: String): String =
        source.replace(BLOCK_COMMENT, " ").replace(LINE_COMMENT, "")

    private companion object {
        const val SOURCE_ROOT = "src/main/java/com/aliothmoon/maafw"

        /** wrapper 自己当然要调 M3 的那两个 */
        const val PRIMITIVE_FILE = "ui/components/MaaComponents.kt"

        /**
         * 前面不能是标识符字符或点：否则 `MaaButton(` `IconButton(` `ButtonDefaults` 全会被抓进来。
         * import 行不必单独排除——它没有紧跟的左括号
         */
        val BARE_BUTTON = Regex("""(?<![\w.])(Outlined)?Button\(""")
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//[^\n]*""")
    }
}
