package com.xiaomiscalesync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xiaomiscalesync.data.Secrets
import com.xiaomiscalesync.data.SettingsStore
import com.xiaomiscalesync.ui.debug.SpikeController
import com.xiaomiscalesync.ui.debug.SpikeScreen
import com.xiaomiscalesync.ui.onboarding.GarminSso
import com.xiaomiscalesync.ui.onboarding.MiCloudKeyExtractor
import com.xiaomiscalesync.ui.onboarding.OnboardingScreen
import com.xiaomiscalesync.ui.onboarding.OnboardingStep
import com.xiaomiscalesync.ui.onboarding.OnboardingViewModel
import com.xiaomiscalesync.ui.onboarding.WizardAnswers
import com.xiaomiscalesync.xiaomi.MiCloudClient
import okhttp3.OkHttpClient

/**
 * The app's navigation graph (plan Task 14): the onboarding wizard shows
 * first whenever `Settings.onboarded` is false; once the wizard's Finish
 * flips it true (idempotent, single write), the flow re-emits and Main
 * takes over. [MainRoute] is the placeholder Task 15 replaces with
 * `MainScreen` — the debug spike stays reachable from it so the owner's
 * milestone-0 harness never disappears mid-project.
 */
@Composable
fun AppNavigation() {
    val appContext = LocalContext.current.applicationContext
    val settings = remember(appContext) { SettingsStore.of(appContext) }
    val current by settings.settings.collectAsState(initial = null)

    val s = current
    if (s == null) {
        // First frame: DataStore has not emitted yet.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (!s.onboarded) {
        // First launch (or Finish never pressed): the wizard, wiring the
        // production seams — Keystore `Secrets`, Mi Cloud extraction,
        // Garmin SSO — into the tested view model.
        val viewModel = remember(appContext) {
            val http = OkHttpClient()
            val secrets = Secrets(appContext)
            OnboardingViewModel(
                settings = settings,
                secrets = secrets,
                mi = MiCloudKeyExtractor(MiCloudClient(http)),
                garmin = GarminSso(http, secrets),
            )
        }
        // Rotation / process death: the non-secret answers ride in
        // saved-instance state and are re-applied onto the fresh VM once
        // (key presence re-seeds from Secrets inside the VM itself).
        var saved by rememberSaveable(stateSaver = wizardSaver) {
            mutableStateOf<WizardAnswers?>(null)
        }
        LaunchedEffect(viewModel) {
            saved?.let(viewModel::restore)
            viewModel.state.collect { saved = viewModel.answers() }
        }
        OnboardingScreen(viewModel)
    } else {
        MainRoute()
    }
}

/**
 * Bundles [WizardAnswers] as a flat list of primitives (step by name).
 * Nothing secret is in it — passwords and key drafts never enter
 * [WizardAnswers] at all. An unknown step name (app update renamed an
 * enum) restores as null: the wizard simply starts over.
 */
private val wizardSaver: Saver<WizardAnswers?, Any> = listSaver(
    save = { a ->
        if (a == null) {
            emptyList()
        } else {
            listOf(
                a.step.name, a.manualMode, a.heightCm, a.age, a.sex, a.mid,
                a.historyAlreadySynced, a.garminUser, a.garminSignedIn,
            )
        }
    },
    restore = { l ->
        val step = OnboardingStep.entries.firstOrNull { it.name == l.getOrNull(0) }
        if (l.size != 9 || step == null) {
            null
        } else {
            WizardAnswers(
                step = step,
                manualMode = l[1] as Boolean,
                heightCm = l[2] as String,
                age = l[3] as String,
                sex = l[4] as Int,
                mid = l[5] as String,
                historyAlreadySynced = l[6] as Boolean,
                garminUser = l[7] as String,
                garminSignedIn = l[8] as Boolean,
            )
        }
    },
)

/**
 * The Main destination. Until plan Task 15 lands `MainScreen`, this
 * placeholder holds the slot and keeps the milestone-0 debug spike
 * reachable (opened on demand, so its controller — and the
 * `PushWorker.pusherFactory` install in its init — only spins up when
 * actually used).
 */
@Composable
private fun MainRoute() {
    val appContext = LocalContext.current.applicationContext
    var showSpike by remember { mutableStateOf(false) }

    if (showSpike) {
        val controller = remember(appContext) { SpikeController(appContext) }
        DisposableEffect(controller) {
            onDispose { controller.dispose() }
        }
        SpikeScreen(controller)
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Xiaomi Scale → Garmin", style = MaterialTheme.typography.titleLarge)
            Text(
                "Onboarding complete — the weighing screen arrives with the next build.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            TextButton(onClick = { showSpike = true }) {
                Text("Open debug spike screen")
            }
        }
    }
}
