package com.xiaomiscalesync.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.xiaomiscalesync.ui.debug.SpikeController
import com.xiaomiscalesync.ui.debug.SpikeScreen

/**
 * The app's navigation graph — single route for now (plan Task 13).
 *
 * Milestone 0 ships exactly one destination: the debug spike screen that
 * proves the protocol on real hardware (owner checklist:
 * `docs/testing/milestone-0.md`). The Task 14–17 screens (onboarding →
 * main → history → settings) plug in here as real routes once the owner
 * gate passes; until then showing anything else would be a lie about what
 * the app can do.
 */
@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val controller = remember(context.applicationContext) {
        SpikeController(context.applicationContext)
    }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    SpikeScreen(controller)
}
