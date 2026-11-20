package com.aliothmoon.maafw.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.schedule.ScheduleAppBlacklistViewModel
import com.aliothmoon.maafw.schedule.ScheduleAppBlacklistViewModel.UiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.ITextField
import com.aliothmoon.maafw.ui.components.MaaAppListItem
import com.aliothmoon.maafw.ui.components.MaaCheckbox
import com.aliothmoon.maafw.ui.components.MaaPressIndication
import org.koin.androidx.compose.koinViewModel

/**
 * 应用黑名单（定时页右上角进入，移植自 MaaMeow 的 ScheduleAppBlacklistView）
 *
 * 只对后台模式的定时触发生效：到点时主屏上开着勾选的应用，跳过这一轮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleAppBlacklistScreen(
    onBack: () -> Unit,
    viewModel: ScheduleAppBlacklistViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val runMode by viewModel.runMode.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val rowShape = RoundedCornerShape(MaaDesignTokens.CornerRadius.inner)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = { Text(stringResource(R.string.schedule_app_blacklist_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                horizontal = MaaDesignTokens.Spacing.lg,
                vertical = MaaDesignTokens.Spacing.sm,
            ),
        ) {
            item(key = "desc") {
                Text(
                    text = stringResource(R.string.schedule_app_blacklist_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = MaaDesignTokens.Spacing.sm),
                )
                if (runMode == RunMode.FOREGROUND) {
                    Text(
                        text = stringResource(R.string.schedule_app_blacklist_foreground_inactive),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(bottom = MaaDesignTokens.Spacing.sm),
                    )
                }
            }

            when (val current = state) {
                UiState.Loading -> item(key = "loading") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MaaDesignTokens.Spacing.md),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(MaaDesignTokens.IconSize.md))
                        Spacer(Modifier.width(MaaDesignTokens.Spacing.md))
                        Text(
                            text = stringResource(R.string.common_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                UiState.Failed -> item(key = "failed") {
                    Text(
                        text = stringResource(R.string.schedule_app_blacklist_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                is UiState.Loaded -> {
                    item(key = "search") {
                        ITextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = stringResource(R.string.schedule_app_blacklist_search),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(R.string.schedule_app_blacklist_selected, current.checkedCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                top = MaaDesignTokens.Spacing.md,
                                bottom = MaaDesignTokens.Spacing.xs,
                            ),
                        )
                    }
                    val keyword = query.trim()
                    val rows = current.rows.filter {
                        keyword.isEmpty() ||
                            it.label.contains(keyword, ignoreCase = true) ||
                            it.packageName.contains(keyword, ignoreCase = true)
                    }
                    if (rows.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text = stringResource(R.string.schedule_app_blacklist_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = MaaDesignTokens.Spacing.md),
                            )
                        }
                    } else {
                        items(rows, key = { it.packageName }) { row ->
                            MaaAppListItem(
                                label = row.label,
                                packageName = row.packageName,
                                modifier = Modifier
                                    .clip(rowShape)
                                    .toggleable(
                                        value = row.checked,
                                        role = Role.Checkbox,
                                        interactionSource = null,
                                        indication = MaaPressIndication(rowShape),
                                    ) { viewModel.setBlacklisted(row.packageName, it) },
                                trailing = { MaaCheckbox(checked = row.checked, onCheckedChange = null) },
                            )
                        }
                    }
                }
            }
        }
    }
}
