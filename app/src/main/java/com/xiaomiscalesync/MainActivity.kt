package com.xiaomiscalesync

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.xiaomiscalesync.data.SettingsStore
import com.xiaomiscalesync.data.Settings
import com.xiaomiscalesync.ui.AppNavigation
import com.xiaomiscalesync.ui.theme.AppTheme
import com.xiaomiscalesync.ui.theme.isDark

/**
 * The single activity: hosts the Compose UI edge-to-edge (each screen's
 * Scaffold applies the system-bar insets) under [AppTheme] in the
 * owner's chosen [ThemeMode]; routes live in [AppNavigation].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = SettingsStore.of(applicationContext).settings
        setContent {
            val appSettings by settings.collectAsState(initial = Settings.DEFAULT)
            val mode = appSettings.themeMode
            val dark = mode.isDark()
            // Status/navigation bar icons follow the app's theme, not the phone's.
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            AppTheme(mode) {
                AppNavigation()
            }
        }
    }
}
