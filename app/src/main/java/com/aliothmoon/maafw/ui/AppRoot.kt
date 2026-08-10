package com.aliothmoon.maafw.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Schedule
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.domain.ThemeMode
import com.aliothmoon.maafw.i18n.resolve
import com.aliothmoon.maafw.privileged.ShizukuInstallHelper
import com.aliothmoon.maafw.overlay.OverlayController
import com.aliothmoon.maafw.overlay.screensaver.ScreenSaverOverlayManager
import com.aliothmoon.maafw.privileged.SystemPermissionRequester
import com.aliothmoon.maafw.schedule.ExactAlarmSettings
import com.aliothmoon.maafw.schedule.ScheduleEffect
import com.aliothmoon.maafw.schedule.ScheduleIntent
import com.aliothmoon.maafw.schedule.ScheduleViewModel
import com.aliothmoon.maafw.settings.SettingsIntent
import com.aliothmoon.maafw.settings.SettingsViewModel
import com.aliothmoon.maafw.session.SessionEffect
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionViewModel
import com.aliothmoon.maafw.service.RunForegroundService
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaFwTheme
import com.aliothmoon.maafw.ui.components.MaaDiagnosticList
import com.aliothmoon.maafw.ui.components.ShizukuReadinessDialog
import com.aliothmoon.maafw.ui.home.HomeScreen
import com.aliothmoon.maafw.ui.navigation.Routes
import com.aliothmoon.maafw.ui.schedule.ScheduleEditScreen
import com.aliothmoon.maafw.ui.schedule.ScheduleScreen
import com.aliothmoon.maafw.ui.schedule.ScheduleTriggerLogScreen
import com.aliothmoon.maafw.ui.settings.SettingsScreen
import com.aliothmoon.maafw.ui.tasks.FullscreenPreview
import com.aliothmoon.maafw.ui.tasks.TasksScreen
import com.aliothmoon.maafw.ui.tasks.rememberMovablePreview
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

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
    Schedule(R.string.nav_schedule, Icons.Outlined.Schedule, Icons.Filled.Schedule),
    Settings(R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
}

