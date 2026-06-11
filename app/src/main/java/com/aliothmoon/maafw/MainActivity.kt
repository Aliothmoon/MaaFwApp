package com.aliothmoon.maafw

import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.aliothmoon.maafw.navigation.AppNavigation
import com.aliothmoon.maafw.theme.MaaFwTheme

class MainActivity : AppCompatActivity() {

    @Volatile
    private var isUiReady: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !isUiReady }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        window.decorView.viewTreeObserver.addOnPreDrawListener(object :
            ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                isUiReady = true
                window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })

        setContent {
            MaaFwTheme {
                AppNavigation()
            }
        }
    }
}
