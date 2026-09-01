package com.aliothmoon.maafw.ui.components
import com.aliothmoon.maafw.MaaDispatchers

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.viewinterop.AndroidView
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.constant.AppFiles
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.project.isRemoteUrl
import com.aliothmoon.maafw.project.normalizeProjectPath
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.SpannableBuilder
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.CssInlineStyleParser
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.html.HtmlTag
import io.noties.markwon.html.MarkwonHtmlRenderer
import io.noties.markwon.html.TagHandler
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.file.FileSchemeHandler
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File

/**
 * PI description 富文本渲染：Markdown 为主、HTML 子集透传（与桌面端 MXU 展示习惯同构）
 *
 * - 表格走 `ext-tables`：core 不带表格，PI 的 CONTACT 这类正文正是表格写的；
 * - `<span style>` 的 color/font-size/font-weight 由自定义 TagHandler 解析；
 *   黑白灰系颜色映射主题（深浅色都可读），彩色原样保留；
 * - 相对路径图片映射到 PI 解包目录，网络图直连；
 * - URL 形态的 description 懒加载（OkHttp + ETag 磁盘缓存），失败回落显示原始 URL
 */
@Composable
fun MaaMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = Int.MAX_VALUE,
    linksClickable: Boolean = true,
) {
    val context = LocalContext.current
    var body by remember(text) { mutableStateOf(if (isRemoteUrl(text)) null else text) }
    if (isRemoteUrl(text)) {
        LaunchedEffect(text) {
            body = DescriptionFetcher.get(context).fetch(text)
        }
    }

    val resolved = body ?: stringResource(R.string.common_loading)

    // 认不出任何记号就走 Compose Text：M9A 的 397 条 description 里 382 条是纯文本，
    // 而 AndroidView 要真 View、要跑两套 measure。必须排在建管线与解析之前，
    // 否则解析照跑一遍，省下的只有一个 View
    // 判不准一律回 Markwon——多花几毫秒，总好过把标记直出给用户
    if (!needsRichText(resolved)) {
        Text(
            text = resolved,
            modifier = modifier.fillMaxWidth(),
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
        return
    }

    val onSurface = MaterialTheme.colorScheme.onSurface.toArgb()
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    // Markwon 管线按主题色三元组进程级复用（深浅色各一份），列表项不再各建一套插件
    val markwon = remember(onSurface, onSurfaceVariant, linkColor) {
        cachedMarkwon(context, onSurface, onSurfaceVariant, linkColor)
    }
    // Markdown 解析只在文本/管线变化时执行，与无关重组解耦
    val spanned = remember(markwon, resolved) {
        markwon.toMarkdown(rewriteRelativeImages(resolved))
    }

    val textColor = color.toArgb()
    val fontSizeSp = if (style.fontSize.isSpecified) style.fontSize.value else 12f

    AndroidView(
        // 必须占满宽：表格的列宽取自 TextView 自身宽度（SpanUtils.width），而 TableRowSpan
        // 首次测量时报 0，wrap_content 下宽度就塌成一条竖线，之后再也涨不回来
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            TextView(ctx).apply {
                setTextIsSelectable(false)
                includeFontPadding = false
            }
        },
        update = { view ->
            view.setTextColor(textColor)
            view.textSize = fontSizeSp
            view.maxLines = maxLines
            view.ellipsize = if (maxLines == Int.MAX_VALUE) null else TextUtils.TruncateAt.END
            markwon.setParsedMarkdown(view, spanned)
            if (linksClickable) {
                // 表格单元格里的链接落在 TableRowSpan 内，LinkMovementMethod 命中不了
                view.movementMethod = TableAwareMovementMethod.create()
            } else {
                view.movementMethod = null
                view.isClickable = false
                view.isFocusable = false
            }
        },
    )
}

/** 行内记号；`-` 之类只在行首才是记号，单列到 [RICH_LINE] */
private val RICH_MARKERS = charArrayOf('*', '_', '`', '#', '[', ']', '<', '>', '|', '~')

private val RICH_LINE = Regex("""^\s*(?:[-+]\s|\d+\.\s)""", RegexOption.MULTILINE)

/** linkify 插件会把裸 URL 变成可点链接，纯文本路径给不出这个，含 URL 的一律回 Markwon */
private val BARE_URL = Regex("""https?://|www\.""", RegexOption.IGNORE_CASE)

private fun needsRichText(text: String): Boolean =
    text.any { it in RICH_MARKERS } ||
        BARE_URL.containsMatchIn(text) ||
        RICH_LINE.containsMatchIn(text)

// 组合只发生在主线程，普通 HashMap 即可；key = 主题色三元组
private val markwonCache = HashMap<List<Int>, Markwon>()

private fun cachedMarkwon(context: Context, onSurface: Int, onSurfaceVariant: Int, linkColor: Int): Markwon =
    markwonCache.getOrPut(listOf(onSurface, onSurfaceVariant, linkColor)) {
        buildMarkwon(context.applicationContext, onSurface, onSurfaceVariant, linkColor)
    }

private fun buildMarkwon(
    context: Context,
    onSurface: Int,
    onSurfaceVariant: Int,
    linkColor: Int,
): Markwon = Markwon.builder(context)
    .usePlugin(HtmlPlugin.create { plugin ->
        plugin.addHandler(StyledSpanTagHandler(onSurface, onSurfaceVariant))
    })
    .usePlugin(ImagesPlugin.create { plugin ->
        // file:// 指向解包目录（相对路径图片经 rewriteRelativeImages 映射至此）
        plugin.addSchemeHandler(FileSchemeHandler.create())
    })
    .usePlugin(TablePlugin.create { builder ->
        builder.tableBorderColor(onSurfaceVariant)
            .tableBorderWidth(1)
    })
    .usePlugin(LinkifyPlugin.create())
    .usePlugin(StrikethroughPlugin.create())
    .usePlugin(object : AbstractMarkwonPlugin() {
        override fun configureTheme(builder: MarkwonTheme.Builder) {
            builder.linkColor(linkColor)
        }
    })
    .build()

