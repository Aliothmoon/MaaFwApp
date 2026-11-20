package com.aliothmoon.maafw.ui.components

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.util.LauncherApps
import kotlinx.coroutines.withContext

// 列表滚动时反复进出屏，按字节数封顶，不按条数
private val iconCache = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().byteCount
}

/** 已装应用的图标；IO 线程解码，解出来之前占同样大小的空位 */
@Composable
fun MaaAppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    size: Dp = MaaDesignTokens.IconContainer.lg,
) {
    val context = LocalContext.current.applicationContext
    val sizePx = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    val key = "$packageName@$sizePx"
    // 按 key 重取初值：produceState 换 key 时留着上一个包的图标，解码期间会闪成别家的
    var icon by remember(key) { mutableStateOf<ImageBitmap?>(iconCache.get(key)) }
    LaunchedEffect(key) {
        if (icon == null) {
            icon = withContext(MaaDispatchers.IO) {
                LauncherApps.icon(context, packageName, sizePx).asImageBitmap().also { iconCache.put(key, it) }
            }
        }
    }
    val current = icon
    if (current == null) {
        Spacer(modifier.size(size))
    } else {
        Image(bitmap = current, contentDescription = null, modifier = modifier.size(size))
    }
}

/** 图标 + 应用名 + 包名（应用名就是包名时不重复显示） */
@Composable
fun MaaAppListItem(
    label: String,
    packageName: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MaaDesignTokens.Spacing.sm, vertical = MaaDesignTokens.Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MaaAppIcon(packageName)
        Spacer(Modifier.width(MaaDesignTokens.Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (label != packageName) {
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}
