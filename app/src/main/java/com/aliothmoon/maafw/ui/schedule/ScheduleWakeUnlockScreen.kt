package com.aliothmoon.maafw.ui.schedule

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.UnlockCredential
import com.aliothmoon.maafw.domain.UnlockGesture
import com.aliothmoon.maafw.domain.UnlockStep
import com.aliothmoon.maafw.i18n.resolve
import com.aliothmoon.maafw.schedule.ScheduleWakeUnlockViewModel
import com.aliothmoon.maafw.schedule.ScheduleWakeUnlockViewModel.GestureRecordState
import com.aliothmoon.maafw.schedule.ScheduleWakeUnlockViewModel.WakeTestState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.ITextFieldWithFocus
import com.aliothmoon.maafw.ui.components.MaaButton
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import com.aliothmoon.maafw.ui.components.MaaSingleChoiceFlow
import org.koin.androidx.compose.koinViewModel

/** 选项顺序即横滑方向：无密码 → 录制 → PIN，与 MaaMeow 一致 */
private val WAKE_TYPE_ORDER = listOf(
    UnlockCredential.TYPE_SWIPE,
    UnlockCredential.TYPE_GESTURE,
    UnlockCredential.TYPE_PIN,
)

/** PIN 再长注入也没意义，系统锁屏本身就封顶 16 位 */
private const val MAX_PIN_LENGTH = 16

/**
 * 唤醒解锁（定时页右上角进入，移植自 MaaMeow 的 ScheduleWakeUnlockView）
 *
 * 只对定时触发生效：到点时若已锁屏，按这里选的方式解开；手动 Start 不走这条
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleWakeUnlockScreen(
    onBack: () -> Unit,
    viewModel: ScheduleWakeUnlockViewModel = koinViewModel(),
) {
    val type by viewModel.wakeUnlockType.collectAsStateWithLifecycle()
    val credential by viewModel.wakeCredential.collectAsStateWithLifecycle()
    val testState by viewModel.wakeTestState.collectAsStateWithLifecycle()
    val gesture by viewModel.unlockGesture.collectAsStateWithLifecycle()
    val recordState by viewModel.gestureRecordState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(testState) {
        val done = testState as? WakeTestState.Done ?: return@LaunchedEffect
        Toast.makeText(context, done.result.message.resolve(context), Toast.LENGTH_LONG).show()
        viewModel.clearWakeTestResult()
    }

    LaunchedEffect(Unit) { viewModel.refreshGestureRecord() }

    val doneTemplate = stringResource(R.string.settings_wake_gesture_done)
    val saveFailedText = stringResource(R.string.settings_wake_gesture_save_failed)
    LaunchedEffect(recordState) {
        val message = when (val state = recordState) {
            is GestureRecordState.Done -> doneTemplate.format(state.steps)
            is GestureRecordState.Failed -> state.result.message.resolve(context)
            GestureRecordState.SaveFailed -> saveFailedText
            else -> return@LaunchedEffect
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        viewModel.clearGestureRecordState()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = { Text(stringResource(R.string.schedule_wake_unlock_title)) },
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
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
        ) {
            item(key = "desc") {
                Text(
                    text = stringResource(R.string.schedule_wake_unlock_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "type") {
                MaaCard(title = stringResource(R.string.settings_wake_unlock_type)) {
                    MaaSingleChoiceFlow(
                        options = listOf(
                            UnlockCredential.TYPE_SWIPE to stringResource(R.string.settings_wake_unlock_type_swipe),
                            UnlockCredential.TYPE_GESTURE to stringResource(R.string.settings_wake_unlock_type_gesture),
                            UnlockCredential.TYPE_PIN to stringResource(R.string.settings_wake_unlock_type_pin),
                        ),
                        selected = type,
                        onSelect = viewModel::setWakeUnlockType,
                    )
                    WakeUnlockTypeContent(type = type) { target ->
                        when (target) {
                            UnlockCredential.TYPE_PIN -> WakePinSection(
                                credential = credential,
                                onCredentialChange = viewModel::setWakeCredential,
                            )

                            UnlockCredential.TYPE_GESTURE -> WakeGestureSection(
                                gesture = gesture,
                                recordState = recordState,
                                onRecord = viewModel::startGestureRecord,
                                onCancelRecord = viewModel::cancelGestureRecord,
                                onClear = viewModel::clearGesture,
                            )

                            else -> Text(
                                text = stringResource(R.string.settings_wake_swipe_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            // 放在横滑内容之外，三种方式共用
            item(key = "test") {
                WakeTestCard(
                    type = type,
                    gestureRecorded = gesture != null,
                    busy = testState == WakeTestState.Testing || recordState.isRecording,
                    testing = testState == WakeTestState.Testing,
                    onTest = viewModel::runWakeTest,
                )
            }
        }
    }
}

/** 跟随选项的左右顺序横滑换内容 */
@Composable
private fun WakeUnlockTypeContent(type: String, content: @Composable (String) -> Unit) {
    AnimatedContent(
        targetState = type,
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds(),
        transitionSpec = {
            val forward = WAKE_TYPE_ORDER.indexOf(targetState) > WAKE_TYPE_ORDER.indexOf(initialState)
            val enter = slideInHorizontally { if (forward) it else -it }
            val exit = slideOutHorizontally { if (forward) -it else it }
            (enter togetherWith exit).using(SizeTransform(clip = false))
        },
        label = "wakeUnlockType",
    ) { target ->
        Column(modifier = Modifier.padding(top = MaaDesignTokens.Spacing.sm)) {
            content(target)
        }
    }
}