private val MARKDOWN_RELATIVE_IMAGE = Regex("""(!\[[^\]]*]\()(?!https?://|file:|data:)([^)\s]+)""")
private val HTML_RELATIVE_IMAGE = Regex("""(<img[^>]*\bsrc=")(?!https?://|file:|data:)([^"]+)""", RegexOption.IGNORE_CASE)

/** 相对路径图片（md 与 <img>）重写为 PI 解包目录 URI；http/file/data 原样保留 */
private fun rewriteRelativeImages(body: String): String {
    val root = File(AppPaths.ROOT, AppFiles.PI_DIR)
    fun toUri(rel: String): String = File(root, normalizeProjectPath(rel)).toURI().toString()
    return body
        .replace(MARKDOWN_RELATIVE_IMAGE) {
            "${it.groupValues[1]}${toUri(it.groupValues[2])}"
        }
        .replace(HTML_RELATIVE_IMAGE) {
            "${it.groupValues[1]}${toUri(it.groupValues[2])}"
        }
}

/**
 * `<span style="color:…;font-size:…px;font-weight:bold">` 处理器
 * Markwon HtmlPlugin 默认丢弃内联 CSS，这里补齐并做主题映射：
 * 黑/白 -> onSurface，灰系 -> onSurfaceVariant，其余彩色尊重作者原意
 */
private class StyledSpanTagHandler(
    private val onSurface: Int,
    private val onSurfaceVariant: Int,
) : TagHandler() {

    override fun supportedTags(): Collection<String> = listOf("span", "font")

    override fun handle(visitor: MarkwonVisitor, renderer: MarkwonHtmlRenderer, tag: HtmlTag) {
        if (tag.isBlock) {
            visitChildren(visitor, renderer, tag.asBlock)
        }
        val spans = mutableListOf<Any>()
        tag.attributes()["style"]?.let { style ->
            for (property in CssInlineStyleParser.create().parse(style)) {
                when (property.key()) {
                    "color" -> parseCssColor(property.value())?.let { spans += ForegroundColorSpan(it) }
                    "font-size" -> parseFontSizePx(property.value())?.let { spans += AbsoluteSizeSpan(it, true) }
                    "font-weight" -> if (property.value().trim() == "bold") spans += StyleSpan(Typeface.BOLD)
                }
            }
        }
        // <font color="…"> 兼容
        tag.attributes()["color"]?.let { value ->
            parseCssColor(value)?.let { spans += ForegroundColorSpan(it) }
        }
        if (spans.isNotEmpty()) {
            SpannableBuilder.setSpans(visitor.builder(), spans.toTypedArray(), tag.start(), tag.end())
        }
    }

    private fun parseCssColor(raw: String): Int? {
        val value = raw.trim().lowercase()
        // 黑白灰映射主题色，深浅主题都可读
        when (value) {
            "black", "#000", "#000000" -> return onSurface
            "white", "#fff", "#ffffff" -> return onSurface
            "gray", "grey", "darkgray", "darkgrey", "lightgray", "lightgrey", "dimgray", "dimgrey" ->
                return onSurfaceVariant
        }
        CSS_COLORS[value]?.let { return it }
        return try {
            android.graphics.Color.parseColor(value)
        } catch (_: IllegalArgumentException) {
            Timber.w("Cannot parse CSS color: %s", raw)
            null
        }
    }

    private fun parseFontSizePx(raw: String): Int? =
        Regex("""(\d+)\s*px""").find(raw.trim())?.groupValues?.get(1)?.toIntOrNull()

    companion object {
        /** Android Color.parseColor 不认识的常用 CSS 颜色名 */
        private val CSS_COLORS: Map<String, Int> = mapOf(
            "crimson" to 0xFFDC143C.toInt(),
            "tomato" to 0xFFFF6347.toInt(),
            "orange" to 0xFFFFA500.toInt(),
            "darkorange" to 0xFFFF8C00.toInt(),
            "gold" to 0xFFFFD700.toInt(),
            "deepskyblue" to 0xFF00BFFF.toInt(),
            "dodgerblue" to 0xFF1E90FF.toInt(),
            "royalblue" to 0xFF4169E1.toInt(),
            "skyblue" to 0xFF87CEEB.toInt(),
            "coral" to 0xFFFF7F50.toInt(),
            "salmon" to 0xFFFA8072.toInt(),
            "hotpink" to 0xFFFF69B4.toInt(),
            "violet" to 0xFFEE82EE.toInt(),
            "mediumseagreen" to 0xFF3CB371.toInt(),
            "seagreen" to 0xFF2E8B57.toInt(),
        )
    }
}

/** URL 形态 description 的拉取器：OkHttp + ETag 磁盘缓存 */
class DescriptionFetcher private constructor(context: Context) {

    private val client = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "pi_description_http"), CACHE_SIZE_BYTES))
        .build()

    /** 失败时回落返回原始 URL 文本 */
    suspend fun fetch(url: String): String = withContext(MaaDispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Failed to fetch description: HTTP %d for %s", response.code, url)
                    return@withContext url
                }
                response.body.string()
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch description: %s", url)
            url
        }
    }

    companion object {
        private const val CACHE_SIZE_BYTES = 5L * 1024 * 1024

        @Volatile
        private var instance: DescriptionFetcher? = null

        fun get(context: Context): DescriptionFetcher =
            instance ?: synchronized(this) {
                instance ?: DescriptionFetcher(context.applicationContext).also { instance = it }
            }
    }
}
