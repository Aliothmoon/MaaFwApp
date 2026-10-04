package com.aliothmoon.maafw.config.backup

import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.InputFieldDefinition
import com.aliothmoon.maafw.domain.OptionCaseDefinition
import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.PipelineType
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.TaskDefinition
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64
import java.util.zip.Deflater

/** 导入导出测试共用的一份 PI：任务选项覆盖 select（带子选项）、switch、checkbox、带 password 的 input */
internal object BackupFixtures {

    private fun case(name: String, children: List<String> = emptyList()) =
        OptionCaseDefinition(name, name, null, JsonObject(emptyMap()), children)

    private fun field(name: String, password: Boolean = false) = InputFieldDefinition(
        name = name,
        pipelineType = PipelineType.StringType,
        default = "",
        verify = null,
        patternMessage = null,
        description = null,
        password = password,
    )

    private fun task(name: String, options: List<String>, controllers: List<String> = emptyList()) = TaskDefinition(
        name = name,
        entry = name,
        description = null,
        groups = emptyList(),
        optionNames = options,
        pipelineOverride = JsonObject(emptyMap()),
        controllers = controllers,
        resources = emptyList(),
        defaultCheck = false,
    )

    val definition = ProjectDefinition(
        name = "MaaEnd",
        version = "v1.0.0",
        controllers = listOf(ControllerDefinition(name = "ADB", optionNames = listOf("Touch"))),
        resources = listOf(
            ResourceDefinition(name = "官服", paths = emptyList(), optionNames = listOf("Server")),
            ResourceDefinition(name = "B服", paths = emptyList(), optionNames = listOf("Server")),
        ),
        tasks = listOf(
            task("Daily", listOf("Mode", "Toggle", "Pick", "Account")),
            task("Win32Only", emptyList(), controllers = listOf("Win32-Window")),
        ),
        groups = emptyList(),
        options = mapOf(
            "Mode" to OptionDefinition.Select("Mode", "Mode", null, listOf(case("A"), case("B", listOf("Sub"))), null),
            "Sub" to OptionDefinition.Select("Sub", "Sub", null, listOf(case("s1"), case("s2")), null),
            "Toggle" to OptionDefinition.Switch("Toggle", "Toggle", null, listOf(case("Yes"), case("No")), "No"),
            "Pick" to OptionDefinition.Checkbox("Pick", "Pick", null, listOf(case("x"), case("y")), emptyList()),
            "Account" to OptionDefinition.Input(
                "Account", "Account", null,
                listOf(field("user"), field("pin", password = true)),
                JsonObject(emptyMap()),
            ),
            "Server" to OptionDefinition.Select("Server", "Server", null, listOf(case("r1"), case("r2")), null),
            "Touch" to OptionDefinition.Select("Touch", "Touch", null, listOf(case("t1"), case("t2")), null),
            "Global" to OptionDefinition.Select("Global", "Global", null, listOf(case("g1"), case("g2")), null),
        ),
        globalOptionNames = listOf("Global"),
        templates = emptyList(),
    )

    /** 与 MXU 的 encryptSecret 相同：UTF-8 异或重复的 key，再标准 Base64 */
    fun mxuEncrypt(plain: String, key: String): String {
        val bytes = plain.encodeToByteArray()
        val keyBytes = key.encodeToByteArray()
        return Base64.getEncoder().encodeToString(
            ByteArray(bytes.size) { (bytes[it].toInt() xor keyBytes[it % keyBytes.size].toInt()).toByte() },
        )
    }

    /** 按 MXU buildTabConfigExportText 拼一份三行分享码 */
    fun mxuShareCode(projectName: String, tabName: String, wireJson: String, version: String = "v1"): String {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(wireJson.encodeToByteArray())
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        val data = Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
        val name = URLEncoder.encode(tabName, Charsets.UTF_8).replace("+", "%20")
        return "「$tabName」的 $projectName 配置，发给你啦~\n$projectName://tab-sharing/$version/$name/$data\n👆 复制这段文字"
    }
}
