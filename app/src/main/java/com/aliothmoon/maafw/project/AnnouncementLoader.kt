package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.AnnouncementCatalog
import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.Diagnostic.Companion.warning
import com.aliothmoon.maafw.domain.DiagnosticMessages
import com.aliothmoon.maafw.domain.ProjectAnnouncement
import java.security.MessageDigest

data class AnnouncementLoadResult(
    val catalog: AnnouncementCatalog,
    val diagnostics: List<Diagnostic> = emptyList(),
)

/** 隐藏目录约定、Markdown 投影和整组已读指纹的公告加载模块。 */
class AnnouncementLoader(private val source: ProjectSource) {

    fun load(): AnnouncementLoadResult {
        val diagnostics = mutableListOf<Diagnostic>()
        val rawFiles = mutableListOf<Pair<String, String>>()
        val items = try {
            source.list(DIRECTORY)
                .asSequence()
                .filter { it.endsWith(".md", ignoreCase = true) }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .mapNotNull { name ->
                    val path = "$DIRECTORY/$name"
                    val content = try {
                        source.read(path)
                    } catch (e: Exception) {
                        diagnostics += warning(
                            path,
                            DiagnosticMessages.descriptionReadFailed(e.message.orEmpty()),
                        )
                        return@mapNotNull null
                    }
                    val normalized = content.replace("\r\n", "\n").replace('\r', '\n').trim()
                    if (normalized.isBlank()) return@mapNotNull null
                    rawFiles += path to normalized
                    parse(path, normalized)
                }
                .toList()
        } catch (e: Exception) {
            diagnostics += warning(
                DIRECTORY,
                DiagnosticMessages.directoryEnumerationFailed(DIRECTORY, e.message.orEmpty()),
            )
            emptyList()
        }
        val fingerprint = rawFiles.takeIf { it.isNotEmpty() }?.let(::fingerprint)
        return AnnouncementLoadResult(AnnouncementCatalog(items, fingerprint), diagnostics)
    }

    private fun parse(path: String, content: String): ProjectAnnouncement {
        val firstLine = content.substringBefore('\n').trim()
        val remainder = content.substringAfter('\n', "").trimStart()
        val fallbackTitle = path.substringAfterLast('/').substringBeforeLast('.')
        return ProjectAnnouncement(
            id = path,
            title = firstLine.trimStart('#', ' ').trim().ifBlank { fallbackTitle },
            body = remainder.ifBlank { content },
            sourcePath = path,
        )
    }

    private fun fingerprint(files: List<Pair<String, String>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.flatMap { (path, content) -> listOf(path, content) }.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(0.toByte())
            digest.update(bytes)
            digest.update(0.toByte())
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DIRECTORY = "resource/announcement"
    }
}
