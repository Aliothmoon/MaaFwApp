package com.aliothmoon.maafw.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.runner.RunTaskSelection
import com.aliothmoon.maafw.runner.RunTaskSelectionMode
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaModalSheet
import com.aliothmoon.maafw.ui.components.MaaSheetHeader
import com.aliothmoon.maafw.ui.components.maaClickable

internal data class TaskRunActionTarget(
    val configurationId: RunConfigurationId,
    val instanceId: String,
    val label: String,
)

@Composable
internal fun TaskRunActionsSheet(
    target: TaskRunActionTarget?,
    onDismiss: () -> Unit,
    onRun: (RunTaskSelectionMode) -> Unit,
) {
    if (target == null) return
    MaaModalSheet(onDismiss = onDismiss) { sheetModifier ->
        Column(
            modifier = sheetModifier,
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
        ) {
            MaaSheetHeader(
                title = stringResource(R.string.task_run_actions_title, target.label),
                onClose = onDismiss,
            )
            RunAction(
                icon = Icons.Outlined.PlayArrow,
                label = stringResource(R.string.task_run_current),
                onClick = { onRun(RunTaskSelectionMode.Current) },
            )
            RunAction(
                icon = Icons.Outlined.FastForward,
                label = stringResource(R.string.task_run_current_and_following),
                onClick = { onRun(RunTaskSelectionMode.CurrentAndFollowing) },
            )
        }
    }
}

@Composable
private fun RunAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .maaClickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(MaaDesignTokens.Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(MaaDesignTokens.IconSize.md))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
