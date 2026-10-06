package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.ProjectMetadata
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val ANNO = "https://example.com/anno.md"

/** 按 URL 查表的拉取器；查不到即视为拉取失败 */
private class MapFetcher(var bodies: Map<String, String>) : RemoteTextFetcher {
    val requests = mutableListOf<String>()
    override suspend fun fetchOrNull(url: String): String? {
        requests += url
        return bodies[url]
    }
}

private fun metadata(vararg declarations: String) =
    ProjectMetadata(welcome = declarations.toList(), welcomeDeclarations = declarations.toList())

class WelcomeResolverTest {

    @Test
    fun `URL 形态展示拉到的正文且同一轮只拉一次`() = runTest {
        val fetcher = MapFetcher(mapOf(ANNO to "# 公告"))

        val resolved = WelcomeResolver(fetcher).resolve(metadata(ANNO), "v1")!!

        assertEquals(listOf("# 公告"), resolved.bodies)
        assertEquals(listOf(ANNO), fetcher.requests)
    }

    /** 回归：指纹曾算在 URL 本身上，远端公告更新后 URL 不变，永远不再弹 */
    @Test
    fun `URL 不变但正文变了指纹跟着变`() = runTest {
        val fetcher = MapFetcher(mapOf(ANNO to "旧公告"))
        val resolver = WelcomeResolver(fetcher)
        val before = resolver.resolve(metadata(ANNO), "v1")!!.fingerprint

        fetcher.bodies = mapOf(ANNO to "新公告")
        val after = resolver.resolve(metadata(ANNO), "v1")!!.fingerprint

        assertNotEquals(before, after)
    }

    @Test
    fun `任一 URL 拉取失败不给结果`() = runTest {
        val fetcher = MapFetcher(emptyMap())
        assertNull(WelcomeResolver(fetcher).resolve(metadata("本地公告", ANNO), "v1"))
    }

    @Test
    fun `没有 welcome 不给结果`() = runTest {
        assertNull(WelcomeResolver(MapFetcher(emptyMap())).resolve(ProjectMetadata(), "v1"))
    }

    /** 非 URL 的项沿用旧算法，已看过的用户升级后不会再弹一次 */
    @Test
    fun `非 URL 指纹与旧算法一致`() = runTest {
        val resolved = WelcomeResolver(MapFetcher(emptyMap())).resolve(metadata("hi"), "1.0.0")!!
        assertEquals("13b80d1bf5a812b6", resolved.fingerprint)
    }

    @Test
    fun `切语言换了译文不重弹`() = runTest {
        val resolver = WelcomeResolver(MapFetcher(emptyMap()))
        val zh = ProjectMetadata(welcome = listOf("欢迎"), welcomeDeclarations = listOf("\$welcome.body"))
        val en = ProjectMetadata(welcome = listOf("Welcome"), welcomeDeclarations = listOf("\$welcome.body"))

        assertEquals(resolver.resolve(zh, "v1")!!.fingerprint, resolver.resolve(en, "v1")!!.fingerprint)
    }

    @Test
    fun `PI 版本变化时指纹跟着变`() {
        assertNotEquals(welcomeFingerprint(listOf("hi"), "1.0.0"), welcomeFingerprint(listOf("hi"), "1.1.0"))
    }

    @Test
    fun `公告增删与重排都换指纹`() {
        val base = welcomeFingerprint(listOf("a", "b"), "1.0.0")
        assertNotEquals(base, welcomeFingerprint(listOf("b", "a"), "1.0.0"))
        assertNotEquals(base, welcomeFingerprint(listOf("a", "b", "c"), "1.0.0"))
        assertNotEquals(base, welcomeFingerprint(listOf("a"), "1.0.0"))
        assertEquals(base, welcomeFingerprint(listOf("a", "b"), "1.0.0"))
    }
}
