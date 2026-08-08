package com.aliothmoon.maafw.project

import android.content.Context

/**
 * PI 文件读取 seam：Loader 只依赖它，便于 JVM 测试注入内存实现
 * 路径一律使用相对于项目根的 `/` 分隔相对路径
 */
interface ProjectSource {
    /** 项目根名称，用作 ProjectDefinition.name 的兜底 */
    val projectName: String

    /** 列出目录直接子项（文件与子目录名）；目录不存在返回空列表 */
    fun list(path: String): List<String>

    /** 读取文本文件内容 */
    fun read(path: String): String
}

const val M9A_ASSET_ROOT = "PI/M9A"

/** 从 APK assets 读取内置 PI */
class AssetProjectSource(
    context: Context,
    private val root: String,
) : ProjectSource {

    private val assets = context.applicationContext.assets

    override val projectName: String = root.substringAfterLast('/')

    override fun list(path: String): List<String> =
        assets.list(assetPath(path))?.toList().orEmpty()

    override fun read(path: String): String =
        assets.open(assetPath(path)).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun assetPath(path: String): String =
        if (path.isEmpty()) root else "$root/$path"
}
