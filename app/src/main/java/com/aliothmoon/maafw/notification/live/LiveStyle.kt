package com.aliothmoon.maafw.notification.live

/** 实时更新的状态栏胶囊、超级岛左栏与息屏显示什么 */
enum class LiveChipContent { BOTH, PROGRESS, TASK, LOG, NONE }

/**
 * 进度条配色；[argb] 为 null 的另行解析：DEFAULT 用各展示方式自己的默认色，CUSTOM 读 [LiveStyle.customColor]
 *
 * 没有 MaaMeow 的 BLUE：本项目的默认色就是那个蓝，再列一档只是重复
 */
enum class LiveColorScheme(val argb: Int?) {
    DEFAULT(null),
    GREEN(0xFF4CAF50.toInt()),
    ORANGE(0xFFFF9800.toInt()),
    PURPLE(0xFF9C27B0.toInt()),
    PINK(0xFFE91E63.toInt()),
    TEAL(0xFF009688.toInt()),
    CUSTOM(null),
}

/**
 * 实时更新进度条上的游标、超级岛小岛与状态栏的图标
 *
 * DEFAULT 交给各展示方式用自己的默认图（实时更新是圆点，超级岛是应用图标）；
 * 没有 MaaMeow 的 DOT：本项目的默认游标本来就是圆点
 */
enum class LiveTrackerIcon { DEFAULT, APP, CUSTOM }

/** 超级岛左栏两行 + 息屏文本；与实时更新的单行胶囊结构不同，按显示内容各自组合 */
data class LiveIslandTexts(
    val leftTitle: String,
    val leftContent: String,
    val aod: String,
)

/**
 * 运行通知样式（移植自 MaaMeow 的 LiveUpdateStyle）：配色与短文本，各展示方式共用
 *
 * 只作用于进行中的进度：本项目的运行通知只在执行期间存在，没有 MaaMeow 那套错误 / 完成的结果态，
 * 所以也没有「错误与完成固定语义色」那层
 */
data class LiveStyle(
    val chipContent: LiveChipContent = LiveChipContent.BOTH,
    val colorScheme: LiveColorScheme = LiveColorScheme.DEFAULT,
    val customColor: String = "",
    val trackerIcon: LiveTrackerIcon = LiveTrackerIcon.DEFAULT,
    val customTrackerPath: String = "",
) {
    /** 用户选的进度色；DEFAULT 或自定义色解析不了时为 null，由调用方用自己的默认色 */
    val progressColor: Int?
        get() = if (colorScheme == LiveColorScheme.CUSTOM) parseHexColor(customColor) else colorScheme.argb

    /** 超级岛的颜色字段只收 `#RRGGBB` */
    val progressColorHex: String?
        get() = progressColor?.let { "#%06X".format(it and 0xFFFFFF) }

    /** 实时更新的胶囊文本；null 是不设，"" 是显式清空 */
    fun chipText(content: RunNotificationContent): String? {
        val text = when (chipContent) {
            LiveChipContent.BOTH -> listOfNotNull(content.taskLabel, content.progressLabel).joinToString(" ")
            LiveChipContent.PROGRESS -> content.progressLabel.orEmpty()
            LiveChipContent.TASK -> content.taskLabel.orEmpty()
            LiveChipContent.LOG -> content.logLine
            LiveChipContent.NONE -> return ""
        }
        return text.ifBlank { null }
    }

    /** @param taskFallback 还没有当前任务名时左栏标题用什么（应用名） */
    fun islandTexts(content: RunNotificationContent, taskFallback: String): LiveIslandTexts {
        val taskName = (content.taskLabel ?: taskFallback).take(16)
        val progressText = content.progressLabel.orEmpty().take(8)
        val percentText = content.percent?.let { "$it%" } ?: "…"
        val logText = content.logLine.take(8)
        return when (chipContent) {
            LiveChipContent.BOTH -> LiveIslandTexts(taskName, progressText, percentText)
            LiveChipContent.PROGRESS -> LiveIslandTexts(progressText, "", percentText)
            LiveChipContent.TASK -> LiveIslandTexts(taskName, "", taskName)
            LiveChipContent.LOG -> LiveIslandTexts(taskName, logText, logText)
            LiveChipContent.NONE -> LiveIslandTexts("", "", "")
        }
    }

    companion object {
        /** 盘上是手改或旧版的非法值时各自回落默认，不让读设置本身失败 */
        fun parse(
            chipContent: String,
            colorScheme: String,
            customColor: String,
            trackerIcon: String,
            customTrackerPath: String,
        ) = LiveStyle(
            chipContent = LiveChipContent.entries.firstOrNull { it.name == chipContent } ?: LiveChipContent.BOTH,
            colorScheme = LiveColorScheme.entries.firstOrNull { it.name == colorScheme } ?: LiveColorScheme.DEFAULT,
            customColor = customColor,
            trackerIcon = LiveTrackerIcon.entries.firstOrNull { it.name == trackerIcon } ?: LiveTrackerIcon.DEFAULT,
            customTrackerPath = customTrackerPath,
        )

        /** 导出配置用：自定义图片只在本机 filesDir 里，换台设备不存在，CUSTOM 回落 DEFAULT */
        fun portableTrackerIcon(raw: String): String =
            if (raw == LiveTrackerIcon.CUSTOM.name) LiveTrackerIcon.DEFAULT.name else raw
    }
}

/** 状态句被去重掉（与任务名或进度相同）时退回阶段标题，「最新日志」那档不至于空着 */
private val RunNotificationContent.logLine: String
    get() = statusLine ?: title

/**
 * `#RRGGBB` 或 `#AARRGGBB`，其余一律 null
 *
 * 不用 `Color.parseColor`：它还认颜色名，且 JVM 单测里没有实现
 */
internal fun parseHexColor(raw: String): Int? {
    val hex = raw.trim().removePrefix("#")
    if (hex.length != 6 && hex.length != 8) return null
    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    val value = hex.toLong(16)
    return if (hex.length == 6) (value or 0xFF000000L).toInt() else value.toInt()
}
