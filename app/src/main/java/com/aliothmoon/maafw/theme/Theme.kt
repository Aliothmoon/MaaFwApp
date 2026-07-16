package com.aliothmoon.maafw.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
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

// 暖石色（stone）中性系，对齐 proto 原型观感
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

/** 徽章/状态用的语义配色对：content + container。 */
data class MaaTone(val content: Color, val container: Color)

/** M3 colorScheme 之外的语义扩展色（成功/警示/信息/强调），随主题明暗切换。 */
data class MaaPalette(
    val success: MaaTone,
    val warning: MaaTone,
    val info: MaaTone,
    val violet: MaaTone,
    val neutral: MaaTone,
) {
    /** 按稳定顺序取一组强调色，用于分组徽章、任务色条等。 */
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
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) BlueDark else BlueLight

    CompositionLocalProvider(
        LocalIndication provides NoIndication,
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
