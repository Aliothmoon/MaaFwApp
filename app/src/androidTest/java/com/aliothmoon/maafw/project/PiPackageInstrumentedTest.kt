package com.aliothmoon.maafw.project

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * assets 读取是平台差异，JVM 测试碰不到 AssetManager
 * 完整解包由应用实际启动验证，这里只覆盖 AssetPiPackage 的读取语义与构建期清单的一致性
 * 当前包未含 PI（未配置 pi.sourceDir）时跳过
 */
@RunWith(AndroidJUnit4::class)
class PiPackageInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun packageOrSkip(): PiPackage {
        val pkg = AssetPiPackage(context, PI_ASSET_ROOT)
        val manifest = runCatching { pkg.manifest() }.getOrDefault(emptyList())
        assumeTrue("当前包未含 PI", manifest.contains("interface.json"))
        return pkg
    }

    /** 清单是解包的唯一依据，条目对不上会静默少解文件 */
    @Test
    fun manifestEntriesAllOpenable() {
        val pkg = packageOrSkip()
        val entries = pkg.manifest()
        assertTrue("清单不应为空", entries.isNotEmpty())
        val unopenable = entries.filter { entry ->
            runCatching { pkg.open(entry).use { it.read() } }.isFailure
        }
        assertTrue("清单里有打不开的条目: ${unopenable.take(5)}", unopenable.isEmpty())
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
        val largest = pkg.manifest()
            .maxByOrNull { entry ->
                runCatching { pkg.open(entry).use { it.available().toLong() } }.getOrDefault(0L)
            } ?: return
        val read = pkg.open(largest).use { stream ->
            var total = 0L
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n <= 0) break
                total += n
            }
            total
        }
        assertTrue("最大 asset 应能完整读出: $largest", read > 0)
    }
}
