package com.aliothmoon.maafw.ui.components

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.constant.AppFiles
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.project.DescriptionFetcher
import com.aliothmoon.maafw.project.isRemoteUrl
import com.aliothmoon.maafw.project.normalizeProjectPath
import timber.log.Timber
import java.io.File
import kotlin.math.roundToInt

/**
 * PI 长正文（welcome / contact / license）走 WebView：与 MXU 一样把 Markdown 转成 HTML 交给浏览器排版，
 * 内联 CSS 的定位、flex、渐变都按作者写的样子出来
 *
 * - 不开 JS，不放行文件访问；相对路径资源经 [PI_HTML_BASE_URL] 拦截，只供给 PI 解包目录内的文件；
 * - 点链接交给系统浏览器，页面本身不跳转；
 * - URL 形态的正文先拉取，失败时回落显示原始 URL（同 [MaaMarkdown]）
 */
@Composable
fun MaaHtmlBody(
    bodies: List<String>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var resolved by remember(bodies) {
        mutableStateOf(if (bodies.none(::isRemoteUrl)) bodies else null)
    }
    if (resolved == null) {
        LaunchedEffect(bodies) {
            val fetcher = DescriptionFetcher.get(context)
            resolved = bodies.map { if (isRemoteUrl(it)) fetcher.fetch(it) else it }
        }
        Text(
            text = stringResource(R.string.common_loading),
            modifier = modifier,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val scheme = MaterialTheme.colorScheme
    val colors = PiHtmlColors(
        dark = scheme.surface.luminance() < 0.5f,
        text = scheme.onSurface.css(),
        muted = scheme.onSurfaceVariant.css(),
        link = scheme.primary.css(),
        line = scheme.outlineVariant.css(),
        code = scheme.surfaceContainerHighest.css(),
    )
    val html = remember(resolved, colors) { piHtmlDocument(piBodiesToHtml(resolved.orEmpty()), colors) }

    // WebView 不认 Compose 的 LocalDensity：页面缩放与字体缩放折算成 textZoom
    val density = LocalDensity.current
    val displayDensity = LocalResources.current.displayMetrics.density
    val textZoom = (density.fontScale * density.density / displayDensity * 100).roundToInt()

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                configure(settings)
                webViewClient = PiHtmlClient()
            }
        },
        update = { view ->
            view.settings.textZoom = textZoom
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(PI_HTML_BASE_URL, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

private fun configure(settings: WebSettings) {
    settings.javaScriptEnabled = false
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.setSupportMultipleWindows(false)
    // 正文里偶有 http 图片，跟浏览器一样放行被动内容
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
}

private class PiHtmlClient : WebViewClient() {

    private val root: File by lazy { File(AppPaths.ROOT, AppFiles.PI_DIR).canonicalFile }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        // 页面自己不跳；只把用户点出来的外链交给系统
        if (request.isForMainFrame && request.hasGesture()) {
            val url = request.url
            if (url.host != PI_HOST && url.scheme in EXTERNAL_SCHEMES) {
                val intent = Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { view.context.startActivity(intent) }
                    .onFailure { Timber.w(it, "No activity handles the link in the PI text") }
            }
        }
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.host != PI_HOST) return null
        val path = url.path?.removePrefix("/").orEmpty()
        val file = File(root, normalizeProjectPath(path)).canonicalFile
        // 越出解包目录（../）或不存在一律 404，不给页面读到别处的文件
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) {
            return WebResourceResponse(null, null, 404, "Not Found", emptyMap(), null)
        }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        return WebResourceResponse(mime, null, file.inputStream())
    }

    companion object {
        private val PI_HOST = PI_HTML_BASE_URL.toUri().host
        private val EXTERNAL_SCHEMES = setOf("http", "https", "mailto")
    }
}

private fun Color.css(): String =
    "rgba(${(red * 255).roundToInt()}, ${(green * 255).roundToInt()}, ${(blue * 255).roundToInt()}, $alpha)"
