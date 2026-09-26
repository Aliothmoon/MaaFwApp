package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aliothmoon.maafw.theme.MaaDesignTokens

/** PI 正文（welcome / contact / license）共用：正文可能有几十 KB，一律滚动而不是塞进卡里 */
@Composable
fun MaaMarkdownSheet(
    title: String,
    body: String?,
    onDismiss: () -> Unit,
) {
    MaaMarkdownSheet(title = title, bodies = listOfNotNull(body), onDismiss = onDismiss)
}

/** 多段正文按序排在同一个 sheet 里，段间一条分隔线；标题由各段自己写在 Markdown 里 */
@Composable
fun MaaMarkdownSheet(
    title: String,
    bodies: List<String>,
    onDismiss: () -> Unit,
) {
    if (bodies.isEmpty()) return
    MaaModalSheet(onDismiss = onDismiss) { modifier ->
        Column(modifier) {
            MaaSheetHeader(title = title, onClose = onDismiss)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = MaaDesignTokens.Spacing.lg),
            ) {
                bodies.forEachIndexed { index, body ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = MaaDesignTokens.Spacing.lg),
                            thickness = MaaDesignTokens.Separator.thickness,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    MaaMarkdown(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
