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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import com.aliothmoon.maafw.util.Misc
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionViewModel
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaFwTheme
import com.aliothmoon.maafw.theme.ThemeStyle
import com.aliothmoon.maafw.ui.components.MaaDiagnosticList
import com.aliothmoon.maafw.ui.components.clearFocusOnBlankTap
import com.aliothmoon.maafw.ui.components.MaaMarkdownSheet
import com.aliothmoon.maafw.ui.components.PiInstallDialog
import com.aliothmoon.maafw.ui.components.ShizukuReadinessDialog
import com.aliothmoon.maafw.ui.home.HomeScreen
import com.aliothmoon.maafw.ui.navigation.Routes
import com.aliothmoon.maafw.ui.logs.AppLogDetailScreen
import com.aliothmoon.maafw.ui.logs.AppLogScreen
import com.aliothmoon.maafw.ui.notification.NotificationSettingsScreen
import com.aliothmoon.maafw.ui.logs.LogExportController
import com.aliothmoon.maafw.ui.logs.RunLogArchiveScreen
import com.aliothmoon.maafw.ui.logs.RunLogDetailScreen
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

/** M3 NavigationBar 固定 80dp 且 padding 不可调，底栏自建成这个高度 */
private val BottomBarHeight = 56.dp

/**
 * 二级页面盖在主 tab 之上时这层的输入处理
 *
 * 主 tab 那层还活着只是被盖住，不截断命中测试就能隔着二级页横滑切页、点到底栏；
 * 截断之后根部那层空白失焦也够不着了，两者必须成对出现
 */
@Composable
private fun Modifier.subPageOverlayInput(): Modifier = this
    .pointerInput(Unit) {
        awaitPointerEventScope {
            // Main pass 排在子节点之后，二级页自己的手势先走，这里只收剩下的
            while (true) {
                awaitPointerEvent().changes.forEach { it.consume() }
            }
        }
    }
    // 排在截断内侧，先于它拿到 Press
    .clearFocusOnBlankTap()

