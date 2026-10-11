package com.aliothmoon.maafw.project

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.InvalidPathException

/**
 * PI 文件读取边界：Loader 只依赖它，便于 JVM 测试注入内存实现
 * 路径一律使用相对于项目根的 `/` 分隔相对路径
 */
interface ProjectSource {
    /** 项目根名称，用作 ProjectDefinition.name 的兜底 */
    val projectName: String

    /** 列出目录直接子项（文件与子目录名）；目录不存在返回空列表 */
    fun list(path: String): List<String>

    /** 必需配置读取失败由 Loader 转成加载错误；可选正文使用 [tryReadText] */
    fun read(path: String): String

    /** 成功正文（可为空串）/ success(null) 未命中文件 / failure 文件访问失败 */
    fun tryReadText(path: String): Result<String?> = catchProjectReadFailure { read(path) }
}

/** 构建期 syncPiAssets 的固定落点；外壳不认具体 PI 项目，只认这个位置 */
const val PI_ASSET_ROOT = "pi"

/** 从文件系统读 PI；native 接入后与 MaaFramework 共用同一份解包目录 */
class DirectoryProjectSource(private val root: File) : ProjectSource {

    override val projectName: String = root.name

    override fun list(path: String): List<String> =
        File(root, path).listFiles()?.map { it.name }?.sorted().orEmpty()

    override fun read(path: String): String = tryReadText(path).getOrThrow()
        ?: throw FileNotFoundException("Not a project file: $path")

    override fun tryReadText(path: String): Result<String?> = catchProjectReadFailure {
        resolveProjectFile(root, path)?.readText(Charsets.UTF_8)
    }
}

/** 无效、越界或非文件路径返回 null；文件访问错误交给读取边界保留原因 */
private fun resolveProjectFile(root: File, path: String): File? {
    val relative = normalizeProjectPath(path.replace('\\', '/'))
    if (relative.contains('\u0000') || relative.startsWith('/') || WINDOWS_DRIVE.containsMatchIn(relative)) {
        return null
    }
    val base = root.canonicalFile
    val file = File(base, relative).canonicalFile
    return try {
        file.takeIf { it.toPath().startsWith(base.toPath()) && it.isFile }
    } catch (_: InvalidPathException) {
        null
    }
}

private val WINDOWS_DRIVE = Regex("""^[A-Za-z]:""")

/** 只处理可预期的文件访问和安装状态错误，不吞取消信号、程序错误或 JVM Error */
private inline fun <T> catchProjectReadFailure(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: IOException) {
    Result.failure(e)
} catch (e: SecurityException) {
    Result.failure(e)
} catch (e: PiNotInstalledException) {
    Result.failure(e)
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

    override fun tryReadText(path: String): Result<String?> {
        val source = catchProjectReadFailure { delegate }.getOrElse { return Result.failure(it) }
        return source.tryReadText(path)
    }
}
