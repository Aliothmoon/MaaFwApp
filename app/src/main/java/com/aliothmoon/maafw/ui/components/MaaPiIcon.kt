package com.aliothmoon.maafw.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.constant.AppFiles
import com.aliothmoon.maafw.constant.AppPaths
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * PI 各级声明的 icon
 *
 * 取的是解包目录，与 [MaaMarkdown] 的相对路径图片同一套映射。
 * 解包在首启加载前完成。**图标文件要落进包里，profile 的 `include` 必须覆盖它所在目录**
 *
 * 位图一律不 tint：PI 图标是彩图，涂成单色就没了原意
 */
@Composable
fun MaaPiIcon(
    path: String?,
    size: Dp,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    if (path.isNullOrBlank()) return
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val key = "$path@$sizePx"
    val bitmap by produceState(initialValue = PiIconCache.peek(key), key) {
        if (value == null) value = PiIconCache.load(path, sizePx, key)
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size),
        )
    }
}

private object PiIconCache {

    /** 缓存按字节计：图标原图尺寸差异大，按条目数限不住内存 */
    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 16).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /** 取不到的也记一笔：PI 写错路径时，列表每滚一次都会重来一遍 open + decode */
    private val missing = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** 命中即同步返回，否则一列 chip 每次重组都要先闪一次无图 */
    fun peek(key: String): ImageBitmap? = cache.get(key)

    suspend fun load(path: String, sizePx: Int, key: String): ImageBitmap? =
        withContext(MaaDispatchers.IO) {
            if (key in missing) return@withContext null
            val file = File(AppPaths.ROOT, "${AppFiles.PI_DIR}/$path")
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, sizePx)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
                    ?.asImageBitmap()
            }.onFailure {
                // PI 写错图标路径或 profile 漏配 include 都落这里；不产诊断，图标缺失不该拦住加载
                Timber.w("PI icon unavailable: %s (%s)", file.path, it.message)
            }.getOrNull()?.also { cache.put(key, it) } ?: run {
                missing += key
                null
            }
        }

    private fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
        if (width <= 0 || height <= 0 || targetPx <= 0) return 1
        var sample = 1
        var shortSide = max(1, minOf(width, height))
        while (shortSide / 2 >= targetPx) {
            shortSide /= 2
            sample *= 2
        }
        return sample
    }
}