/** Route：收集 state、消费 Effect、承载三 tab；VM 为 Activity 作用域 */
@Composable
fun AppRoot(
    onDarkThemeChanged: (Boolean) -> Unit,
    viewModel: SessionViewModel = koinViewModel(),
    scheduleViewModel: ScheduleViewModel = koinViewModel(),
    settingsViewModel: SettingsViewModel = koinViewModel(),
    overlayController: OverlayController = koinInject(),
    screenSaverManager: ScreenSaverOverlayManager = koinInject(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scheduleState by scheduleViewModel.uiState.collectAsStateWithLifecycle()
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    // 触点与运行日志各自单独一条流，不并进 SessionUiState（见 SessionViewModel）
    val previewMarkers by viewModel.previewMarkers.collectAsStateWithLifecycle()
    val runLog by viewModel.runLog.collectAsStateWithLifecycle()

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

    // 预览面的所有权在这一层：全屏宿主必须在 Scaffold 之外才能盖住底部 tab 栏，
    // 而 movableContent 要求内嵌与全屏两处调用点同属一棵组合树
    var previewFullscreen by rememberSaveable { mutableStateOf(false) }
    var previewSurfaceReady by remember { mutableStateOf(false) }
    val previewContent = state.previewResolution?.let { resolution ->
        rememberMovablePreview(
            resolution = resolution,
            markers = previewMarkers,
            onSurfaceAvailable = {
                previewSurfaceReady = true
                viewModel.onIntent(SessionIntent.AttachPreviewSurface(it))
            },
            onSurfaceDestroyed = {
                previewSurfaceReady = false
                viewModel.onIntent(SessionIntent.DetachPreviewSurface)
            },
        )
    }
    // 项目重载后分辨率可能变；此时全屏没有内容可显示，先退回来
    LaunchedEffect(previewContent) {
        if (previewContent == null) previewFullscreen = false
    }

    MaaFwTheme(darkTheme = darkTheme) {
        // NavHost 只承载二级页面；主 tab 仍由下面的 HorizontalPager 渲染
        val navController = rememberNavController()
        val pagerState = rememberPagerState(pageCount = { TopDestination.entries.size })
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        var diagnosticsDialog by remember { mutableStateOf<List<Diagnostic>?>(null) }

        val context = LocalContext.current
        LaunchedEffect(Unit) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is SessionEffect.ShowMessage ->
                        snackbarHostState.showSnackbar(effect.message.resolve(context))

                    is SessionEffect.ShowDiagnostics -> diagnosticsDialog = effect.diagnostics

                    // 拉起外部 Activity 要 Context，只能落在 Route 层
                    SessionEffect.InstallShizuku -> ShizukuInstallHelper.installShizuku(context)
                    SessionEffect.OpenShizuku -> ShizukuInstallHelper.openShizuku(context)

                    SessionEffect.StartRunForegroundService -> RunForegroundService.start(context)

                    // 控制层挂在 WindowManager 上、跨 Activity 存活，由 Application 级单例持有
                    SessionEffect.ShowOverlay -> overlayController.show()
                    SessionEffect.ShowScreenSaver -> screenSaverManager.show()

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

        LaunchedEffect(Unit) {
            scheduleViewModel.effects.collect { effect ->
                when (effect) {
                    ScheduleEffect.RequestExactAlarmPermission -> {
                        ExactAlarmSettings.open(context)
                        // 那个页面没有结果回调，只能等用户回来时自己重读
                        scheduleViewModel.onIntent(ScheduleIntent.RefreshExactAlarmPermission)
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
            onSwitchToRoot = { settingsViewModel.onIntent(SettingsIntent.SetBackend(RemoteBackend.ROOT)) },
            isRequesting = state.remoteAccessGranting,
        )

        Box(modifier = Modifier.fillMaxSize()) {
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
                        previewSurfaceReady = previewSurfaceReady,
                        // 全屏时这里让位，同一份 previewContent 搬到下面的全屏宿主
                        previewContent = previewContent.takeUnless { previewFullscreen },
                        runLog = runLog,
                        onEnterFullscreen = { previewFullscreen = true },
                        onIntent = viewModel::onIntent,
                        modifier = Modifier.fillMaxSize(),
                    )
                    TopDestination.Schedule -> ScheduleScreen(
                        state = scheduleState,
                        onIntent = scheduleViewModel::onIntent,
                        onEdit = { id ->
                            navController.navigate(
                                if (id == null) Routes.SCHEDULE_EDIT_NEW else Routes.scheduleEdit(id),
                            )
                        },
                        onOpenLog = { navController.navigate(Routes.SCHEDULE_TRIGGER_LOG) },
                        modifier = Modifier.fillMaxSize(),
                    )

                    TopDestination.Settings -> SettingsScreen(
                        state = state,
                        onIntent = viewModel::onIntent,
                        settingsState = settingsState,
                        onSettingsIntent = settingsViewModel::onIntent,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.fillMaxSize(),
            ) {
                // 主 tab 路由空占位：真实内容由上面的 HorizontalPager 渲染
                composable(Routes.HOME) {}
                composable(Routes.TASKS) {}
                composable(Routes.SCHEDULE) {}
                composable(Routes.SETTINGS) {}
                composable(
                    route = Routes.SCHEDULE_EDIT,
                    arguments = listOf(
                        navArgument(Routes.SCHEDULE_EDIT_ARG) {
                            type = NavType.StringType
                            defaultValue = "new"
                        },
                    ),
                ) { entry ->
                    ScheduleEditScreen(
                        strategyId = entry.arguments?.getString(Routes.SCHEDULE_EDIT_ARG),
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.SCHEDULE_TRIGGER_LOG) {
                    ScheduleTriggerLogScreen(onBack = { navController.popBackStack() })
                }
            }
        }
        }

        // 挂在 Scaffold 之外，才盖得住底部 tab 栏与系统栏
        val fullscreenResolution = state.previewResolution
        if (previewFullscreen && previewContent != null && fullscreenResolution != null) {
            FullscreenPreview(
                resolution = fullscreenResolution,
                onExit = { previewFullscreen = false },
                onTouch = { x, y, action ->
                    viewModel.onIntent(SessionIntent.PreviewTouch(x, y, action))
                },
                content = previewContent,
            )
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
