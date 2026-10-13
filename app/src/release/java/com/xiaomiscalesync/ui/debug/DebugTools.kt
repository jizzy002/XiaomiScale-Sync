package com.xiaomiscalesync.ui.debug

import android.content.Context
import androidx.compose.runtime.Composable

/**
 * Release builds: no debug screen. The spike and its plain-prefs key store
 * live only in the debug source set.
 */
object DebugTools {
    const val AVAILABLE = false

    @Composable
    fun Screen(appContext: Context) = Unit

    /**
     * Deletes the spike's plain-prefs store, which builds up to 0.1.0 could
     * write in release too (scale keys and Garmin tokens in cleartext).
     */
    fun purgeLegacyStore(context: Context) {
        context.deleteSharedPreferences("milestone0_spike")
    }
}
