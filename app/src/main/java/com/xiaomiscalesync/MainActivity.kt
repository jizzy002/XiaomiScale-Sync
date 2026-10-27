package com.xiaomiscalesync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.xiaomiscalesync.ui.AppNavigation
import com.xiaomiscalesync.ui.theme.AppTheme

/**
 * The single activity: hosts the Compose UI edge-to-edge (each screen's
 * Scaffold applies the system-bar insets) under the system-driven [AppTheme];
 * routes live in [AppNavigation].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                AppNavigation()
            }
        }
    }
}
