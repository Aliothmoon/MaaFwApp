package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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
