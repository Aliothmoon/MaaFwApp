package com.aliothmoon.maafw.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope

// 暖石色（stone）中性系，对齐早期原型 UI 观感
private val LightBackground = Color(0xFFFAF9F6)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFF5F5F4)
private val LightOnSurface = Color(0xFF1C1917)
private val LightOnSurfaceVariant = Color(0xFF78716C)
private val LightOutline = Color(0xFFE7E5E4)

private val DarkBackground = Color(0xFF121212)
private val DarkSurface = Color(0xFF1C1C1E)
private val DarkSurfaceVariant = Color(0xFF2C2C2E)
private val DarkOnSurface = Color(0xFFFFFFFF)
private val DarkOnSurfaceVariant = Color(0xFF98989D)
private val DarkOutline = Color(0xFF3A3A3C)

private fun createLightColorScheme(
    primary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color
): ColorScheme = lightColorScheme(
    primary = primary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = Color(0xFFA8A29E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF5F5F4),
    onSecondaryContainer = Color(0xFF1C1917),
    tertiary = primary.copy(alpha = 0.8f),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = primaryContainer.copy(alpha = 0.5f),
    onTertiaryContainer = onPrimaryContainer,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    // M3 baseline 的 surfaceContainer* 带紫调，会与暖石色盘冲突（sheet/menu 底色）；
    // sheet 底（surfaceContainerLow）取 background 同源暖灰，衬白卡片建立层级
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = LightBackground,
    surfaceContainer = Color(0xFFFAF9F6),
    surfaceContainerHigh = Color(0xFFF5F5F4),
    surfaceContainerHighest = Color(0xFFE7E5E4),
    outline = LightOutline,
    outlineVariant = LightSurfaceVariant,
    error = Color(0xfff53f3f),
    onError = Color.White,
    errorContainer = Color(0xFFFFD8D6),
    onErrorContainer = Color(0xFF690005)
)

private fun createDarkColorScheme(
    primary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color
): ColorScheme = darkColorScheme(
    primary = primary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = Color(0xFF98989D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFFE5E5EA),
    tertiary = primary.copy(alpha = 0.8f),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = primaryContainer.copy(alpha = 0.5f),
    onTertiaryContainer = onPrimaryContainer,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = Color(0xFF0F0F0F),
    surfaceContainerLow = DarkBackground,
    surfaceContainer = Color(0xFF232325),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C),
    outline = DarkOutline,
    outlineVariant = DarkSurfaceVariant,
    error = Color(0xFFFF453A),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val BlueLight = createLightColorScheme(
    primary = Color(0xFF2563EB),
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A)
)

private val BlueDark = createDarkColorScheme(
    primary = Color(0xFF3B82F6),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE)
)
/** 可选主题风格；DEFAULT 是暖石色 + 蓝，SEMI_DESIGN 取 Semi Design 的冷灰 + 品牌蓝 */
enum class ThemeStyle { DEFAULT, SEMI_DESIGN }

// Semi Design 默认主题配色；色值取自 @douyinfe/semi-theme-default 的 _palette.scss / global.scss
// （primary=blue-5 #0064FA，中性 grey 冷灰，暗色 palette 反转）
private val SemiLight = lightColorScheme(
    primary = Color(0xFF0064FA),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEAF5FF),
    onPrimaryContainer = Color(0xFF004FB3),
    secondary = Color(0xFF6B7075),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF9F9F9),
    onSecondaryContainer = Color(0xFF1C1F23),
    tertiary = Color(0xFF0064FA),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEAF5FF),
    onTertiaryContainer = Color(0xFF004FB3),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1C1F23),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1F23),
    surfaceVariant = Color(0xFFF9F9F9),
    onSurfaceVariant = Color(0xFF555B61),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F6F6),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF9F9F9),
    surfaceContainerHighest = Color(0xFFE6E8EA),
    outline = Color(0xFFC6CACD),
    outlineVariant = Color(0xFFE6E8EA),
    error = Color(0xFFF93920),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEDDD2),
    onErrorContainer = Color(0xFF6A0103),
)

