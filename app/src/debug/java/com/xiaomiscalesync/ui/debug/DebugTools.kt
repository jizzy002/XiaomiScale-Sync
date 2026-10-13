package com.xiaomiscalesync.ui.debug

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Debug builds: the milestone-0 spike, reachable from Profile. Its
 * plain-prefs key store ([SpikeKeyHolder]) exists only in this source set;
 * the release twin of this object has no screen and wipes that store.
 */
object DebugTools {
    const val AVAILABLE = true

    @Composable
    fun Screen(appContext: Context) {
        val controller = remember(appContext) { SpikeController(appContext) }
        DisposableEffect(controller) {
            onDispose { controller.dispose() }
        }
        // Debug screen has no Scaffold: keep it clear of the system bars.
        Box(Modifier.safeDrawingPadding()) { SpikeScreen(controller) }
    }

    /** Debug builds keep the spike's store — the spike still uses it. */
    fun purgeLegacyStore(context: Context) = Unit
}
