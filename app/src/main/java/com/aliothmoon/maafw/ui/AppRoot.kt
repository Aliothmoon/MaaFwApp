package com.aliothmoon.maafw.ui

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.domain.ThemeMode
import com.aliothmoon.maafw.session.SessionEffect
import com.aliothmoon.maafw.session.SessionViewModel
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaFwTheme
import com.aliothmoon.maafw.ui.home.HomeScreen
import com.aliothmoon.maafw.ui.settings.SettingsScreen
import com.aliothmoon.maafw.ui.tasks.TasksScreen
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

private enum class TopDestination(
    val label: String,
    val outlinedIcon: ImageVector,
    val filledIcon: ImageVector,
) {
    Home("首页", Icons.Outlined.Home, Icons.Filled.Home),
    Tasks("任务", Icons.Outlined.Checklist, Icons.Filled.Checklist),
    Settings("设置", Icons.Outlined.Settings, Icons.Filled.Settings),
}

/**
 * Route 层：生命周期感知收集、Effect 消费与顶层导航。
 * SessionViewModel 为 Activity 作用域，三个 tab 观察同一实例。
 */
@Composable
fun AppRoot(
    onDarkThemeChanged: @Composable (Boolean) -> Unit,
    viewModel: SessionViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val darkTheme = when (state.themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    onDarkThemeChanged(darkTheme)

    MaaFwTheme(darkTheme = darkTheme) {
        // pager 是选中 tab 的唯一事实来源：滑动切页、点 tab 平滑滚动
        val pagerState = rememberPagerState(pageCount = { TopDestination.entries.size })
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        var diagnosticsDialog by remember { mutableStateOf<List<Diagnostic>?>(null) }

        LaunchedEffect(Unit) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is SessionEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
                    is SessionEffect.ShowDiagnostics -> diagnosticsDialog = effect.diagnostics
                }
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                // 自定义紧凑底栏：M3 NavigationBar 默认 80dp 且内部 padding 不可调，自建 56dp 版本
                Column {
                    // 发丝顶部分隔线，替代默认 tonal elevation 的着色阴影
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
                                        contentDescription = destination.label,
                                        tint = tint,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = destination.label,
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
                    TopDestination.Tasks -> TasksScreen(state, viewModel::onIntent, Modifier.fillMaxSize())
                    TopDestination.Settings -> SettingsScreen(state, viewModel::onIntent, Modifier.fillMaxSize())
                }
            }
        }

        diagnosticsDialog?.let { diagnostics ->
            AlertDialog(
                onDismissRequest = { diagnosticsDialog = null },
                title = { Text("无法开始") },
                text = {
                    androidx.compose.foundation.layout.Column {
                        diagnostics.forEach {
                            Text(
                                text = "${it.source}: ${it.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (it.severity == DiagnosticSeverity.Error) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { diagnosticsDialog = null }) { Text("知道了") }
                },
            )
        }
    }
}
