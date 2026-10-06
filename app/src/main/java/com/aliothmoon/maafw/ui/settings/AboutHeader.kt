package com.aliothmoon.maafw.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaMarkdown
import com.aliothmoon.maafw.ui.components.MaaPiIcon

private val IconSize = 72.dp
private val IconShape = RoundedCornerShape(16.dp)

/**
 * 关于卡顶部的项目名片：图标、名称、简介居中，对齐 MXU 的关于页
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MaaDesignTokens.Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        val iconModifier = Modifier
            .shadow(6.dp, IconShape)
            .clip(IconShape)
        if (icon != null) {
            MaaPiIcon(icon, IconSize, contentDescription = name, modifier = iconModifier)
        } else {
            val context = LocalContext.current
            val bitmap = remember(context) {
                context.applicationInfo.loadIcon(context.packageManager).toBitmap().asImageBitmap()
            }
            Image(bitmap = bitmap, contentDescription = name, modifier = iconModifier.size(IconSize))
        }
        Text(
            text = name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = MaaDesignTokens.Spacing.xs),
        )
        description?.takeIf(String::isNotBlank)?.let {
            MaaMarkdown(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
