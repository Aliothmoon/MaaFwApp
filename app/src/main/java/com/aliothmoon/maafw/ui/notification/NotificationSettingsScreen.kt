package com.aliothmoon.maafw.ui.notification

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.EventNotificationLevel
import com.aliothmoon.maafw.i18n.resolve
import com.aliothmoon.maafw.notification.NOTIFICATION_PROVIDER_ORDER
import com.aliothmoon.maafw.notification.NotificationEffect
import com.aliothmoon.maafw.notification.NotificationIntent
import com.aliothmoon.maafw.notification.NotificationSettings
import com.aliothmoon.maafw.notification.NotificationSettingsViewModel
import com.aliothmoon.maafw.notification.NotificationUiState
import com.aliothmoon.maafw.notification.toPrefBoolean
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.ITextField
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaSwitch
import com.aliothmoon.maafw.ui.components.MaaSwitchRow
import org.koin.androidx.compose.koinViewModel

/**
 * 通知设置（二级页面）
 *
 * 三段：内部系统通知的档位、外部推送的触发条件、逐个渠道的凭据
 *
 * 自带 SnackbarHost 而不是把反馈甩给 `AppRoot`：测试发送的逐条结果只在这一页有意义，
 * 走 `SessionEffect` 会让用户离开页面后还在收上一页的提示
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    onBack: () -> Unit,
    viewModel: NotificationSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is NotificationEffect.ShowMessage ->
                    snackbarHostState.showSnackbar(effect.message.resolve(context))
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                // AppRoot 的 Scaffold 已吃掉状态栏顶部 inset，这里不能再加一次
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.notification_settings_title)) },
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
            contentPadding = PaddingValues(MaaDesignTokens.Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.lg),
        ) {
            item(key = "internal") { InternalCard(state, viewModel::onIntent) }
            item(key = "triggers") { TriggerCard(state, viewModel::onIntent) }
            items(NOTIFICATION_PROVIDER_ORDER, key = { it }) { id ->
                ProviderCard(id, state, viewModel::onIntent)
            }
            item(key = "test") { ExternalTestCard(state, viewModel::onIntent) }
        }
    }
}

@Composable
private fun InternalCard(state: NotificationUiState, onIntent: (NotificationIntent) -> Unit) {
    val enabled = state.eventLevel != EventNotificationLevel.OFF
    MaaCard(title = stringResource(R.string.notification_section_internal)) {
        // 三档枚举摊成两个开关：关掉整档与「要不要弹横幅」是两个决定，
        // 摆成三选一会让「关」和「静默」看着像同一列的两种提醒方式
        MaaSwitchRow(
            label = stringResource(R.string.notification_enable),
            checked = enabled,
            onCheckedChange = {
                onIntent(
                    NotificationIntent.SetEventLevel(
                        if (it) EventNotificationLevel.DEFAULT else EventNotificationLevel.OFF,
                    ),
                )
            },
        )
        AnimatedVisibility(visible = enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                MaaSwitchRow(
                    label = stringResource(R.string.notification_popup),
                    checked = state.eventLevel == EventNotificationLevel.HIGH,
                    onCheckedChange = {
                        onIntent(
                            NotificationIntent.SetEventLevel(
                                if (it) EventNotificationLevel.HIGH else EventNotificationLevel.DEFAULT,
                            ),
                        )
                    },
                )
                val title = stringResource(R.string.notification_test_title)
                val body = stringResource(R.string.notification_test_message)
                Button(
                    onClick = { onIntent(NotificationIntent.SendInternalTest(title, body)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.notification_send_test))
                }
            }
        }
    }
}

@Composable
private fun TriggerCard(state: NotificationUiState, onIntent: (NotificationIntent) -> Unit) {
    MaaCard(title = stringResource(R.string.notification_section_external)) {
        MaaSwitchRow(
            label = stringResource(R.string.notification_send_on_complete),
            checked = state.settings.sendOnComplete.toPrefBoolean(default = true),
            onCheckedChange = { value -> onIntent(NotificationIntent.UpdateSettings { copy(sendOnComplete = value.toString()) }) },
        )
        MaaSwitchRow(
            label = stringResource(R.string.notification_send_on_error),
            checked = state.settings.sendOnError.toPrefBoolean(default = true),
            onCheckedChange = { value -> onIntent(NotificationIntent.UpdateSettings { copy(sendOnError = value.toString()) }) },
        )
        MaaSwitchRow(
            label = stringResource(R.string.notification_send_on_service_died),
            checked = state.settings.sendOnServiceDied.toPrefBoolean(),
            onCheckedChange = { value -> onIntent(NotificationIntent.UpdateSettings { copy(sendOnServiceDied = value.toString()) }) },
        )
        MaaSwitchRow(
            label = stringResource(R.string.notification_include_log_details),
            checked = state.settings.includeLogDetails.toPrefBoolean(),
            onCheckedChange = { value -> onIntent(NotificationIntent.UpdateSettings { copy(includeLogDetails = value.toString()) }) },
        )
    }
}

@Composable
private fun ExternalTestCard(state: NotificationUiState, onIntent: (NotificationIntent) -> Unit) {
    val title = stringResource(R.string.notification_test_title)
    val body = stringResource(R.string.notification_test_message)
    MaaCard(title = stringResource(R.string.notification_section_test)) {
        Button(
            onClick = { onIntent(NotificationIntent.SendExternalTest(title, body)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.enabledProviders.isNotEmpty(),
        ) {
            Text(stringResource(R.string.notification_send_test))
        }
    }
}

@Composable
private fun ProviderCard(
    id: String,
    state: NotificationUiState,
    onIntent: (NotificationIntent) -> Unit,
) {
    val enabled = id in state.enabledProviders
    MaaCard(
        title = stringResource(providerNameRes(id)),
        trailing = {
            MaaSwitch(
                checked = enabled,
                onCheckedChange = { onIntent(NotificationIntent.ToggleProvider(id, it)) },
            )
        },
    ) {
        AnimatedVisibility(visible = enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                ProviderConfig(id, state.settings, onIntent)
            }
        }
    }
}

@Composable
private fun ProviderConfig(
    id: String,
    settings: NotificationSettings,
    onIntent: (NotificationIntent) -> Unit,
) {
    when (id) {
        "ServerChan" -> Field(
            value = settings.serverChanSendKey,
            label = stringResource(R.string.notification_label_send_key),
            placeholder = "SCT…",
            onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(serverChanSendKey = v) }) },
        )

        "Telegram" -> {
            Field(
                value = settings.telegramBotToken,
                label = stringResource(R.string.notification_label_bot_token),
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(telegramBotToken = v) }) },
            )
            Field(
                value = settings.telegramChatId,
                label = stringResource(R.string.notification_label_chat_id),
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(telegramChatId = v) }) },
            )
            Field(
                value = settings.telegramTopicId,
                label = stringResource(R.string.notification_label_topic_id),
                placeholder = stringResource(R.string.notification_placeholder_optional),
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(telegramTopicId = v) }) },
            )
        }

        "Discord" -> {
            Field(
                value = settings.discordBotToken,
                label = stringResource(R.string.notification_label_bot_token),
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(discordBotToken = v) }) },
            )
            Field(
                value = settings.discordUserId,
                label = stringResource(R.string.notification_label_user_id),
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(discordUserId = v) }) },
            )
        }

        "DingTalk" -> {
            Field(
                value = settings.dingTalkAccessToken,
                label = stringResource(R.string.notification_label_access_token),
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(dingTalkAccessToken = v) }) },
            )
            Field(
                value = settings.dingTalkSecret,
                label = stringResource(R.string.notification_label_secret),
                placeholder = stringResource(R.string.notification_placeholder_optional_signing_secret),
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(dingTalkSecret = v) }) },
            )
        }

        "KOOK" -> {
            Field(
                value = settings.kookBotToken,
                label = stringResource(R.string.notification_label_bot_token),
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(kookBotToken = v) }) },
            )
            Field(
                value = settings.kookTargetId,
                label = stringResource(R.string.notification_label_kook_target_id),
                placeholder = stringResource(R.string.notification_placeholder_kook_target_id),
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(kookTargetId = v) }) },
            )
            MaaSwitchRow(
                label = stringResource(R.string.notification_kook_direct_message),
                checked = settings.kookDirectMessage.toPrefBoolean(),
                onCheckedChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(kookDirectMessage = v.toString()) }) },
            )
        }

        "Discord Webhook" -> Field(
            value = settings.discordWebhookUrl,
            label = stringResource(R.string.notification_label_webhook_url),
            placeholder = "https://discord.com/api/webhooks/…",
            onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(discordWebhookUrl = v) }) },
        )

        "SMTP" -> {
            Field(
                value = settings.smtpServer,
                label = stringResource(R.string.notification_label_smtp_server),
                placeholder = "smtp.example.com",
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(smtpServer = v) }) },
            )
            Field(
                value = settings.smtpPort,
                label = stringResource(R.string.notification_label_smtp_port),
                placeholder = "465",
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(smtpPort = v) }) },
            )
            MaaSwitchRow(
                label = stringResource(R.string.notification_use_ssl),
                checked = settings.smtpUseSsl.toPrefBoolean(),
                onCheckedChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(smtpUseSsl = v.toString()) }) },
            )
            MaaSwitchRow(
                label = stringResource(R.string.notification_requires_auth),
                checked = settings.smtpRequireAuthentication.toPrefBoolean(),
                onCheckedChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(smtpRequireAuthentication = v.toString()) }) },
            )
            Field(
                value = settings.smtpUser,
                label = stringResource(R.string.notification_label_smtp_user),
                placeholder = stringResource(R.string.notification_placeholder_optional_required_when_auth),
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(smtpUser = v) }) },
            )
            Field(
                value = settings.smtpPassword,
                label = stringResource(R.string.notification_label_smtp_password),
                placeholder = stringResource(R.string.notification_placeholder_optional_required_when_auth),
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(smtpPassword = v) }) },
            )
            Field(
                value = settings.smtpFrom,
                label = stringResource(R.string.notification_label_from),
                placeholder = "sender@example.com",
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(smtpFrom = v) }) },
            )
            Field(
                value = settings.smtpTo,
                label = stringResource(R.string.notification_label_to),
                placeholder = "receiver@example.com",
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(smtpTo = v) }) },
            )
        }

        "Bark" -> {
            Field(
                value = settings.barkServer,
                label = stringResource(R.string.notification_label_server_url),
                placeholder = "https://api.day.app",
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(barkServer = v) }) },
            )
            Field(
                value = settings.barkSendKey,
                label = stringResource(R.string.notification_label_bark_send_key),
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(barkSendKey = v) }) },
            )
        }

        "Qmsg" -> {
            Field(
                value = settings.qmsgServer,
                label = stringResource(R.string.notification_label_qmsg_server),
                placeholder = "https://qmsg.zendee.cn",
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(qmsgServer = v) }) },
            )
            Field(
                value = settings.qmsgKey,
                label = stringResource(R.string.notification_label_qmsg_key),
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(qmsgKey = v) }) },
            )
            Field(
                value = settings.qmsgUser,
                label = stringResource(R.string.notification_label_user_qq),
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(qmsgUser = v) }) },
            )
            Field(
                value = settings.qmsgBot,
                label = stringResource(R.string.notification_label_bot_qq),
                placeholder = stringResource(R.string.notification_placeholder_optional),
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(qmsgBot = v) }) },
            )
        }

        "Gotify" -> {
            Field(
                value = settings.gotifyServer,
                label = stringResource(R.string.notification_label_server_url),
                placeholder = "https://gotify.example.com",
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(gotifyServer = v) }) },
            )
            Field(
                value = settings.gotifyToken,
                label = stringResource(R.string.notification_label_application_token),
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(gotifyToken = v) }) },
            )
        }

        "CustomWebhook" -> {
            Field(
                value = settings.customWebhookUrl,
                label = stringResource(R.string.notification_label_webhook_url),
                placeholder = "https://…",
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(customWebhookUrl = v) }) },
            )
            Field(
                value = settings.customWebhookHeaders,
                label = stringResource(R.string.notification_label_webhook_headers),
                placeholder = "Content-Type: application/json",
                singleLine = false,
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(customWebhookHeaders = v) }) },
            )
            Field(
                value = settings.customWebhookBody,
                label = stringResource(R.string.notification_label_request_body_template),
                placeholder = """{"title":"{title}","content":"{content}"}""",
                singleLine = false,
                onValueChange = { v -> onIntent(NotificationIntent.UpdateSettings { copy(customWebhookBody = v) }) },
            )
            Text(
                text = stringResource(R.string.notification_supported_placeholders),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Field(
    value: String,
    label: String,
    placeholder: String = "",
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    ITextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
    )
}

/** 渠道名不进 [NOTIFICATION_PROVIDER_ORDER]：那份是持久化的 id，这里是展示用的译名 */
private fun providerNameRes(id: String): Int = when (id) {
    "ServerChan" -> R.string.notification_provider_server_chan
    "Telegram" -> R.string.notification_provider_telegram
    "Discord" -> R.string.notification_provider_discord
    "DingTalk" -> R.string.notification_provider_ding_talk
    "KOOK" -> R.string.notification_provider_kook
    "Discord Webhook" -> R.string.notification_provider_discord_webhook
    "SMTP" -> R.string.notification_provider_smtp
    "Bark" -> R.string.notification_provider_bark
    "Qmsg" -> R.string.notification_provider_qmsg
    "Gotify" -> R.string.notification_provider_gotify
    else -> R.string.notification_provider_custom_webhook
}
