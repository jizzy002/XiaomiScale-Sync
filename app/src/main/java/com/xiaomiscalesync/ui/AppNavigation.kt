package com.xiaomiscalesync.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.xiaomiscalesync.AppGraph
import com.xiaomiscalesync.data.Secrets
import com.xiaomiscalesync.data.SettingsStore
import com.xiaomiscalesync.ui.debug.DebugTools
import com.xiaomiscalesync.ui.history.HistoryScreen
import com.xiaomiscalesync.ui.main.MainScreen
import com.xiaomiscalesync.ui.profile.ProfileScreen
import com.xiaomiscalesync.ui.onboarding.GarminSso
import com.xiaomiscalesync.ui.onboarding.MiCloudKeyExtractor
import com.xiaomiscalesync.ui.onboarding.OnboardingScreen
import com.xiaomiscalesync.ui.onboarding.OnboardingStep
import com.xiaomiscalesync.ui.onboarding.OnboardingViewModel
import com.xiaomiscalesync.ui.onboarding.WizardAnswers
import com.xiaomiscalesync.ui.theme.AppIcons
import com.xiaomiscalesync.xiaomi.MiCloudClient
import okhttp3.OkHttpClient

/**
 * The app's navigation graph (plan Task 14): the onboarding wizard shows
 * first whenever `Settings.onboarded` is false; once the wizard's Finish
 * flips it true (idempotent, single write), the flow re-emits and Main
 * takes over. [MainRoute] hosts `MainScreen`; debug builds also reach the
 * milestone-0 spike from it ([DebugTools]).
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

/** The bottom-bar destinations. */
private enum class Tab(val label: String) { HOME("Home"), HISTORY("History"), PROFILE("Profile") }

/**
 * The Main destination: Home (weighing, Task 15), History (Task 16) and
 * Profile (logins + settings, Task 17) behind a bottom navigation bar.
 * Only Home listens to the scale, and only while it is visible and its
 * listening switch is on. The switch resets to ON at every fresh launch
 * (owner ruling 2026-10-13) — it survives rotation, not an app exit.
 * Debug builds reach the milestone-0 spike from Profile ([DebugTools]).
 */
@Composable
private fun MainRoute() {
    val appContext = LocalContext.current.applicationContext
    var showSpike by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var listeningOn by rememberSaveable { mutableStateOf(true) }

    // App open: push anything left pending (e.g. a failed network push).
    LaunchedEffect(Unit) {
        DebugTools.purgeLegacyStore(appContext)
        AppGraph.of(appContext).pushIfPending()
    }

    if (showSpike) {
        BackHandler { showSpike = false }
        DebugTools.Screen(appContext)
        return
    }

    if (tab != Tab.HOME) BackHandler { tab = Tab.HOME }
    Scaffold(
        // Each tab's own Scaffold handles the top/status-bar insets.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                for (t in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.HOME -> AppIcons.Home
                                    Tab.HISTORY -> AppIcons.History
                                    Tab.PROFILE -> AppIcons.Person
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            when (tab) {
                Tab.HOME -> MainScreen(
                    listeningOn = listeningOn,
                    onListeningChange = { listeningOn = it },
                    onOpenHistory = { tab = Tab.HISTORY },
                    onOpenProfile = { tab = Tab.PROFILE },
                )
                Tab.HISTORY -> HistoryScreen()
                Tab.PROFILE -> ProfileScreen(onOpenDebug = if (DebugTools.AVAILABLE) ({ showSpike = true }) else null)
            }
        }
    }
}
