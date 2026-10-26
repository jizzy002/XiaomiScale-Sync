package com.xiaomiscalesync.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.xiaomiscalesync.AppGraph
import com.xiaomiscalesync.data.Settings
import com.xiaomiscalesync.data.WeighInSource
import com.xiaomiscalesync.data.WeightUnit
import com.xiaomiscalesync.data.SyncOutcome
import com.xiaomiscalesync.data.SyncStatus
import com.xiaomiscalesync.sync.LiveReading
import com.xiaomiscalesync.ui.theme.Pill
import com.xiaomiscalesync.ui.formatCentiKg
import com.xiaomiscalesync.ui.formatDeltaCentiKg
import com.xiaomiscalesync.ui.theme.Tone
import com.xiaomiscalesync.ui.theme.container
import com.xiaomiscalesync.ui.theme.content
import com.xiaomiscalesync.ui.theme.tone
import com.xiaomiscalesync.work.PushWorker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * The main weighing screen (plan Task 15, spec "Main screen"): scans only
 * while this screen is resumed (no background scanning), keeps the display
 * on while listening, and shows the listening status, the live reading
 * while connected, the newest capture, and the
 * silence / Garmin-auth / needs-review banners. All decisions live in the
 * JVM-tested [MainUi]; this composable only renders them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    listeningOn: Boolean,
    onListeningChange: (Boolean) -> Unit,
    onOpenHistory: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
) {
    val appContext = LocalContext.current.applicationContext
    val graph = AppGraph.of(appContext)
    val scanner = graph.scanner
    val viewModel = remember(graph) {
        MainViewModel(
            graph.engine.state,
            graph.repo.observe(null),
            push = PushWorker.state(appContext),
        )
    }
    val ui by viewModel.uiState.collectAsState(initial = MainUiState())
    val settings by graph.settings.settings.collectAsState(initial = Settings.DEFAULT)
    val weightUnit = settings.weightUnit
    val listening by scanner.listening.collectAsState()
    val syncStatus by graph.syncStatus.status.collectAsState(initial = SyncStatus())
    val scope = rememberCoroutineScope()
    var recoveryRunning by remember { mutableStateOf(false) }
    var recoveryNotice by remember { mutableStateOf<String?>(null) }
    val recoveryEnabled = listeningOn && listening && !recoveryRunning
    val onRecover: () -> Unit = {
        recoveryRunning = true
        recoveryNotice = null
        scope.launch {
            try {
                if (!graph.recoverStoredReadings()) recoveryNotice = "A scale check is already running."
            } catch (_: Exception) {
                recoveryNotice = "The scale check could not be completed. Try again."
            } finally {
                recoveryRunning = false
            }
        }
    }

    // Permissions: asked once on entry; a grant bumps [permissionTick] so
    // the resume effect below re-runs and starts the scan immediately.
    var permissionTick by remember { mutableIntStateOf(0) }
    var missing by remember { mutableStateOf(scanner.missingPermissions()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        missing = scanner.missingPermissions()
        permissionTick++
    }
    LaunchedEffect(Unit) {
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }

    // Scan strictly while resumed AND switched on. Pausing (home button,
    // app switcher, screen off), leaving the tab, exiting, or flipping the
    // switch off closes the scanner gate — no scan, no new connection.
    LifecycleResumeEffect(scanner, permissionTick, listeningOn) {
        missing = scanner.missingPermissions()
        scanner.allowed = listeningOn
        if (listeningOn && missing.isEmpty() && scanner.startListening()) viewModel.onListeningStarted()
        onPauseOrDispose {
            scanner.allowed = false
            scanner.stopListening()
            viewModel.onListeningStopped()
        }
    }

    // Keep the display on while listening (spec): the scan dies with the
    // screen, so a sleeping display would silently stop capture.
    val view = LocalView.current
    DisposableEffect(view, listening) {
        view.keepScreenOn = listening
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scale Sync") },
                actions = {
                    TextButton(onClick = onRecover, enabled = recoveryEnabled) {
                        Text(if (recoveryRunning) "Checking…" else "Check scale")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (listeningOn && missing.isNotEmpty()) {
                BannerCard(
                    "Bluetooth access is needed to hear the scale.",
                    tone = Tone.ERROR,
                    action = "Allow" to { launcher.launch(missing.toTypedArray()) },
                )
            }
            for (banner in ui.banners) BannerFor(banner, onReview = onOpenHistory, onSignIn = onOpenProfile)

            StatusCard(
                headline = ui.headline,
                switchOn = listeningOn,
                scanning = listening,
                onSwitch = onListeningChange,
                syncStatus = syncStatus,
                recoveryNotice = recoveryNotice,
                recoveryRunning = recoveryRunning,
            )
            val latest = ui.latest
            if (latest != null) LatestWeighInCard(latest, weightUnit) else EmptyCard()
            ui.garmin?.takeUnless { it == GarminLine.Synced }?.let {
                GarminCard(
                    it,
                    onPushNow = { PushWorker.pushNow(appContext) },
                    onSignIn = onOpenProfile,
                )
            }
            ui.live?.let { LiveCard(it, weightUnit) }
            if (ui.recent.isNotEmpty()) RecentSection(ui.recent, weightUnit, onSeeAll = onOpenHistory)
        }
    }
}

/**
 * Listening status with the dedicated on/off switch. [switchOn] is the
 * owner's choice; [scanning] whether the scan actually runs (it can be on
 * but blocked by Bluetooth being off or a missing permission).
 */
@Composable
private fun StatusCard(
    headline: Headline,
    switchOn: Boolean,
    scanning: Boolean,
    onSwitch: (Boolean) -> Unit,
    syncStatus: SyncStatus,
    recoveryNotice: String?,
    recoveryRunning: Boolean,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (scanning) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        ),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        !switchOn -> "Listening off"
                        !scanning -> "Not listening"
                        else -> headline.label
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = switchOn,
                    onCheckedChange = onSwitch,
                    modifier = Modifier.semantics {
                        contentDescription = "Listen for scale readings"
                    },
                )
            }
            Text(
                when {
                    !switchOn -> "Saving battery. Weigh-ins stay on the scale and are fetched when you turn listening back on."
                    !scanning -> "Bluetooth is off or access is missing."
                    headline == Headline.SYNCING -> "Talking to the scale — stay close."
                    headline == Headline.SCALE_SEEN -> "The scale is awake."
                    else -> "Step on the scale with this screen open."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (headline == Headline.SYNCING) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            val recoveryDetail = when {
                recoveryRunning -> "Checking the scale for stored readings…"
                syncStatus.lastAttemptAt == null -> "No check yet · readings stay on the scale until collected"
                syncStatus.outcome == SyncOutcome.SUCCESS -> "Last checked ${time(syncStatus.lastSuccessAt ?: syncStatus.lastAttemptAt)} · " +
                    "${syncStatus.lastSuccessArchived} new ${if (syncStatus.lastSuccessArchived == 1) "reading" else "readings"}"
                syncStatus.outcome == SyncOutcome.SCALE_NOT_FOUND -> "Scale not found on last check · " +
                    (syncStatus.lastSuccessAt?.let { "last success ${time(it)}" } ?: "step on the scale and try again")
                else -> "Last check did not finish · " +
                    (syncStatus.lastSuccessAt?.let { "last success ${time(it)}" } ?: "keep the scale nearby and try again")
            }
            Column(
                Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(recoveryDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                recoveryNotice?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun LiveCard(live: LiveReading, weightUnit: WeightUnit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("On the scale", style = MaterialTheme.typography.labelLarge)
            WeightText(live.weightCentiKg, weightUnit, 56)
            Pill(
                if (live.stable) "Stable" else "Measuring…",
                if (live.stable) Tone.PRIMARY else Tone.NEUTRAL,
            )
        }
    }
}

@Composable
private fun LatestWeighInCard(latest: LatestCard, weightUnit: WeightUnit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Last weigh-in",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            WeightText(latest.weightCentiKg, weightUnit, 48, deltaCentiKg = latest.deltaCentiKg)
            Text(
                "${time(latest.recordedAt)} · ${sourceLabel(latest.source)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Is everything of the owner's in Garmin? Offers "Push now" whenever
 * something waits and no pass is uploading (never cancels a running pass).
 */
@Composable
private fun GarminCard(line: GarminLine, onPushNow: () -> Unit, onSignIn: () -> Unit) {
    // Same filled surface as the listening card above it: the two read as
    // one status block (scale, then Garmin).
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (line) {
                            GarminLine.Synced -> "✓ All weigh-ins are in Garmin"
                            is GarminLine.Pushing -> "Sending ${line.count} to Garmin…"
                            is GarminLine.Waiting -> when {
                                line.authBlocked > 0 && line.count > 0 -> "${line.count} waiting · ${line.authBlocked} need sign-in"
                                line.authBlocked > 0 -> "${line.authBlocked} need Garmin sign-in"
                                else -> "${line.count} waiting for Garmin"
                            }
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (line is GarminLine.Waiting && line.retrying) {
                        Text(
                            "Couldn't reach Garmin — retrying automatically",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (line is GarminLine.Waiting && line.authBlocked > 0) {
                        Text(
                            "These readings will resume after you sign in again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (line is GarminLine.Waiting && line.count > 0) {
                    FilledTonalButton(onClick = onPushNow) { Text("Push now") }
                } else if (line is GarminLine.Waiting && line.authBlocked > 0) {
                    FilledTonalButton(onClick = onSignIn) { Text("Sign in") }
                }
            }
            if (line is GarminLine.Pushing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}

/** Home's short history, newest first including the latest weigh-in. */
@Composable
private fun RecentSection(recent: List<LatestCard>, weightUnit: WeightUnit, onSeeAll: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onSeeAll) { Text("See all") }
        }
        OutlinedCard(Modifier.fillMaxWidth()) {
            recent.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${weightUnit.formatCentiKg(row.weightCentiKg)} ${weightUnit.symbol}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            time(row.recordedAt) +
                                (row.deltaCentiKg?.let { " · ${weightUnit.formatDeltaCentiKg(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Pill(row.chip.label, row.chip.tone(), compact = true)
                }
            }
        }
    }
}

/** Formats a signed change in the selected unit, without judgement. */
/** Weight with its selected unit and optional change on the same baseline. */
@Composable
private fun WeightText(
    weightCentiKg: Int,
    weightUnit: WeightUnit,
    sizeSp: Int,
    deltaCentiKg: Int? = null,
) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(weightUnit.formatCentiKg(weightCentiKg), fontSize = sizeSp.sp, fontWeight = FontWeight.SemiBold)
        Text(
            " ${weightUnit.symbol}",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = (sizeSp / 6).dp),
        )
        // Change vs the previous weigh-in, on the same baseline: short, never wraps.
        deltaCentiKg?.let {
            Text(
                "  ${weightUnit.formatDeltaCentiKg(it)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(bottom = (sizeSp / 6).dp),
            )
        }
    }
}

@Composable
private fun EmptyCard() {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("No weigh-ins yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Your first weigh-in shows up here and is sent to Garmin automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BannerFor(banner: Banner, onReview: () -> Unit, onSignIn: () -> Unit) = when (banner) {
    is Banner.Silence -> BannerCard(
        "Scale not heard for ${banner.hours} hours. If its Bluetooth icon stays solid when you " +
            "step on, take the batteries out for 10 seconds.",
        tone = Tone.WARNING,
    )
    Banner.AuthRequired -> BannerCard(
        "Garmin sign-in expired. Queued weigh-ins will resume after you sign in again.",
        tone = Tone.ERROR,
        action = "Sign in" to onSignIn,
    )
    Banner.NeedsReview -> BannerCard(
        "Someone else weighed in.",
        tone = Tone.WARNING,
        action = "Review" to onReview,
    )
}

@Composable
private fun BannerCard(text: String, tone: Tone, action: Pair<String, () -> Unit>? = null) {
    Surface(
        Modifier.fillMaxWidth(),
        color = tone.container(),
        contentColor = tone.content(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            action?.let { (label, onClick) -> FilledTonalButton(onClick = onClick) { Text(label) } }
        }
    }
}

private fun sourceLabel(source: WeighInSource): String = when (source) {
    WeighInSource.BROADCAST -> "from broadcast"
    WeighInSource.LIVE -> "measured live"
    WeighInSource.STORED -> "from scale memory"
}

private val CLOCK_FMT = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_FMT = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

/** "Today, 08:15" / "Yesterday, 21:02" / "Tue 13 Oct, 08:15". */
private fun time(unixSeconds: Long): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochSecond(unixSeconds).atZone(zone)
    val today = LocalDate.now(zone)
    return when (at.toLocalDate()) {
        today -> "Today, ${at.format(CLOCK_FMT)}"
        today.minusDays(1) -> "Yesterday, ${at.format(CLOCK_FMT)}"
        else -> at.format(DATE_FMT)
    }
}
