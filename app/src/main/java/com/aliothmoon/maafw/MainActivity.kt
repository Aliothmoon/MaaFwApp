package com.aliothmoon.maafw

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.aliothmoon.maafw.ui.AppRoot
import com.aliothmoon.maafw.ui.ProvideInputFocusManager

// per-app locale 在 API 32- 依赖 AppCompatActivity（appcompat 只包装其 context）
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
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