@Composable
private fun WakePinSection(credential: String, onCredentialChange: (String) -> Unit) {
    // 字段自己持有输入，落盘按键入节奏；外部值只作初值，避免回写抢光标
    var text by remember { mutableStateOf(credential) }
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs)) {
        ITextFieldWithFocus(
            value = text,
            onValueChange = {
                text = it
                onCredentialChange(it)
            },
            onFocusLost = {},
            label = stringResource(R.string.settings_wake_credential),
            placeholder = stringResource(R.string.settings_wake_credential_hint),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
            // 只收数字：注入按键只打得出 0-9，图案与字母密码的面板模拟不出来
            inputFilter = { it.length <= MAX_PIN_LENGTH && it.all(Char::isDigit) },
        )
        Text(
            text = stringResource(R.string.settings_wake_credential_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun WakeGestureSection(
    gesture: UnlockGesture?,
    recordState: GestureRecordState?,
    onRecord: () -> Unit,
    onCancelRecord: () -> Unit,
    onClear: () -> Unit,
) {
    val recording = recordState.isRecording
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
        if (gesture == null) {
            Text(
                text = stringResource(R.string.settings_wake_gesture_none),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            GestureSteps(gesture)
        }

        Text(
            text = stringResource(R.string.settings_wake_gesture_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (recording) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(
                        if (recordState is GestureRecordState.Preparing) {
                            R.string.settings_wake_gesture_preparing
                        } else {
                            R.string.settings_wake_gesture_waiting
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            MaaOutlinedButton(onClick = onCancelRecord, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_wake_gesture_cancel))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                MaaButton(onClick = onRecord, modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            if (gesture == null) {
                                R.string.settings_wake_gesture_record
                            } else {
                                R.string.settings_wake_gesture_rerecord
                            },
                        ),
                    )
                }
                if (gesture != null) {
                    MaaOutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_wake_gesture_clear))
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.settings_wake_gesture_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** 步骤只在需要核对时才看，默认收起 */
@Composable
private fun GestureSteps(gesture: UnlockGesture) {
    MaaCard(
        title = stringResource(
            R.string.settings_wake_gesture_summary,
            gesture.steps.size,
            gesture.screenWidth,
            gesture.screenHeight,
        ),
        collapsible = true,
        initiallyExpanded = false,
    ) {
        gesture.steps.forEachIndexed { index, step ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = gestureStepText(step), style = MaterialTheme.typography.bodyMedium)
                    if (step.delayBeforeMs > 0) {
                        Text(
                            text = stringResource(R.string.settings_wake_gesture_step_delay, step.delayBeforeMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun gestureStepText(step: UnlockStep): String = when (step) {
    is UnlockStep.Tap -> stringResource(R.string.settings_wake_gesture_step_tap, step.x, step.y)

    is UnlockStep.LongPress ->
        stringResource(R.string.settings_wake_gesture_step_long_press, step.x, step.y, step.holdMs)

    is UnlockStep.Swipe -> {
        val first = step.points.firstOrNull()
        val last = step.points.lastOrNull()
        stringResource(
            R.string.settings_wake_gesture_step_swipe,
            first?.x ?: 0,
            first?.y ?: 0,
            last?.x ?: 0,
            last?.y ?: 0,
            last?.tMs ?: 0,
        )
    }
}

@Composable
private fun WakeTestCard(
    type: String,
    gestureRecorded: Boolean,
    busy: Boolean,
    testing: Boolean,
    onTest: () -> Unit,
) {
    val isGesture = type == UnlockCredential.TYPE_GESTURE
    // 没录手势时凭证会退化成「无密码」，测出来的不是用户选的方式
    val missingGesture = isGesture && !gestureRecorded
    MaaCard(title = stringResource(R.string.settings_wake_test_button)) {
        Text(
            text = stringResource(
                when {
                    missingGesture -> R.string.wake_result_gesture_empty
                    isGesture -> R.string.settings_wake_gesture_test_hint
                    else -> R.string.settings_wake_test_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MaaButton(
            onClick = onTest,
            enabled = !missingGesture && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (testing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.settings_wake_test_button))
            }
        }
    }
}

private val GestureRecordState?.isRecording: Boolean
    get() = this is GestureRecordState.Preparing || this is GestureRecordState.Recording
