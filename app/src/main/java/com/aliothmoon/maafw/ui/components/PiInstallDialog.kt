package com.aliothmoon.maafw.ui.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.project.PiInstallState
import com.aliothmoon.maafw.theme.MaaDesignTokens

/**
 * PI 解包的阻塞弹窗
 *
 * 只有解包与失败两档渲染：检查一闪而过，未检查与就绪都不该挡着人
 * 关掉失败弹窗不等于解决了问题，重来的入口在设置页
 */
@Composable
fun PiInstallDialog(
    state: PiInstallState,
    onRetry: () -> Unit,
) {
    var dismissed by remember { mutableStateOf(false) }
    // 重试会先离开 Failed，这一步把上次的关闭意图清掉，否则再失败时弹窗不再出现
    LaunchedEffect(state) {
        if (state !is PiInstallState.Failed) dismissed = false
    }

    // 解包快到看不清时进度框一闪而过，跟闪屏没区别；显示过就补满 MIN_VISIBLE_MS
    // 只拖显示，不拖 PiInstallCoordinator——那边一拖，projectRepository.reload() 跟着晚
    var lingering by remember { mutableStateOf<PiInstallState.Unpacking?>(null) }
    var shownAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state) {
        when {
            state is PiInstallState.Unpacking -> {
                if (lingering == null) shownAt = SystemClock.uptimeMillis()
                lingering = state
            }

            lingering == null -> Unit

            // 失败要立刻让位：进度停在半路还杵着，比直接报错更让人摸不着头脑
            state is PiInstallState.Failed -> lingering = null

            else -> {
                lingering = lingering?.let { it.copy(done = it.total, currentPath = "") }
                val remaining = MIN_VISIBLE_MS - (SystemClock.uptimeMillis() - shownAt)
                if (remaining > 0) delay(remaining)
                lingering = null
            }
        }
    }

    val progress = state as? PiInstallState.Unpacking ?: lingering
    when {
        progress != null -> UnpackingDialog(progress)

        state is PiInstallState.Failed -> if (!dismissed) {
            MaaPromptDialog(
                title = stringResource(R.string.pi_install_failed_title),
                message = stringResource(R.string.pi_install_failed_message, state.reason.asString()),
                icon = Icons.Rounded.Warning,
                confirmText = stringResource(R.string.pi_install_retry),
                onConfirm = onRetry,
                onDismissRequest = { dismissed = true },
                dismissText = stringResource(R.string.dialog_cancel),
            )
        }

        else -> Unit
    }
}

/** 进度框的最短显示时长：低于这个数就只剩一下闪动，看不出发生了什么 */
private const val MIN_VISIBLE_MS = 2_000L

/** 解包期间不给任何出口：中途退出留下的是半份内容，下次启动照样得重解 */
@Composable
private fun UnpackingDialog(state: PiInstallState.Unpacking) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = MaaDesignTokens.Spacing.xs,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MaaDesignTokens.Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.pi_install_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MaaDesignTokens.Spacing.lg),
                )
                Text(
                    text = stringResource(
                        R.string.pi_install_progress,
                        state.done,
                        state.total,
                        state.percent,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MaaDesignTokens.Spacing.sm),
                )
                Text(
                    text = state.currentPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.padding(top = MaaDesignTokens.Spacing.xxs),
                )
                Text(
                    text = stringResource(R.string.pi_install_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = MaaDesignTokens.Spacing.md),
                )
            }
        }
    }
}
