package com.aliothmoon.maafw.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PiAssetsValidatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeFile(root: File, rel: String, content: String = "{}") {
        val file = File(root, rel)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    @Test
    fun `a synced PI with all declared resource and Adb attach_resource_path directories passes`() {
        val pi = tmp.newFolder("pi")
        writeFile(
            pi,
            "interface.json",
            """
            {
                // JSONC comments and trailing commas are allowed in PI V2
                "interface_version": 2,
                "resource": [
                    { "name": "Official", "path": ["./resource"] },
                    { "name": "DesktopOnly", "controller": ["CloudWeb"], "path": ["./resource_desktop"] },
                ],
                "controller": [
                    {
                        "name": "ADB",
                        "type": "Adb",
                        "attach_resource_path": ["./resource_adb"],
                    },
                    {
                        "name": "CloudADB",
                        "type": "Adb",
                        "attach_resource_path": ["./resource_adb", "./resource_cloud", "./resource_cloud_adb"],
                    },
                    {
                        "name": "CloudWeb",
                        "type": "Win32",
                        "attach_resource_path": ["./resource_cloud_web"],
                    },
                ],
            }
            """.trimIndent(),
        )
        writeFile(pi, "resource/pipeline/main.json")
        writeFile(pi, "resource_adb/pipeline/adb.json")
        writeFile(pi, "resource_cloud/pipeline/cloud.json")
        writeFile(pi, "resource_cloud_adb/image/btn.png", "png")

        assertEquals(emptyList<String>(), verifySyncedPiAssets(pi))
    }

    @Test
    fun `an Adb controller whose attach_resource_path was omitted by include fails naming the controller and path`() {
        val pi = tmp.newFolder("pi")
        writeFile(
            pi,
            "interface.json",
            """
            {
                "interface_version": 2,
                "resource": [{ "name": "Official", "path": ["./resource"] }],
                "controller": [
                    {
                        "name": "CloudADB",
                        "type": "Adb",
                        "attach_resource_path": ["./resource_adb", "./resource_cloud_adb"]
                    }
                ]
            }
            """.trimIndent(),
        )
        writeFile(pi, "resource/pipeline/main.json")
        writeFile(pi, "resource_adb/pipeline/adb.json")

        val problems = verifySyncedPiAssets(pi)
        assertEquals(
            listOf("""controller "CloudADB" (Adb): attach_resource_path "resource_cloud_adb" is missing"""),
            problems,
        )
    }

    @Test
    fun `an empty directory is rejected because packPiArchive drops empty dirs`() {
        val pi = tmp.newFolder("pi")
        writeFile(
            pi,
            "interface.json",
            """
            {
                "interface_version": 2,
                "resource": [{ "name": "Official", "path": ["./resource"] }]
            }
            """.trimIndent(),
        )
        File(pi, "resource/empty_subdir").mkdirs()

        val problems = verifySyncedPiAssets(pi)
        assertEquals(
            listOf("""resource "Official": path "resource" contains no files"""),
            problems,
        )
    }

    @Test
    fun `a missing interface json or escaping path is rejected`() {
        val emptyPi = tmp.newFolder("empty")
        assertEquals(listOf("interface.json is missing"), verifySyncedPiAssets(emptyPi))

        val escapingPi = tmp.newFolder("escaping")
        writeFile(
            escapingPi,
            "interface.json",
            """
            {
                "interface_version": 2,
                "resource": [{ "name": "Bad", "path": ["../outside"] }]
            }
            """.trimIndent(),
        )
        val problems = verifySyncedPiAssets(escapingPi)
        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("escapes the PI root"))
    }
}
