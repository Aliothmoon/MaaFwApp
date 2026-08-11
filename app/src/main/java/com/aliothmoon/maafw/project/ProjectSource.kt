package com.aliothmoon.maafw.project

import java.io.File

/**
 * PI 文件读取边界：Loader 只依赖它，便于 JVM 测试注入内存实现
 * 路径一律使用相对于项目根的 `/` 分隔相对路径
 */
interface ProjectSource {
    /** 项目根名称，用作 ProjectDefinition.name 的兜底 */
    val projectName: String

    /** 列出目录直接子项（文件与子目录名）；目录不存在返回空列表 */
    fun list(path: String): List<String>

    fun read(path: String): String
}

/** 构建期 syncPiAssets 的固定落点；外壳不认具体 PI 项目，只认这个位置 */
const val PI_ASSET_ROOT = "pi"

/** 从文件系统读 PI；native 接入后与 MaaFramework 共用同一份解包目录 */
class DirectoryProjectSource(private val root: File) : ProjectSource {

    override val projectName: String = root.name

    override fun list(path: String): List<String> =
        File(root, path).listFiles()?.map { it.name }?.sorted().orEmpty()

    override fun read(path: String): String = File(root, path).readText(Charsets.UTF_8)
}

/**
 * 委托给已解包的目录，自身不解包
 * 解包归 [PiInstallCoordinator]；这里再兜一次的话，失败会以「interface.json 读取失败」的面目出现
 */
class InstalledProjectSource(private val installer: PiInstaller) : ProjectSource {

    private val delegate: ProjectSource by lazy { DirectoryProjectSource(installer.installedDir()) }

    // 解包目录名是外壳定的固定值，不带项目信息；PI 未声明 name 时回落它作中性兜底
    override val projectName: String = PI_ASSET_ROOT

    override fun list(path: String): List<String> = delegate.list(path)

    override fun read(path: String): String = delegate.read(path)
}
