package com.aliothmoon.maafw.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme

private const val SheetEnterMs = 100
private const val SheetScrimAlpha = 0.4f

/**
 * 统一的 modal sheet 脚手架：固定 3/4 高度、跳过半展开、禁手势拖拽（无把手），
 * 关闭走遮罩点击 / 返回键 / 标题栏按钮
 *
 * 不用 M3 ModalBottomSheet：它另开窗口 + 不可调的 leisurely 入场弹簧，观感偏慢。
 * 这里自己挂 Dialog（Compose 已把窗口背景设透明，不双遮罩），入场用 tween(100ms)，
 * 进度只驱动 drawBehind / graphicsLayer（延迟读），整段动画不触发重组；
 * 关闭整棵被调用方的 if() 摘掉，瞬时消失
 *
 * content 收到的 Modifier 已带高度/水平边距/顶部间距/导航栏与输入法 inset
 */
@Composable
fun MaaModalSheet(
    onDismiss: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val screenHeightPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    val sheetHeightPx = screenHeightPx * MaaDesignTokens.Sheet.heightFraction

    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(SheetEnterMs, easing = FastOutSlowInEasing)) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            // 遮罩：alpha 读在 drawBehind，只重绘不重组；点空白处关
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind { drawRect(Color.Black, alpha = SheetScrimAlpha * progress.value) }
                    .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
            )
            // 抽屉：translationY 读在 graphicsLayer，只重铺层不重组
            // 顶角走风格 large（DEFAULT 12 / Semi modal 12；不用静态 pill，否则 Semi 主题顶角过圆）
            val topRadius = MaaTheme.style.radii.large
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(MaaDesignTokens.Sheet.heightFraction)
                    .graphicsLayer { translationY = (1f - progress.value) * sheetHeightPx },
                shape = RoundedCornerShape(topStart = topRadius, topEnd = topRadius),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                content(
                    Modifier
                        .fillMaxSize()
                        // 另开的窗口，主窗口根部那层空白失焦的命中树够不到这里
                        .clearFocusOnBlankTap()
                        .padding(horizontal = MaaDesignTokens.Spacing.lg)
                        .padding(top = MaaDesignTokens.Spacing.sm)
                        .navigationBarsPadding()
                        .imePadding(),
                )
            }
        }
    }
}

/** 无拖拽把手 sheet 的统一标题栏：可选返回键 + 标题 + 关闭钮（M3 默认 ≥48dp 触控） */
@Composable
fun MaaSheetHeader(
    title: String,
    onClose: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.common_close))
        }
    }
}
