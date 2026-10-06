package com.aliothmoon.maafw.theme

import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.wallpaper.WallpaperSettings

/** 模糊滑块 100% 对应的半径；只在 API 31+ 生效 */
private val MaxWallpaperBlur: Dp = 24.dp

/** 视差单侧最大横移，占屏宽比例 */
private const val PARALLAX_SHIFT = 0.04f

/** 视差恒定放大：两侧各溢出 5%，大于 [PARALLAX_SHIFT] 才不会横移到露边 */
private const val PARALLAX_ZOOM = 1.1f

/**
 * 画一层背景的取值函数；有自定义背景时由 [WallpaperHost] 下发
 *
 * 二级页面是盖在主 tab 上的另一层，玻璃配色下底是透明的，不自己补一层背景就会透出底下的主 tab
 */
val LocalWallpaperBackdrop = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

/**
 * 应用级背景（移植自 MaaMeow 的 AppBackgroundHost）：有图时铺背景并换玻璃配色，没图时只是透传
 *
 * 结构不随有无背景变化（始终是 Box + 一层配色），开关背景不会让子树重建、pager 页码与各页状态都留着
 *
 * @param parallax 主 tab 的归一化位置（-1~1），在 graphicsLayer 里延迟读取，滑动只重铺图层不重组
 */
@Composable
fun WallpaperHost(
    image: ImageBitmap?,
    settings: WallpaperSettings,
    parallax: () -> Float,
    content: @Composable () -> Unit,
) {
    val base = MaterialTheme.colorScheme
    val reduceMotion = rememberReduceMotion()
    val backdrop: (@Composable () -> Unit)? = image?.let {
        remember(it, settings, base.background, reduceMotion, parallax) {
            @Composable {
                WallpaperLayer(
                    image = it,
                    settings = settings,
                    baseColor = base.background,
                    parallax = parallax.takeUnless { reduceMotion },
                )
            }
        }
    }
    val scheme = if (image != null) remember(base) { base.toGlass() } else base
    // 半透明卡片底下的投影会从卡面透出来一团灰，有背景时一律压平，靠描边分层
    val style = MaaTheme.style.let { if (image != null) it.copy(cardElevation = 0.dp) else it }
    Box(Modifier.fillMaxSize()) {
        backdrop?.invoke()
        CompositionLocalProvider(
            LocalWallpaperBackdrop provides backdrop,
            LocalMaaStyleTokens provides style,
        ) {
            ProvideColorScheme(scheme, content)
        }
    }
}

/**
 * 二级页面的底：有背景时补一层同样的背景盖住下面的主 tab，没有时透传
 *
 * 背景层钉成整窗大小、贴左上：二级页那层挂着 imePadding，跟着它缩的话
 * 弹键盘时图片会按新高度重新裁切，与底下主界面那张对不上
 */
@Composable
fun WallpaperBackdrop(content: @Composable () -> Unit) {
    val backdrop = LocalWallpaperBackdrop.current
    if (backdrop == null) {
        content()
        return
    }
    val window = LocalWindowInfo.current.containerSize
    val windowSize = with(LocalDensity.current) { DpSize(window.width.toDp(), window.height.toDp()) }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .size(windowSize),
        ) {
            backdrop()
        }
        content()
    }
}

/** 不透明底 → 背景图 → 遮罩；先铺底色，图片不透明度调低时透出的是主题底色而不是窗口背景 */
@Composable
private fun WallpaperLayer(
    image: ImageBitmap,
    settings: WallpaperSettings,
    baseColor: Color,
    parallax: (() -> Float)?,
) {
    val parallaxLayer = parallax?.let { read ->
        Modifier.graphicsLayer {
            scaleX = PARALLAX_ZOOM
            scaleY = PARALLAX_ZOOM
            translationX = read().coerceIn(-1f, 1f) * size.width * PARALLAX_SHIFT
        }
    } ?: Modifier
    val blur = MaxWallpaperBlur * (settings.blur / 100f)
    Box(
        Modifier
            .fillMaxSize()
            .background(baseColor),
    ) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = settings.imageAlpha / 100f,
            modifier = Modifier
                .matchParentSize()
                .then(parallaxLayer)
                .then(if (blur > 0.dp) Modifier.blur(blur) else Modifier),
        )
        if (settings.scrim > 0) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(baseColor.copy(alpha = settings.scrim / 100f)),
            )
        }
    }
}

/** 系统关了动画（开发者选项把动画时长缩放调成 0）时不做视差 */
@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
