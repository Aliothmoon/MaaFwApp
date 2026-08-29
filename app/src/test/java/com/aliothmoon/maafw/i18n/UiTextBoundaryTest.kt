package com.aliothmoon.maafw.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守住「文案的载体只有 UiText」这条规则
 *
 * 规则本身在 docs/android-ui-contract.md §3.1，但规则写在文档里挡不住任何人——
 * 这里把两条最容易被绕过的落成测试
 */
class UiTextBoundaryTest {

    /**
     * 渲染层之外不许就地把资源解析成 String
     *
     * 禁的是解析动作，不是引用资源——`uiTextOf(R.string.x)` 正是要的写法，
     * 而 `getString(R.string.x)` 会把那一刻的语言冻进值里，之后切语言不再更新
     */
    @Test
    fun `产出层不就地解析字符串资源`() {
        val offenders = sourceFiles()
            .filter { it.toRelative().substringBefore('/') !in RENDER_LAYERS }
            .filterNot { it.toRelative() in EAGER_RESOLVE_ALLOWED }
            .flatMap { file ->
                stripComments(file.readText()).lines().withIndex()
                    .filter { (_, line) -> EAGER_RESOLVE.containsMatchIn(line) }
                    .map { (index, _) -> "${file.toRelative()}:${index + 1}" }
            }

        assertTrue(
            "以下位置就地解析了资源，改成产出 UiText 交给 UI：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * `UiText.Verbatim` 只能由三个语义工厂构造
     *
     * 裸的 `Verbatim("…")` 看不出是「有意不翻译」还是「漏了」，
     * 而 `uiTextFromFramework("运行期间不能修改配置")` 一眼就是错的。
     * 单模块下 internal 挡不住任何人，只能靠这条扫描
     */
    @Test
    fun `Verbatim 不被直接构造`() {
        val offenders = sourceFiles()
            .filterNot { it.toRelative() == UI_TEXT_FILE }
            .flatMap { file ->
                stripComments(file.readText()).lines().withIndex()
                    .filter { (_, line) -> VERBATIM_CONSTRUCTION.containsMatchIn(line) }
                    .map { (index, _) -> "${file.toRelative()}:${index + 1}" }
            }

        assertTrue(
            "直接构造了 UiText.Verbatim，改用 uiTextFromProject / uiTextFromFramework / uiTextFormatted：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun sourceFiles(): List<File> =
        File(SOURCE_ROOT).walkTopDown().filter { it.extension == "kt" }.toList()

    /** Windows 下 File.path 是反斜杠，先归一再截，否则前缀永远对不上 */
    private fun File.toRelative(): String =
        path.replace('\\', '/').substringAfter("$SOURCE_ROOT/")

    /** 与 UiTextArityTest 同样的理由：注释里的示例不该算违规 */
    private fun stripComments(source: String): String =
        source.replace(BLOCK_COMMENT, " ").replace(LINE_COMMENT, "")

    private companion object {
        const val SOURCE_ROOT = "src/main/java/com/aliothmoon/maafw"

        /**
         * 渲染层：就地把资源画出来，不把文案当值传，规则不约束它们
         *
         * overlay 与 ui 平级而不是它的子目录——它是独立于 Activity 的第二个 UI 面，
         * 跨 Activity 存活，挂在 WindowManager 上
         */
        val RENDER_LAYERS = setOf("ui", "overlay")
        const val UI_TEXT_FILE = "i18n/UiText.kt"

        /**
         * `UiText.resolve` 本身就是解析出口
         * 两个 Service 就地渲染通知：它们有 Context，文案不存值也不跨层传；代价是运行中的
         * 通知不跟着切语言更新，通知本身是瞬时的，可以接受
         *
         * [RunEventNotifier] 只就地取 channel 的名字与说明：channel 建出来之后名字由系统留着，
         * 换成 UiText 也不会跟着切语言更新——那得删掉重建 channel，而那会丢掉用户在系统里的调整
         */
        val EAGER_RESOLVE_ALLOWED = setOf(
            "i18n/UiText.kt",
            "service/RunForegroundService.kt",
            "service/UpdateDownloadForegroundService.kt",
            "schedule/ScheduleExecutionService.kt",
            "notification/RunEventNotifier.kt",
        )

        /**
         * 必须带 `R.string` / `R.plurals` 限定
         * 光看方法名会把 `Settings.Secure.getString` 与 `Bundle.getString` 一起抓进来
         */
        val EAGER_RESOLVE = Regex(
            """\b(stringResource|pluralStringResource)\(|\bget(String|QuantityString)\(\s*R\.(string|plurals)\.""",
        )
        val VERBATIM_CONSTRUCTION = Regex("""UiText\.Verbatim\(""")
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//[^\n]*""")
    }
}
