package com.aliothmoon.maafw.config.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.Base64
import java.util.zip.Inflater

/**
 * MXU 的配置，只取导得过来的部分（对齐 MXU src/types/config.ts）
 *
 * MXU 的 `version` 恒为 "1.0"，旧格式靠逐字段兼容，所以这里每个字段都给缺省值、按 JsonElement 宽松地读：
 * 2026-06 前的定时只有 `hours`，2026-07 前没有 `enabledByController`，2026-08 前 password 还是明文
 */
@Serializable
internal data class MxuConfigFile(
    val instances: List<MxuInstance> = emptyList(),
    /** global_option 与 setting 分区的值，MXU 不分开存 */
    val globalOptionValues: Map<String, JsonElement> = emptyMap(),
)

@Serializable
internal data class MxuInstance(
    val name: String = "",
    val controllerName: String? = null,
    val resourceName: String? = null,
    val tasks: List<MxuTask> = emptyList(),
    val schedulePolicies: List<MxuSchedulePolicy> = emptyList(),
    /** 桌面端启动外部程序，Android 上没有对应物，只计数 */
    val preActions: List<JsonElement> = emptyList(),
    /** 2026-04 前的单个前置动作 */
    val preAction: JsonElement? = null,
)

@Serializable
internal data class MxuTask(
    val taskName: String = "",
    val customName: String? = null,
    val enabled: Boolean = false,
    /** controller 名 → 勾选状态；切 controller 时 MXU 按它恢复各自的勾选 */
    val enabledByController: Map<String, Boolean>? = null,
    val optionValues: Map<String, JsonElement> = emptyMap(),
)

@Serializable
internal data class MxuSchedulePolicy(
    val name: String = "",
    val enabled: Boolean = false,
    /** 0 = 周日 … 6 = 周六 */
    val weekdays: List<Int> = emptyList(),
    /** "HH:mm" */
    val times: List<String> = emptyList(),
    /** 2026-06 前的整点小时；MXU 自己读到时直接丢，这里折成 "HH:00" */
    val hours: List<Int> = emptyList(),
)

/** MXU 的 OptionValue；hotkey 外壳不支持（PI 里的 hotkey 选项加载期就跳过了），读到也不收 */
internal sealed interface MxuOptionValue {
    data class Select(val caseName: String) : MxuOptionValue
    data class Checkbox(val caseNames: List<String>) : MxuOptionValue
    data class Switch(val value: Boolean) : MxuOptionValue
    data class Input(val values: Map<String, String>, val encryptedValues: Map<String, String>) : MxuOptionValue

    companion object {
        fun parse(element: JsonElement): MxuOptionValue? {
            val obj = element as? JsonObject ?: return null
            return when (obj.string("type")) {
                "select" -> obj.string("caseName")?.let(::Select)
                "checkbox" -> Checkbox((obj["caseNames"] as? JsonArray).strings())
                "switch" -> (obj["value"] as? JsonPrimitive)?.booleanOrNull?.let(::Switch)
                "input" -> Input(obj.stringMap("values"), obj.stringMap("encryptedValues"))
                else -> null
            }
        }
    }
}

internal object MxuFormat {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        allowComments = true
        allowTrailingComma = true
    }

    fun decodeConfig(text: String): MxuConfigFile = json.decodeFromString(text)

    /**
     * 文件名表明是别的项目时返回文件名里那段名字；对得上、或者认不出（用户改过名）返回 null
     *
     * MXU 的配置叫 `mxu-{name}.json`，它自己的滚动备份叫 `mxu-{name}-yyyyMMdd-HHmmss.json`，
     * 下载重名还会变成 `mxu-{name} (1).json`：以 `mxu-{name}` 开头、后面不再接着名字就算同一个项目。
     * MXU 的 Rust 端起文件名时把 `/ \ . :` 换成 `_`，两边都按它折一遍再比
     */
    fun mismatchedProjectName(fileName: String?, projectName: String): String? {
        val stem = fileName?.let { CONFIG_FILE_NAME.matchEntire(it) }?.groupValues?.get(1) ?: return null
        val expected = projectName.fileSafe()
        val actual = stem.fileSafe()
        if (actual.startsWith(expected) && actual.getOrNull(expected.length)?.isLetterOrDigit() != true) return null
        return stem
    }

    private fun String.fileSafe() = replace(Regex("[/\\\\.:]"), "_")

    private val CONFIG_FILE_NAME = Regex("""mxu-(.+)\.json""", RegexOption.IGNORE_CASE)

    /** MXU 的密码混淆：UTF-8 按位异或重复的 key，再标准 Base64（src/utils/secretCrypto.ts） */
    fun decryptSecret(encrypted: String, keyMaterial: String): String? {
        if (encrypted.isEmpty()) return ""
        val bytes = runCatching { Base64.getDecoder().decode(encrypted) }.getOrNull() ?: return null
        val key = keyMaterial.encodeToByteArray()
        if (key.isEmpty()) return null
        val plain = ByteArray(bytes.size) { (bytes[it].toInt() xor key[it % key.size].toInt()).toByte() }
        return plain.decodeToString()
    }

    fun inputSecretKey(projectName: String, optionName: String, fieldName: String): String {
        val base = if (projectName.isNotEmpty()) "MXU-INPUT-$projectName" else "MXU-INPUT"
        return "$base-$optionName-$fieldName"
    }
}

