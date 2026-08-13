package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.i18n.AppLocales
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 打包 PI 的加载契约：Error 级诊断说明声明本身有问题（引用不存在的 option、重名、缺必填字段），
 * 这类错误在装机前就该拦下
 *
 * 与 [CurrentProjectI18nTest] 分开：那份只管翻译完整性
 */
class CurrentProjectLoadTest {

    /** locale 显式固定，不依赖运行机默认语言；ProjectLoader 内部直读 [AppLocales] */
    @Before
    fun fixLocale() {
        mockkObject(AppLocales)
        every { AppLocales.currentProjectTag() } returns "zh-CN"
    }

    @After
    fun releaseLocale() {
        unmockkObject(AppLocales)
    }

    @Test
    fun `打包 PI 加载不产生 Error 诊断`() {
        val result = ProjectLoader(syncedPiOrSkip()).load()
        assertTrue("加载应成功: $result", result is ProjectLoadResult.Ready)
        val errors = (result as ProjectLoadResult.Ready).diagnostics
            .filter { it.severity == DiagnosticSeverity.Error }
        assertTrue("不应有 Error 诊断: $errors", errors.isEmpty())
    }
}
