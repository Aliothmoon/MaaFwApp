package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/** PI 正文（welcome / contact / license）共用：正文可能有几十 KB，一律滚动而不是塞进卡里 */
@Composable
fun MaaMarkdownSheet(
    title: String,
    body: String?,
    onDismiss: () -> Unit,
) {
    MaaMarkdownSheet(title, bodies = body?.let(::listOf).orEmpty(), onDismiss = onDismiss)
}

/** 多段正文按序排在同一个 sheet 里，段间一条分隔线；标题由各段自己写在 Markdown 里 */
@Composable
fun MaaMarkdownSheet(
    title: String,
    bodies: List<String>,
    onDismiss: () -> Unit,
    minimumBrowseMs: Long? = null,
) {
    if (bodies.isEmpty()) return
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
            // WebView 自己滚动，外层不再套 verticalScroll
            MaaHtmlBody(
                bodies = bodies,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }
}
