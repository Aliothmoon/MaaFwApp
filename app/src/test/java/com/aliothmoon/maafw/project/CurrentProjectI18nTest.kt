package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.DiagnosticMessage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 构建期同步进来的 PI 的发布契约：声明了 languages 就必须覆盖全部 $key
 * 契约只约束「声明了就要完整」，不要求每个 PI 都做 i18n；
 * 未配置 pi.sourceDir 或该 PI 不做 i18n 时跳过，外壳不绑定任何具体项目
 */
@OptIn(ExperimentalSerializationApi::class)
class CurrentProjectI18nTest {

    private val piJson = Json {
        allowComments = true
        allowTrailingComma = true
    }

    /** syncPiAssets 的落点；单元测试工作目录是 app/ */
    private val piRoot = File("build/generated/piAssets/pi")

    private fun sourceOrSkip(): ProjectSource {
        assumeTrue("未同步 PI（未配置 pi.sourceDir）", File(piRoot, "interface.json").isFile)
        return FileProjectSource(piRoot)
    }

    @Test
    fun `打包 PI 的每种声明语言覆盖全部 i18n 引用`() {
        val source = sourceOrSkip()
        val projectInterface = PiParser.parseInterface("interface.json", source.read("interface.json"))
        assertTrue(
            "合法的 language path 不应产生诊断: ${projectInterface.diagnostics}",
            projectInterface.diagnostics.none { it.message is DiagnosticMessage.LanguagePathInvalid },
        )
        assumeTrue("该 PI 未声明 languages", projectInterface.languages.isNotEmpty())

        val loadedFiles = listOf("interface.json") + projectInterface.imports
        val references = loadedFiles
            .flatMap { path -> collectI18nReferences(piJson.parseToJsonElement(source.read(path))) }
            .toSortedSet()

        projectInterface.languages.forEach { (language, path) ->
            val diagnostics = mutableListOf<Diagnostic>()
            val translations = PiParser.parseTranslations(path, source.read(normalizeProjectPath(path))) {
                diagnostics += it
            }
            assertTrue("$language 翻译文件应可解析: $diagnostics", diagnostics.isEmpty())
            val missing = references - translations.keys
            assertTrue("$language 缺少 i18n key: $missing", missing.isEmpty())
        }
    }

    @Test
    fun `打包 PI 的各语言加载时无 i18n 诊断`() {
        val source = sourceOrSkip()
        val projectInterface = PiParser.parseInterface("interface.json", source.read("interface.json"))
        assumeTrue("该 PI 未声明 languages", projectInterface.languages.isNotEmpty())

        projectInterface.languages.keys.forEach { language ->
            val ready = ProjectLoader(source) { language }.load() as ProjectLoadResult.Ready
            val i18nDiagnostics = ready.diagnostics.filter {
                when (it.message) {
                    is DiagnosticMessage.LanguagePathInvalid,
                    is DiagnosticMessage.TranslationReadFailed,
                    is DiagnosticMessage.TranslationJsonParseFailed,
                    is DiagnosticMessage.DescriptionReadFailed,
                    -> true

                    else -> false
                }
            }
            assertTrue("$language 不应产生 i18n 诊断: $i18nDiagnostics", i18nDiagnostics.isEmpty())
        }
    }

    private fun collectI18nReferences(element: JsonElement): List<String> = when (element) {
        is JsonObject -> element.values.flatMap(::collectI18nReferences)
        is JsonArray -> element.flatMap(::collectI18nReferences)
        is JsonPrimitive -> element.contentOrNull
            ?.takeIf { it.startsWith("$") && it.length > 1 }
            ?.let { listOf(it.substring(1)) }
            .orEmpty()
    }
}
