package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.runner.FocusDialogController
import kotlinx.coroutines.launch

@Composable
fun FocusDialogHost(
    controller: FocusDialogController,
    onMessage: (String) -> Unit,
) {
    val requests by controller.requests.collectAsStateWithLifecycle()
    val request = requests.firstOrNull() ?: return
    val scope = rememberCoroutineScope()
    val dismissible = !request.modal
    val acknowledgeFailedMessage = stringResource(R.string.msg_modal_ack_failed)
    val stopFailedMessage = stringResource(R.string.msg_reject_stop_failed)

    AlertDialog(
        onDismissRequest = {
            if (dismissible) controller.confirmDialog(request.id)
        },
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
        ),
        title = {
            Text(
                text = stringResource(
                    if (request.modal) R.string.focus_modal_title else R.string.notification_focus_title,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                MaaMarkdown(
                    text = request.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        if (request.modal) {
                            if (!controller.confirmModal(request.id)) {
                                onMessage(acknowledgeFailedMessage)
                            }
                        } else {
                            controller.confirmDialog(request.id)
                        }
                    }
                },
            ) {
                Text(
                    text = stringResource(
                        if (request.modal) R.string.focus_modal_continue else R.string.dialog_ok,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        if (request.modal) {
                            if (!controller.stopModal(request.id)) {
                                onMessage(stopFailedMessage)
                            }
                        } else {
                            controller.dismissDialog(request.id)
                        }
                    }
                },
            ) {
                Text(
                    text = stringResource(
                        if (request.modal) R.string.runner_stop else R.string.common_close,
                    ),
                )
            }
        },
    )
}
