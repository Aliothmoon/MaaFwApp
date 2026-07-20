package com.aliothmoon.maafw.project

/**
 * description 内容形态判定（对齐 MXU contentResolver 的启发式）：
 * URL / 文件路径 / 直接文本。$i18n 形态在进入判定前已由加载期物化。
 */

/** http(s) URL：加载期原样保留，UI 层懒加载。 */
fun isRemoteUrl(content: String): Boolean =
    content.startsWith("https://") || content.startsWith("http://")

private val DOC_EXTENSION = Regex("""\.(md|txt|json|html|htm)$""", RegexOption.IGNORE_CASE)
private val SIMPLE_NAME_CHARS = Regex("""^[A-Za-z0-9_\-./\\]+$""")
private val DIGITS_ONLY = Regex("""^\d+$""")
private val UPPERCASE_NAME = Regex("""^[A-Z][A-Z0-9_\-]*$""")

/**
 * 文件路径特征：./ 或 ../ 前缀；常见文档扩展名；
 * 或不含空白/HTML 标签的简单文件名（全大写如 LICENSE，或带路径分隔符）。
 */
fun isFilePath(content: String): Boolean {
    if (isRemoteUrl(content)) return false
    if (content.startsWith("./") || content.startsWith("../")) return true
    if (DOC_EXTENSION.containsMatchIn(content)) return true

    val isSimpleName = SIMPLE_NAME_CHARS.matches(content) &&
        content.length in 1..100 &&
        !DIGITS_ONLY.matches(content)

    if (isSimpleName && UPPERCASE_NAME.matches(content)) return true
    if (isSimpleName && (content.contains('/') || content.contains('\\'))) return true
    return false
}
