package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import com.aliothmoon.maafw.theme.MaaDesignTokens

/** PI 正文（welcome / contact / license）共用：正文可能有几十 KB，一律滚动而不是塞进卡里 */
@Composable
fun MaaMarkdownSheet(
    title: String,
    body: String?,
    onDismiss: () -> Unit,
) {
    MaaMarkdownSheet(title, body?.let(::listOf), onDismiss)
}

@Composable
fun MaaMarkdownSheet(
    title: String,
    bodies: List<String>?,
    onDismiss: () -> Unit,
    minimumBrowseMs: Long? = null,
) {
    if (bodies.isNullOrEmpty()) return
    var canDismiss by remember(minimumBrowseMs) { mutableStateOf(minimumBrowseMs == null) }
    LaunchedEffect(minimumBrowseMs) {
        if (minimumBrowseMs == null) return@LaunchedEffect
        delay(minimumBrowseMs)
        canDismiss = true
    }

    MaaModalSheet(onDismiss = { if (canDismiss) onDismiss() }) { modifier ->
        Column(modifier) {
            MaaSheetHeader(
                title = title,
                onClose = { if (canDismiss) onDismiss() },
                closeEnabled = canDismiss,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = MaaDesignTokens.Spacing.lg),
            ) {
                bodies.forEachIndexed { index, body ->
                    MaaMarkdown(
                        text = body,
                        modifier = if (index == 0) {
                            Modifier
                        } else {
                            Modifier.padding(top = MaaDesignTokens.Spacing.md)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
