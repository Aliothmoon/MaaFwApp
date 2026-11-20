package com.aliothmoon.maafw.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaMarkdown
import com.aliothmoon.maafw.ui.components.MaaPiIcon

/** 对齐 MaaMeow `SettingAboutHeader` 的 56dp 左侧图标尺寸 */
private val AboutIconSize = 56.dp

/**
 * 关于卡顶部的项目名片：左侧图标 + 右侧名称与简介，对齐 MaaMeow 的 SettingAboutHeader
 *
 * 图标取 PI 根上的 `icon`；PI 没写时用应用图标（同样来自 profile）
 */
@Composable
internal fun AboutHeader(
    name: String,
    icon: String?,
    description: String?,
    modifier: Modifier = Modifier,
) {
    val iconShape = RoundedCornerShape(MaaTheme.style.radii.large)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MaaDesignTokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.lg),
    ) {
        val iconModifier = Modifier
            .shadow(MaaTheme.style.cardElevation, iconShape)
            .clip(iconShape)
        if (icon != null) {
            MaaPiIcon(icon, AboutIconSize, contentDescription = name, modifier = iconModifier)
        } else {
            val context = LocalContext.current
            val bitmap = remember(context) {
                context.applicationInfo.loadIcon(context.packageManager).toBitmap().asImageBitmap()
            }
            Image(bitmap = bitmap, contentDescription = name, modifier = iconModifier.size(AboutIconSize))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            description?.takeIf(String::isNotBlank)?.let {
                MaaMarkdown(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
