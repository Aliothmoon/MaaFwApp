package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.i18n.AppLocales
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject

/** 加载语言取自进程级的 [AppLocales]，测试里替换它的返回值而不是给 loader 加参数 */
internal fun loadWithLocale(tag: String, source: ProjectSource): ProjectLoadResult {
    mockkObject(AppLocales)
    every { AppLocales.currentProjectTag() } returns tag
    return try {
        ProjectLoader(source).load()
    } finally {
        unmockkObject(AppLocales)
    }
}
