package com.aliothmoon.maafw.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.ProjectAnnouncement
import com.aliothmoon.maafw.theme.MaaDesignTokens

/** 手机端公告中心：列表进入详情，详情返回列表，关闭始终退出整个 Sheet。 */
@Composable
fun AnnouncementCenterSheet(
    announcements: List<ProjectAnnouncement>,
    onDismiss: () -> Unit,
) {
    if (announcements.isEmpty()) return

    var selected by remember(announcements) { mutableStateOf<ProjectAnnouncement?>(null) }
    BackHandler(enabled = selected != null) { selected = null }

    MaaModalSheet(onDismiss = onDismiss) { modifier ->
        Column(modifier) {
            val current = selected
            MaaSheetHeader(
                title = if (current == null) {
                    stringResource(R.string.announcement_title)
                } else {
                    current.title.ifBlank { stringResource(R.string.announcement_title) }
                },
                onClose = onDismiss,
                onBack = current?.let { { selected = null } },
            )
            if (current == null) {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(announcements, key = ProjectAnnouncement::id) { announcement ->
                        MaaNavigationRow(
                            label = announcement.title.ifBlank {
                                stringResource(R.string.announcement_title)
                            },
                            onClick = { selected = announcement },
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = MaaDesignTokens.Spacing.lg),
                ) {
                    MaaMarkdown(
                        text = current.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
