package com.aliothmoon.maafw.project

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultProjectRepositoryTest {

    private class MapSource(private val files: Map<String, String>) : ProjectSource {
        override val projectName: String = "demo"
        override fun list(path: String): List<String> = emptyList()
        override fun read(path: String): String =
            files[path] ?: error("missing $path")
    }

    @Test
    fun `reload maps successful load to Ready`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = MapSource(
            mapOf(
                "interface.json" to """
                    {
                      "interface_version": 2,
                      "name": "demo",
                      "resource": [{"name":"R","path":["./base"]}],
                      "task": [{"name":"T","entry":"E"}]
                    }
                """.trimIndent(),
            ),
        )
        val repo = DefaultProjectRepository(ProjectLoader(source), dispatcher)
        repo.reload()
        advanceUntilIdle()
        val ready = repo.state.value as ProjectState.Ready
        assertEquals("demo", ready.definition.name)
        assertEquals(1, ready.definition.tasks.size)
    }

    @Test
    fun `reload maps missing interface to Error`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultProjectRepository(ProjectLoader(MapSource(emptyMap())), dispatcher)
        repo.reload()
        advanceUntilIdle()
        assertTrue(repo.state.value is ProjectState.Error)
    }

    @Test
    fun `latest reload wins when previous load is still in flight`() = runTest {
        var readCount = 0
        val source = object : ProjectSource {
            override val projectName: String = "demo"
            override fun list(path: String): List<String> = emptyList()
            override fun read(path: String): String {
                val n = ++readCount
                // 第一次读用真实时钟等待模拟慢请求；测试用真实 IO 调度
                if (n == 1) Thread.sleep(80)
                return """
                    {
                      "interface_version": 2,
                      "name": "demo",
                      "version": "${if (n == 1) "slow" else "fast"}",
                      "resource": [{"name":"R","path":["./base"]}],
                      "task": [{"name":"T","entry":"E"}]
                    }
                """.trimIndent()
            }
        }
        val repo = DefaultProjectRepository(ProjectLoader(source), kotlinx.coroutines.Dispatchers.IO)
        val first = backgroundScope.async(kotlinx.coroutines.Dispatchers.IO) { repo.reload() }
        Thread.sleep(10)
        val second = backgroundScope.async(kotlinx.coroutines.Dispatchers.IO) { repo.reload() }
        first.await()
        second.await()
        val ready = repo.state.value as ProjectState.Ready
        assertEquals("fast", ready.definition.version)
    }
}
