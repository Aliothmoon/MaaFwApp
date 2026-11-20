package com.aliothmoon.maafw.project

/**
 * description 内容形态判定（URL / 文件路径 / 直接文本）：
 * $i18n 形态在进入判定前已由加载期物化
 */

/** http(s) URL：加载期原样保留，UI 层懒加载 */
fun isRemoteUrl(content: String): Boolean =
    content.startsWith("https://") || content.startsWith("http://")

private val DOC_EXTENSION = Regex("""\.(md|txt|json|html|htm)$""", RegexOption.IGNORE_CASE)
private val UPPERCASE_NAME = Regex("""^[A-Z][A-Z0-9_\-]*$""")

internal fun isExplicitProjectPath(content: String): Boolean =
    content.startsWith("./") || content.startsWith("../") ||
        content.startsWith(".\\") || content.startsWith("..\\")

/** 单行文本有路径特征就尝试读取，允许中文、空格和长路径；是否为文件由读取结果决定 */
fun isProjectFileCandidate(content: String): Boolean {
    if (isRemoteUrl(content) || content.any { it == '\r' || it == '\n' || it == '\u0000' }) return false
    if (isExplicitProjectPath(content)) return true
    if (content.contains('<') || content.contains('>')) return false
    return DOC_EXTENSION.containsMatchIn(content) || UPPERCASE_NAME.matches(content) ||
        content.contains('/') || content.contains('\\')
}
