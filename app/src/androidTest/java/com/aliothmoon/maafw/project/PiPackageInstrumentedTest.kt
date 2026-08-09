package com.aliothmoon.maafw.project

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * assets 读取是平台差异，JVM 测试碰不到 AssetManager
 * 完整解包由应用实际启动验证，这里只覆盖 AssetPiPackage 的读取语义
 * 当前包未含 PI（未配置 pi.sourceDir）时跳过
 */
@RunWith(AndroidJUnit4::class)
class PiPackageInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun packageOrSkip(): PiPackage {
        val pkg = AssetPiPackage(context, PI_ASSET_ROOT)
        assumeTrue("当前包未含 PI", pkg.list("").contains("interface.json"))
        return pkg
    }

    @Test
    fun listDistinguishesDirectoryFromFile() {
        val pkg = packageOrSkip()
        assertTrue("PI 根应能列出子项", pkg.list("").isNotEmpty())
        assertTrue("文件的 list 应为空，解包据此判定文件", pkg.list("interface.json").isEmpty())
    }

    @Test
    fun interfaceJsonReadable() {
        val pkg = packageOrSkip()
        val content = pkg.open("interface.json").bufferedReader().use { it.readText() }
        assertTrue(content.contains("interface_version"))
    }

    /**
     * PI 常带几十 MB 的模型，压缩存放的大 asset 能否流式读出，JVM 侧验证不到
     * available() 只是估计，不拿它做相等断言；这里验证的是能读到底且不抛异常
     */
    @Test
    fun largestAssetStreamsFully() {
        val pkg = packageOrSkip()
        val largest = walk(pkg, "").maxByOrNull { it.second } ?: return
        val read = pkg.open(largest.first).use { stream ->
            var total = 0L
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n <= 0) break
                total += n
            }
            total
        }
        assertTrue("最大 asset 应能完整读出: ${largest.first}", read > 0)
    }

    /** 返回 (路径, 大小估计)，仅用于挑出最大的那个文件 */
    private fun walk(pkg: PiPackage, path: String): List<Pair<String, Long>> {
        val children = pkg.list(path)
        if (children.isEmpty()) {
            val size = try {
                pkg.open(path).use { it.available().toLong() }
            } catch (e: Exception) {
                return emptyList()
            }
            return listOf(path to size)
        }
        return children.flatMap { walk(pkg, if (path.isEmpty()) it else "$path/$it") }
    }
}
