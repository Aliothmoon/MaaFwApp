package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.DiagnosticMessage
import com.aliothmoon.maafw.domain.casesOrEmpty
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 当前打包 PI 的发布契约：所有实际加载的 $key 必须在每份声明语言中有译文。 */
@OptIn(ExperimentalSerializationApi::class)
class CurrentProjectI18nTest {

    private val piJson = Json {
        allowComments = true
        allowTrailingComma = true
    }

    @Test
    fun `当前打包 PI 的每种语言覆盖全部 i18n 引用`() {
        val source = FileProjectSource(File("src/main/assets", M9A_ASSET_ROOT))
        val interfaceContent = source.read("interface.json")
        val projectInterface = PiParser.parseInterface("interface.json", interfaceContent)
        assertTrue("当前打包 PI 应声明 languages", projectInterface.languages.isNotEmpty())
        assertTrue(
            "合法的 language path 不应产生诊断: ${projectInterface.diagnostics}",
            projectInterface.diagnostics.none { it.message is DiagnosticMessage.LanguagePathInvalid },
        )

        val loadedFiles = listOf("interface.json") + projectInterface.imports
        val references = loadedFiles
            .flatMap { path -> collectI18nReferences(piJson.parseToJsonElement(source.read(path))) }
            .toSortedSet()

        projectInterface.languages.forEach { (language, path) ->
            val diagnostics = mutableListOf<com.aliothmoon.maafw.domain.Diagnostic>()
            val translations = PiParser.parseTranslations(path, source.read(normalizeProjectPath(path))) {
                diagnostics += it
            }
            assertTrue("$language 翻译文件应可解析: $diagnostics", diagnostics.isEmpty())
            val missing = references - translations.keys
            assertTrue("$language 缺少 i18n key: $missing", missing.isEmpty())
        }
    }

    @Test
    fun `当前打包 PI 的各语言加载时无 i18n 诊断`() {
        val source = FileProjectSource(File("src/main/assets", M9A_ASSET_ROOT))
        val projectInterface = PiParser.parseInterface("interface.json", source.read("interface.json"))

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

    @Test
    fun `英文 Sell Products 不暴露中文内部 case 名`() {
        val source = FileProjectSource(File("src/main/assets", M9A_ASSET_ROOT))
        val ready = ProjectLoader(source) { "en-US" }.load() as ProjectLoadResult.Ready
        val labelsByName = ready.definition.options.values
            .flatMap { it.casesOrEmpty() }
            .filter { it.name in setOf("无", "全部售出", "保留指定份数") }
            .groupBy({ it.name }, { it.label })
            .mapValues { (_, labels) -> labels.toSet() }

        assertEquals(setOf("None"), labelsByName["无"])
        assertEquals(setOf("Sell all"), labelsByName["全部售出"])
        assertEquals(setOf("Reserve specified quantity"), labelsByName["保留指定份数"])
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
