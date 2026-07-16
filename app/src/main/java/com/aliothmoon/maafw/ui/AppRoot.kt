package com.aliothmoon.maafw.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.domain.ThemeMode
import com.aliothmoon.maafw.session.SessionEffect
import com.aliothmoon.maafw.session.SessionViewModel
import com.aliothmoon.maafw.theme.MaaFwTheme
import com.aliothmoon.maafw.ui.home.HomeScreen
import com.aliothmoon.maafw.ui.settings.SettingsScreen
import com.aliothmoon.maafw.ui.tasks.TasksScreen
import org.koin.androidx.compose.koinViewModel

private enum class TopDestination(val label: String, val icon: ImageVector) {
    Home("首页", Icons.Outlined.Home),
    Tasks("任务", Icons.AutoMirrored.Outlined.List),
    Settings("设置", Icons.Outlined.Settings),
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
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
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
                NavigationBar {
                    TopDestination.entries.forEachIndexed { index, destination ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            },
        ) { padding ->
            val contentModifier = Modifier
                .fillMaxSize()
                .padding(padding)
            when (TopDestination.entries[selectedTab]) {
                TopDestination.Home -> HomeScreen(state, viewModel::onIntent, contentModifier)
                TopDestination.Tasks -> TasksScreen(state, viewModel::onIntent, contentModifier)
                TopDestination.Settings -> SettingsScreen(state, viewModel::onIntent, contentModifier)
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
