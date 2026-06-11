package com.aliothmoon.maafw.navigation

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.demo.DemoPage
import com.aliothmoon.maafw.demo.SettingsPage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.HorizontalSplit
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val pageTitles = listOf("Miuix Demo", "Settings")

private val navigationItems = listOf(
    NavigationItem("Demo", MiuixIcons.HorizontalSplit),
    NavigationItem("Settings", MiuixIcons.Settings),
)

@Composable
fun AppNavigation(
    onDarkThemeChanged: @Composable (Boolean) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()

    val themeMode = remember { mutableStateOf(ThemeMode.SYSTEM) }
    val darkTheme = when (themeMode.value) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (darkTheme) darkColorScheme() else lightColorScheme()

    onDarkThemeChanged(darkTheme)

    MiuixTheme(colors = colors) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(56.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = pageTitles[pagerState.currentPage],
                        style = MiuixTheme.textStyles.title1,
                    )
                    Spacer(Modifier.weight(1f))
                }
            },
            bottomBar = {
                NavigationBar {
                    navigationItems.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            icon = item.icon,
                            label = item.label,
                        )
                    }
                }
            },
        ) { padding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    Routes.DEMO -> DemoPage(
                        padding = padding,
                        currentThemeMode = themeMode.value,
                        onThemeModeChange = { themeMode.value = it },
                    )
                    Routes.SETTINGS -> SettingsPage(contentPadding = padding)
                }
            }
        }
    }
}
