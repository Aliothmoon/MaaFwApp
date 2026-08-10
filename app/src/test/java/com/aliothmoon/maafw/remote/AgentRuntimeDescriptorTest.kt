package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.runner.AgentPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeDescriptorTest {

    @Test
    fun `bundle 形态解析出可执行体与占位符 env`() {
        val descriptor = AgentRuntimeDescriptor.parse(
            """
            {
              "runtimes": [
                {
                  "location": "bundle",
                  "executable": "bin/python3",
                  "argsPrefix": ["-u"],
                  "env": { "PYTHONHOME": "{bundle}/prefix" }
                }
              ]
            }
            """.trimIndent(),
        )
        val entry = descriptor.runtimes.single()
        assertEquals(AgentRuntimeLocation.BUNDLE, entry.location)
        assertEquals("bin/python3", entry.executable)
        assertEquals(listOf("-u"), entry.argsPrefix)
        assertEquals("{bundle}/prefix", entry.env.getValue("PYTHONHOME"))
    }

    @Test
    fun `nativeLibs 形态可以只写可执行体`() {
        val descriptor = AgentRuntimeDescriptor.parse(
            """{"runtimes":[{"location":"nativeLibs","executable":"libpiagent.so"}]}""",
        )
        val entry = descriptor.runtimes.single()
        assertEquals(AgentRuntimeLocation.NATIVE_LIBS, entry.location)
        assertTrue(entry.argsPrefix.isEmpty())
        assertTrue(entry.env.isEmpty())
    }

    @Test
    fun `多个 runtime 按声明顺序，与 PI 的 agent 数组一一对应`() {
        val descriptor = AgentRuntimeDescriptor.parse(
            """
            {"runtimes":[
              {"location":"nativeLibs","executable":"libgo.so"},
              {"location":"bundle","executable":"bin/algo"}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("libgo.so", "bin/algo"), descriptor.runtimes.map { it.executable })
    }

    @Test
    fun `占位符按 bundle 与 nativeLibs 替换`() {
        assertEquals(
            "/tmp/rt/prefix/lib:/data/app/lib/arm64",
            "{bundle}/prefix/lib:{nativeLibs}".resolveAgentPlaceholders("/tmp/rt", "/data/app/lib/arm64"),
        )
    }

    @Test
    fun `非占位符内容原样保留`() {
        assertEquals(
            "a=1;b={unknown}",
            "a=1;b={unknown}".resolveAgentPlaceholders("/tmp/rt", "/libs"),
        )
    }

    @Test
    fun `nativeLibs 条目里写 bundle 占位符直接失败`() {
        // 替成空串会得到看着像绝对路径的 /prefix，排查成本比直接失败高得多
        assertThrows(AgentLaunchException::class.java) {
            "{bundle}/prefix".resolveAgentPlaceholders(null, "/libs")
        }
    }

    @Test
    fun `NoAgentHost 一律失败并带上 PI 声明的 child_exec`() {
        val error = assertThrows(AgentLaunchException::class.java) {
            NoAgentHost.launch(
                AgentLaunchRequest(
                    index = 0,
                    agent = AgentPayload("python", listOf("-u")),
                    identifier = "id",
                    apkPath = "/a.apk",
                    nativeLibraryDir = "/libs",
                    workingDir = "/pi",
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains("python"))
    }
}