/** 分享码解出来的一个标签页 */
internal data class MxuShareCode(val projectName: String, val instance: MxuInstance)

internal sealed interface MxuShareCodeResult {
    data class Decoded(val code: MxuShareCode) : MxuShareCodeResult
    data object Invalid : MxuShareCodeResult
    data object UnsupportedVersion : MxuShareCodeResult
}

/**
 * MXU 的单标签页分享码（src/utils/tabExportImport.ts）
 *
 * 三行文本，中间那行是 `{项目名}://tab-sharing/v1/{encodeURIComponent(标签名)}/{base64url(deflate-raw(JSON))}`，
 * 首尾两行是本地化的提示语。JSON 用的是短键：t=任务、tn=任务名、e=勾选、ec=按 controller 的勾选、ov=选项值
 */
internal object MxuShareCodes {

    private val json = Json { ignoreUnknownKeys = true }

    fun looksLikeShareCode(text: String): Boolean = MARKER in text

    /** 按整行找数据行再切项目名：PI 名字里可能带空格，与 MXU 的 `.+://tab-sharing/` 一致 */
    fun decode(text: String): MxuShareCodeResult {
        val line = text.lineSequence().map(String::trim).firstOrNull { MARKER in it }
            ?: return MxuShareCodeResult.Invalid
        val projectName = line.substringBefore(MARKER)
        val parts = line.substringAfter(MARKER).split('/')
        if (parts.size < 3) return MxuShareCodeResult.Invalid
        if (parts[0] != VERSION) return MxuShareCodeResult.UnsupportedVersion
        val instance = runCatching {
            // encodeURIComponent 不会留下裸的 +，URLDecoder 却把 + 当空格，先护一下
            val tabName = URLDecoder.decode(parts[1].replace("+", "%2B"), "UTF-8")
            val payload = json.parseToJsonElement(inflateRaw(Base64.getUrlDecoder().decode(parts.drop(2).joinToString("/"))))
            payload.toInstance(tabName)
        }.getOrNull() ?: return MxuShareCodeResult.Invalid
        return MxuShareCodeResult.Decoded(MxuShareCode(projectName, instance))
    }

    private fun inflateRaw(bytes: ByteArray): String {
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(bytes.size * 4)
            val buffer = ByteArray(8 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
                // 分享码不过几 KB，解出来远超这个数就不是正常数据
                check(out.size() <= MAX_INFLATED_BYTES) { "share code payload too large" }
            }
            return out.toByteArray().decodeToString()
        } finally {
            inflater.end()
        }
    }

    private fun JsonElement.toInstance(tabName: String): MxuInstance {
        val payload = this as JsonObject
        val tasks = (payload["t"] as JsonArray).map { element ->
            val task = element as JsonObject
            MxuTask(
                taskName = task.string("tn").orEmpty(),
                customName = task.string("cn"),
                enabled = (task["e"] as? JsonPrimitive)?.booleanOrNull ?: false,
                enabledByController = (task["ec"] as? JsonObject)?.mapNotNull { (name, value) ->
                    (value as? JsonPrimitive)?.booleanOrNull?.let { name to it }
                }?.toMap(),
                optionValues = (task["ov"] as? JsonObject).orEmpty().mapValues { (_, value) -> value.wireToOptionValue() },
            )
        }
        return MxuInstance(
            name = tabName,
            controllerName = payload.string("cn"),
            resourceName = payload.string("rn"),
            tasks = tasks,
            preActions = (payload["pa"] as? JsonArray).orEmpty(),
        )
    }

    /** 短键还原成 MXU 落盘的长键形态，后面和配置文件走同一套转换 */
    private fun JsonElement.wireToOptionValue(): JsonElement {
        val wire = this as? JsonObject ?: return this
        val type = when (wire.string("t")) {
            "s" -> return JsonObject(mapOf("type" to JsonPrimitive("select"), "caseName" to (wire["c"] ?: JsonPrimitive(""))))
            "cb" -> return JsonObject(mapOf("type" to JsonPrimitive("checkbox"), "caseNames" to (wire["c"] ?: JsonArray(emptyList()))))
            "sw" -> return JsonObject(mapOf("type" to JsonPrimitive("switch"), "value" to (wire["v"] ?: JsonPrimitive(false))))
            "in" -> "input"
            "hk" -> "hotkey"
            else -> return wire
        }
        return JsonObject(
            buildMap {
                put("type", JsonPrimitive(type))
                wire["v"]?.let { put("values", it) }
                wire["ev"]?.let { put("encryptedValues", it) }
            },
        )
    }

    private const val MARKER = "://tab-sharing/"
    private const val VERSION = "v1"
    private const val MAX_INFLATED_BYTES = 4 * 1024 * 1024
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonArray?.strings(): List<String> = orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

private fun JsonObject.stringMap(key: String): Map<String, String> =
    (this[key] as? JsonObject).orEmpty().mapNotNull { (name, value) ->
        (value as? JsonPrimitive)?.contentOrNull?.let { name to it }
    }.toMap()