/** Route：收集 state、消费 Effect、承载四个主 tab 与二级页面的 NavHost；VM 为 Activity 作用域 */
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
    // 刻意不用 by 解构：在这一层读就等于让整棵树跟着触摸与日志频率重组，
    // 往下传取值函数，读推迟到真正显示它们的叶子
    val previewMarkersState = viewModel.previewMarkers.collectAsStateWithLifecycle()
    val runLogState = viewModel.runLog.collectAsStateWithLifecycle()

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
            markers = { previewMarkersState.value },
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

    MaaFwTheme(themeStyle = state.themeStyle, darkTheme = darkTheme) {
        // NavHost 只承载二级页面；主 tab 仍由下面的 HorizontalPager 渲染
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        // 首帧 backStackEntry 还没就绪，那时必然停在 startDestination
        val currentRoute = navBackStackEntry?.destination?.route
        val onSubPage = currentRoute != null && currentRoute !in Routes.mainTabs
        val pagerState = rememberPagerState(pageCount = { TopDestination.entries.size })
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        var diagnosticsDialog by remember { mutableStateOf<List<Diagnostic>?>(null) }
        var exportSheetVisible by remember { mutableStateOf(false) }

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


                    // 控制层挂在 WindowManager 上、跨 Activity 存活，由 Application 级单例持有
                    SessionEffect.ShowOverlay -> overlayController.show()
                    SessionEffect.ShowScreenSaver -> screenSaverManager.show()

                    SessionEffect.RestartApp -> Misc.restartApp(context)

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
                    // 系统页还在启动，此刻重读仍是改动前的值
                    ScheduleEffect.RequestExactAlarmPermission -> ExactAlarmSettings.open(context)
                }
            }
        }

        // Activity 进出都会走这里；系统精确闹钟页没有结果回调
        val scheduleLifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(scheduleLifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    scheduleViewModel.onIntent(ScheduleIntent.RefreshExactAlarmPermission)
                }
            }
            scheduleLifecycleOwner.lifecycle.addObserver(observer)
            onDispose { scheduleLifecycleOwner.lifecycle.removeObserver(observer) }
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

        // 排在 Shizuku 引导之后才盖得住它：PI 没铺好之前，授不授权都无事可做
        PiInstallDialog(
            state = state.piInstallState,
            onRetry = { viewModel.onIntent(SessionIntent.ReinstallPi) },
        )

        MaaMarkdownSheet(
            title = stringResource(R.string.welcome_title),
            body = state.welcomePrompt,
            onDismiss = { viewModel.onIntent(SessionIntent.DismissWelcome) },
        )

        // 整窗的空白失焦铺在这一层；另开窗口的（sheet、Dialog）不在这棵命中树里，各自挂
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clearFocusOnBlankTap(),
        ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
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
                                .height(BottomBarHeight)
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
                                    Spacer(Modifier.height(MaaDesignTokens.Spacing.xxs))
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
                // 不预组合的话，整页的首次组合与测量全落在切页动画的第一帧里：
                // 实测任务页那一帧 193ms（7 个 TaskRow 逐个组合 45ms + measureAndLayout 31ms）
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // 页内 imePadding 量的是到窗口底边的距离，而这里的底边已经被底栏顶高了一截；
                    // 不声明这份已让位的 inset，页内就会多减一个底栏，正文与键盘之间空出一条
                    // 键盘让位交给具体页去挂：挂在这里等于每帧重测四个 tab
                    .consumeWindowInsets(padding),
            ) { page ->
                when (TopDestination.entries[page]) {
                    TopDestination.Home -> HomeScreen(state, viewModel::onIntent, Modifier.fillMaxSize())
                    TopDestination.Tasks -> TasksScreen(
                        state = state,
                        previewSurfaceReady = previewSurfaceReady,
                        // 全屏时这里让位，同一份 previewContent 搬到下面的全屏宿主
                        previewContent = previewContent.takeUnless { previewFullscreen },
                        runLog = { runLogState.value },
                        onEnterFullscreen = { previewFullscreen = true },
                        onExportLogs = { exportSheetVisible = true },
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
                        onOpenRunLogArchive = { navController.navigate(Routes.RUN_LOG_ARCHIVE) },
                        onOpenAppLog = { navController.navigate(Routes.APP_LOG) },
                        onOpenNotificationSettings = { navController.navigate(Routes.NOTIFICATION_SETTINGS) },
                        onExportLogs = { exportSheetVisible = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // 二级页面自成一层：留在 Scaffold 体内会被底栏从高度里扣掉一截，盖不住它；
        // inset 也随之归各页自己吃
        // imePadding 排在指针修饰符之后，命中区仍是整屏，只有内容被压
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (onSubPage) Modifier.subPageOverlayInput() else Modifier)
                .imePadding(),
        ) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.fillMaxSize(),
                // 共享轴 X 前进转场，对齐 MaaMeow：推进右进左出、返回左进右出
                enterTransition = { slideInHorizontally { it } + fadeIn() },
                exitTransition = { slideOutHorizontally { -it } + fadeOut() },
                popEnterTransition = { slideInHorizontally { -it } + fadeIn() },
                popExitTransition = { slideOutHorizontally { it } + fadeOut() },
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
                composable(Routes.RUN_LOG_ARCHIVE) {
                    RunLogArchiveScreen(
                        onBack = { navController.popBackStack() },
                        onOpen = { navController.navigate(Routes.runLogDetail(it)) },
                    )
                }
                composable(Routes.APP_LOG) {
                    AppLogScreen(
                        onBack = { navController.popBackStack() },
                        onOpen = { navController.navigate(Routes.appLogDetail(it)) },
                    )
                }
                composable(Routes.NOTIFICATION_SETTINGS) {
                    NotificationSettingsScreen(onBack = { navController.popBackStack() })
                }
                composable(
                    route = Routes.APP_LOG_DETAIL,
                    arguments = listOf(
                        navArgument(Routes.APP_LOG_DETAIL_ARG) { type = NavType.StringType },
                    ),
                ) { entry ->
                    AppLogDetailScreen(
                        fileName = entry.arguments?.getString(Routes.APP_LOG_DETAIL_ARG).orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = Routes.RUN_LOG_DETAIL,
                    arguments = listOf(
                        navArgument(Routes.RUN_LOG_DETAIL_ARG) { type = NavType.StringType },
                    ),
                ) { entry ->
                    RunLogDetailScreen(
                        fileName = entry.arguments?.getString(Routes.RUN_LOG_DETAIL_ARG).orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        // 挂在二级页面之上，否则整屏的二级页一盖，snackbar 就没人看得见
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (onSubPage) 0.dp else BottomBarHeight),
        )
        }

        // 无条件挂在这一层：它注册的 SAF launcher 要活得比 sheet 的显隐久
        LogExportController(
            visible = exportSheetVisible,
            onDismiss = { exportSheetVisible = false },
            onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
        )

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
                // 诊断条数不设上限，靠滚动兜住：AlertDialog 的 text 槽自己不滚，超高会被直接裁掉
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        MaaDiagnosticList(diagnostics)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { diagnosticsDialog = null }) { Text(stringResource(R.string.dialog_ok)) }
                },
            )
        }
    }
}
