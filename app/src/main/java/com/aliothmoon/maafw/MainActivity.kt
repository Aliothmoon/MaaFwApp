package com.aliothmoon.maafw

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.aliothmoon.maafw.settings.AppSettingsManager
import com.aliothmoon.maafw.ui.AppRoot
import com.aliothmoon.maafw.ui.pip.LocalIsInPip
import com.aliothmoon.maafw.ui.pip.PipController
import com.aliothmoon.maafw.ui.pip.PipHost
import com.aliothmoon.maafw.ui.pip.PipRequest
import org.koin.android.ext.android.inject

class MainActivity : AppCompatActivity(), PipHost {

    private val appSettings: AppSettingsManager by inject()

    @Volatile
    override var pipRequest: PipRequest? = null

    private var isInPip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !appSettings.loaded.value }
        super.onCreate(savedInstanceState)

        setContent {
            CompositionLocalProvider(LocalIsInPip provides isInPip) {
                AppRoot(onDarkThemeChanged = ::applyEdgeToEdge)
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val request = pipRequest ?: return
        PipController.enterNow(this, request)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip = isInPictureInPictureMode
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
