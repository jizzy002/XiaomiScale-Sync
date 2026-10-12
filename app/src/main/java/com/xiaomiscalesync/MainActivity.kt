package com.xiaomiscalesync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xiaomiscalesync.ui.AppNavigation
import com.xiaomiscalesync.ui.theme.AppTheme

/**
 * The single activity: hosts the Compose UI edge-to-edge (each screen's
 * Scaffold applies the system-bar insets) under [AppTheme]; routes live
 * in [AppNavigation].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                AppNavigation()
            }
        }
    }
}
