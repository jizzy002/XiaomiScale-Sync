package com.xiaomiscalesync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.xiaomiscalesync.ui.AppNavigation

/**
 * The single activity: hosts the Compose UI. Milestone 0 shows the spike
 * screen through [AppNavigation]; later tasks swap in real routes without
 * touching this class.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppNavigation()
            }
        }
    }
}
