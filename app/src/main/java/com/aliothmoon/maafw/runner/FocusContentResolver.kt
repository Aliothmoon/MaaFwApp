package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.project.PiInstaller
import com.aliothmoon.maafw.project.isFilePath
import com.aliothmoon.maafw.project.normalizeProjectPath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * focus 模板正文里的 `{image}` 占位符
 *
 * **不是 PI 协议的东西**：`3.3-ProjectInterfaceV2协议.md` 与 `2.3-回调协议.md` 都没有它，
 * 任何一种 `details_json` 也不带 `image` 字段。这是照桌面端 MXU 的同名扩展做的，
 * PI 里写了它才有意义，换个客户端不保证认
 */
const val FOCUS_IMAGE_PLACEHOLDER = "{image}"

/**
 * 补完 focus 模板正文里需要 IO 的那两种形态
 *
 * 抽成接口是为了让 [com.aliothmoon.maafw.session.SessionViewModel] 可测：真实实现要拿
 * 特权进程与 PI 解包目录，测试给个原样返回的替身即可。**不挂生产默认值**，由 Koin 显式注入
 */
interface FocusContentResolver {
    /** 不需要 IO 时必须原样返回，调用方靠 [focusContentNeedsIo] 提前判断，不重复判 */
    suspend fun resolve(content: String): String
}

/**
 * `$i18n` 查表要先做完再判这个：查出来的结果才可能是文件路径
 *
 * 判定放在调用方而不是 resolver 里，是为了让不需要 IO 的绝大多数模板走同步路径，
 * 不为它们各起一个协程
 */
fun focusContentNeedsIo(content: String): Boolean =
    content.contains(FOCUS_IMAGE_PLACEHOLDER) || isFilePath(content)

/**
 * 生产实现：`{image}` 找特权进程要缓存帧，文件路径形态读 PI 解包目录
 *
 * 截图落盘而不回传字节——理由见 `RemoteService.saveCachedImage`。落点在 [imageDir] 下按
 * 序号轮转：日志里同时挂着好几张图时，新的不该把旧的盖掉，但也不能无限堆
 */
class PrivilegedFocusContentResolver(
    private val installer: PiInstaller,
    /** 截图落点；须是特权进程写得进、app 也读得到的目录 */
    private val imageDir: () -> File,
    private val servicePort: PrivilegedServicePort,
    private val ioDispatcher: CoroutineDispatcher,
) : FocusContentResolver {

    private val slot = AtomicInteger(0)

    override suspend fun resolve(content: String): String = withContext(ioDispatcher) {
        if (content.contains(FOCUS_IMAGE_PLACEHOLDER)) {
            return@withContext content.replace(FOCUS_IMAGE_PLACEHOLDER, captureImageUri())
        }
        readProjectFile(content)
    }

    /** 拿不到就替换成空串，与桌面端 MXU 一致：留着占位符更难看 */
    private suspend fun captureImageUri(): String {
        val target = File(imageDir(), "focus_${slot.getAndIncrement() % IMAGE_SLOTS}.png")
        val saved = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            // 用 serviceOrNull 而不是 useService：一条日志不值得为它发起重连与授权请求
            runCatching { servicePort.serviceOrNull()?.saveCachedImage(target.absolutePath) }
                .onFailure { Timber.w(it, "focus {image} 取缓存帧失败") }
                .getOrNull()
        }
        if (saved != true) return ""
        // Markwon 的 FileSchemeHandler 认这个 scheme；带上 mtime 防它按 URL 命中旧图的缓存
        return "file://${target.absolutePath}?t=${target.lastModified()}"
    }

    /** 读不到回落原文：PI 作者可能本来就想显示这行字 */
    private fun readProjectFile(content: String): String = runCatching {
        val root = installer.ensureInstalled()
        val file = File(root, normalizeProjectPath(content))
        if (file.isFile) file.readText() else content
    }.getOrElse {
        Timber.w(it, "focus 正文按文件路径读取失败：%s", content)
        content
    }

    private companion object {
        const val IMAGE_SLOTS = 8

        /** 缓存帧是现成的，慢只可能是 binder 排队；等太久不如不显示这张图 */
        const val CAPTURE_TIMEOUT_MS = 3_000L
    }
}
