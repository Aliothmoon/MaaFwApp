package com.aliothmoon.maafw.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.domain.ThemeMode
import com.aliothmoon.maafw.privileged.ShizukuInstallHelper
import com.aliothmoon.maafw.privileged.SystemPermissionRequester
import com.aliothmoon.maafw.session.SessionEffect
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionViewModel
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaFwTheme
import com.aliothmoon.maafw.ui.components.MaaDiagnosticList
import com.aliothmoon.maafw.ui.components.ShizukuReadinessDialog
import com.aliothmoon.maafw.ui.home.HomeScreen
import com.aliothmoon.maafw.ui.i18n.localized
import com.aliothmoon.maafw.ui.settings.SettingsScreen
import com.aliothmoon.maafw.ui.tasks.TasksScreen
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** Compose 的 LocalContext 在对话框等场景下会是 ContextWrapper，逐层剥到 Activity */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private enum class TopDestination(
    @param:StringRes val labelRes: Int,
    val outlinedIcon: ImageVector,
    val filledIcon: ImageVector,
) {
    Home(R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    Tasks(R.string.nav_tasks, Icons.Outlined.Checklist, Icons.Filled.Checklist),
    Settings(R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
}

/** Route：收集 state、消费 Effect、承载三 tab；VM 为 Activity 作用域 */
@Composable
fun AppRoot(
    onDarkThemeChanged: (Boolean) -> Unit,
    viewModel: SessionViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 触点单独一条流，不并进 SessionUiState（见 SessionViewModel.previewMarkers）
    val previewMarkers by viewModel.previewMarkers.collectAsStateWithLifecycle()

    // 语言重载唯一触发点：App/系统切语言都经 Activity 重建后到此
    val localeTag = LocalConfiguration.current.locales[0]?.toLanguageTag().orEmpty()
    var lastLocaleTag by rememberSaveable { mutableStateOf(localeTag) }
    LaunchedEffect(localeTag) {
        if (lastLocaleTag != localeTag) {
            lastLocaleTag = localeTag
            viewModel.onIntent(SessionIntent.ReloadProject)
        }
    }

    val darkTheme = when (state.themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    LaunchedEffect(darkTheme) { onDarkThemeChanged(darkTheme) }

    MaaFwTheme(darkTheme = darkTheme) {
        val pagerState = rememberPagerState(pageCount = { TopDestination.entries.size })
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        var diagnosticsDialog by remember { mutableStateOf<List<Diagnostic>?>(null) }

        val context = LocalContext.current
        LaunchedEffect(Unit) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is SessionEffect.ShowMessage ->
                        snackbarHostState.showSnackbar(effect.message.localized(context))

                    is SessionEffect.ShowDiagnostics -> diagnosticsDialog = effect.diagnostics

                    // 拉起外部 Activity 要 Context，只能落在 Route 层
                    SessionEffect.InstallShizuku -> ShizukuInstallHelper.installShizuku(context)
                    SessionEffect.OpenShizuku -> ShizukuInstallHelper.openShizuku(context)

                    is SessionEffect.RequestSystemPermission -> {
                        // 系统权限页以调用方 Activity 为宿主；拿不到就只能放弃这次请求
                        val activity = context.findActivity()
                        if (activity != null) {
                            SystemPermissionRequester.request(activity, effect.permission)
                            viewModel.onIntent(SessionIntent.RefreshPermissions)
                        }
                    }
                }
            }
        }

        // 未装/未启动/未授权时的引导；needsGuidance 为 false 时自身不渲染
        ShizukuReadinessDialog(
            readiness = state.shizukuReadiness,
            onInstall = { viewModel.onIntent(SessionIntent.InstallShizuku) },
            onOpenApp = { viewModel.onIntent(SessionIntent.OpenShizuku) },
            onRequestAuth = { viewModel.onIntent(SessionIntent.RequestRemoteAccess) },
            onDismiss = { viewModel.onIntent(SessionIntent.SkipShizukuCheck) },
            onSwitchToRoot = { viewModel.onIntent(SessionIntent.SetRemoteBackend(RemoteBackend.ROOT)) },
            isRequesting = state.remoteAccessGranting,
        )

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                // M3 NavigationBar 固定 80dp 且 padding 不可调，自建 56dp
                Column {
                    HorizontalDivider(
                        thickness = MaaDesignTokens.Separator.thickness,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .height(56.dp)
                                .selectableGroup(),
                        ) {
                            TopDestination.entries.forEachIndexed { index, destination ->
                                val selected = pagerState.currentPage == index
                                val tint = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .selectable(
                                            selected = selected,
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = LocalIndication.current,
                                            role = Role.Tab,
                                            onClick = {
                                                scope.launch { pagerState.animateScrollToPage(index) }
                                            },
                                        ),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        imageVector = if (selected) destination.filledIcon else destination.outlinedIcon,
                                        contentDescription = stringResource(destination.labelRes),
                                        tint = tint,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(destination.labelRes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = tint,
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) { padding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) { page ->
                when (TopDestination.entries[page]) {
                    TopDestination.Home -> HomeScreen(state, viewModel::onIntent, Modifier.fillMaxSize())
                    TopDestination.Tasks -> TasksScreen(
                        state = state,
                        previewMarkers = previewMarkers,
                        onIntent = viewModel::onIntent,
                        modifier = Modifier.fillMaxSize(),
                    )
                    TopDestination.Settings -> SettingsScreen(state, viewModel::onIntent, Modifier.fillMaxSize())
                }
            }
        }

        diagnosticsDialog?.let { diagnostics ->
            AlertDialog(
                onDismissRequest = { diagnosticsDialog = null },
                title = { Text(stringResource(R.string.dialog_cannot_start)) },
                text = { MaaDiagnosticList(diagnostics) },
                confirmButton = {
                    TextButton(onClick = { diagnosticsDialog = null }) { Text(stringResource(R.string.dialog_ok)) }
                },
            )
        }
    }
}
