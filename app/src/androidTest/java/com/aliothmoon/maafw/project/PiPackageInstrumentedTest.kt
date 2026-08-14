package com.aliothmoon.maafw.project

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipInputStream

/**
 * assets 读取是平台差异，JVM 测试碰不到 AssetManager
 * 完整解包由应用实际启动验证，这里覆盖 pi.zip 能顺序读到 interface.json
 * 当前包未含 PI（未配置 pi.profile）时跳过
 */
@RunWith(AndroidJUnit4::class)
class PiPackageInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun packageOrSkip(): PiPackage {
        val pkg = AssetPiPackage(context)
        assumeTrue("当前包未含 PI", pkg.openArchive() != null)
        return pkg
    }

    @Test
    fun archiveContainsInterfaceJson() {
        val pkg = packageOrSkip()
        val names = pkg.openArchive()!!.use { raw ->
            ZipInputStream(raw).use { zip ->
                buildList {
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory) add(entry.name.replace('\\', '/').trimStart('/'))
                        zip.closeEntry()
                    }
                }
            }
        }
        assertTrue("pi.zip 应含 interface.json: $names", "interface.json" in names)
    }

    @Test
    fun interfaceJsonReadable() {
        val pkg = packageOrSkip()
        val content = pkg.openArchive()!!.use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/').trimStart('/')
                    if (name == "interface.json") {
                        return@use zip.bufferedReader().readText()
                    }
                    zip.closeEntry()
                }
                ""
            }
        }
        assertTrue(content.contains("interface_version"))
    }
}
