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

class MainActivity : AppCompatActivity() {

    private val appSettings: AppSettingsManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
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
