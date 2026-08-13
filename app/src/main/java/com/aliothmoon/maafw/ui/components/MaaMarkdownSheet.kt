package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    if (body == null) return
    MaaModalSheet(onDismiss = onDismiss) { modifier ->
        Column(modifier) {
            MaaSheetHeader(title = title, onClose = onDismiss)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = MaaDesignTokens.Spacing.lg),
            ) {
                MaaMarkdown(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