private val SemiDark = darkColorScheme(
    primary = Color(0xFF54A9FF),
    onPrimary = Color(0xFF053170),
    primaryContainer = Color(0xFF053170),
    onPrimaryContainer = Color(0xFF54A9FF),
    secondary = Color(0xFFA7ABB0),
    onSecondary = Color(0xFF1C1F23),
    secondaryContainer = Color(0xFF2E3238),
    onSecondaryContainer = Color(0xFFF9F9F9),
    tertiary = Color(0xFF54A9FF),
    onTertiary = Color(0xFF053170),
    tertiaryContainer = Color(0xFF053170),
    onTertiaryContainer = Color(0xFF54A9FF),
    background = Color(0xFF16161A),
    onBackground = Color(0xFFF9F9F9),
    surface = Color(0xFF16161A),
    onSurface = Color(0xFFF9F9F9),
    surfaceVariant = Color(0xFF2E3238),
    onSurfaceVariant = Color(0xFFA7ABB0),
    surfaceContainerLowest = Color(0xFF0F0F12),
    surfaceContainerLow = Color(0xFF16161A),
    surfaceContainer = Color(0xFF232429),
    surfaceContainerHigh = Color(0xFF35363C),
    surfaceContainerHighest = Color(0xFF43444A),
    outline = Color(0xFF41464C),
    outlineVariant = Color(0xFF2E3238),
    error = Color(0xFFFC725A),
    onError = Color(0xFF6A0103),
    errorContainer = Color(0xFF901110),
    onErrorContainer = Color(0xFFFDBEAC),
)

/** 徽章/状态用的语义配色对：content + container */
data class MaaTone(val content: Color, val container: Color)

/** M3 colorScheme 之外的语义扩展色（成功/警示/信息/强调），随主题明暗切换 */
data class MaaPalette(
    val success: MaaTone,
    val warning: MaaTone,
    val info: MaaTone,
    val violet: MaaTone,
    val neutral: MaaTone,
) {
    /** 按稳定顺序取一组强调色，用于分组徽章、任务色条等 */
    val accents: List<MaaTone> get() = listOf(info, violet, warning, success)
}

private val LightMaaPalette = MaaPalette(
    success = MaaTone(Color(0xFF059669), Color(0xFFD1FAE5)),
    warning = MaaTone(Color(0xFFB45309), Color(0xFFFEF3C7)),
    info = MaaTone(Color(0xFF0284C7), Color(0xFFE0F2FE)),
    violet = MaaTone(Color(0xFF7C3AED), Color(0xFFEDE9FE)),
    neutral = MaaTone(Color(0xFF78716C), Color(0xFFF5F5F4)),
)

private val DarkMaaPalette = MaaPalette(
    success = MaaTone(Color(0xFF34D399), Color(0xFF064E3B)),
    warning = MaaTone(Color(0xFFFBBF24), Color(0xFF78350F)),
    info = MaaTone(Color(0xFF38BDF8), Color(0xFF0C4A6E)),
    violet = MaaTone(Color(0xFFA78BFA), Color(0xFF4C1D95)),
    neutral = MaaTone(Color(0xFF98989D), Color(0xFF2C2C2E)),
)

val LocalMaaPalette = staticCompositionLocalOf { LightMaaPalette }

object MaaTheme {
    val palette: MaaPalette
        @Composable get() = LocalMaaPalette.current
}

val MaaShapes = Shapes(
    extraSmall = RoundedCornerShape(MaaDesignTokens.CornerRadius.inner),
    small = RoundedCornerShape(MaaDesignTokens.CornerRadius.button),
    medium = RoundedCornerShape(MaaDesignTokens.CornerRadius.card),
    large = RoundedCornerShape(MaaDesignTokens.CornerRadius.card),
    extraLarge = RoundedCornerShape(MaaDesignTokens.CornerRadius.pill)
)

private object NoIndication : IndicationNodeFactory {
    private class NoIndicationNode : Modifier.Node(), DrawModifierNode {
        override fun ContentDrawScope.draw() {
            drawContent()
        }
    }

    override fun create(interactionSource: InteractionSource): DelegatableNode {
        return NoIndicationNode()
    }

    override fun hashCode(): Int = -1
    override fun equals(other: Any?): Boolean = other === this
}

@Composable
fun MaaFwTheme(
    themeStyle: ThemeStyle = ThemeStyle.DEFAULT,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when (themeStyle) {
        ThemeStyle.DEFAULT -> if (darkTheme) BlueDark else BlueLight
        ThemeStyle.SEMI_DESIGN -> if (darkTheme) SemiDark else SemiLight
    }

    CompositionLocalProvider(
        // foundation 层 clickable 的指示效果
        LocalIndication provides NoIndication,
        // M3 组件内部 ripple（NavigationBarItem/Button 等不走 LocalIndication）
        LocalRippleConfiguration provides null,
        LocalMaaPalette provides if (darkTheme) DarkMaaPalette else LightMaaPalette,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = MaaShapes,
            content = content
        )
    }
}
