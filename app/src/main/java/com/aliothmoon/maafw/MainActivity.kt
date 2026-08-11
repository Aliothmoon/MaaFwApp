package com.aliothmoon.maafw

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.aliothmoon.maafw.settings.AppSettingsManager
import com.aliothmoon.maafw.ui.AppRoot
import com.aliothmoon.maafw.ui.ProvideInputFocusManager
import org.koin.android.ext.android.inject

// per-app locale 在 API 32- 依赖 AppCompatActivity（appcompat 只包装其 context）
class MainActivity : AppCompatActivity() {

    private val appSettings: AppSettingsManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须早于 super.onCreate：它要在窗口装起来之前把启动主题换成 postSplashScreenTheme
        val splash = installSplashScreen()
        // 押到设置读盘完成：主题风格与深浅色都取自设置，早放行会先渲染一帧默认主题再跳变
        splash.setKeepOnScreenCondition { !appSettings.loaded.value }
        super.onCreate(savedInstanceState)

        setContent {
            ProvideInputFocusManager {
                AppRoot(onDarkThemeChanged = ::applyEdgeToEdge)
            }
        }
    }

    private fun applyEdgeToEdge(darkMode: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }
}
