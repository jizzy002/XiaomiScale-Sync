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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.xiaomiscalesync.AppGraph
import com.xiaomiscalesync.data.WeighInSource
import com.xiaomiscalesync.sync.LiveReading
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The main weighing screen (plan Task 15, spec "Main screen"): scans only
 * while this screen is resumed (no background scanning), keeps the display
 * on while listening, and shows the listening status, the live reading
 * while connected, the newest capture with its status pill, and the
 * silence / Garmin-auth / needs-review banners. All decisions live in the
 * JVM-tested [MainUi]; this composable only renders them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onOpenDebug: () -> Unit) {
    val graph = AppGraph.of(LocalContext.current)
    val scanner = graph.scanner
    val viewModel = remember(graph) {
        MainViewModel(graph.engine.state, graph.repo.observe(null))
    }
    val ui by viewModel.uiState.collectAsState(initial = MainUiState())
    val listening by scanner.listening.collectAsState()

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

    // Scan strictly while resumed: pause / leaving the screen stops it.
    LifecycleResumeEffect(scanner, permissionTick) {
        missing = scanner.missingPermissions()
        if (missing.isEmpty() && scanner.startListening()) viewModel.onListeningStarted()
        onPauseOrDispose {
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

    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scale Sync") },
                actions = {
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text("⋮", fontSize = 20.sp) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Debug tools") },
                                onClick = {
                                    menuOpen = false
                                    onOpenDebug()
                                },
                            )
                        }
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
            if (missing.isNotEmpty()) {
                BannerCard(
                    "Bluetooth access is needed to hear the scale.",
                    tone = Tone.ERROR,
                    action = "Allow" to { launcher.launch(missing.toTypedArray()) },
                )
            }
            for (banner in ui.banners) BannerFor(banner)

            StatusCard(ui.headline, listening)
            ui.live?.let { LiveCard(it) }
            val latest = ui.latest
            if (latest != null) LatestWeighInCard(latest) else EmptyCard()
        }
    }
}

/** Colour roles for pills/banners — all taken from the Material scheme. */
private enum class Tone { PRIMARY, NEUTRAL, WARNING, ERROR }

@Composable
private fun Tone.container(): Color = when (this) {
    Tone.PRIMARY -> MaterialTheme.colorScheme.primaryContainer
    Tone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
    Tone.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
    Tone.ERROR -> MaterialTheme.colorScheme.errorContainer
}

@Composable
private fun Tone.content(): Color = when (this) {
    Tone.PRIMARY -> MaterialTheme.colorScheme.onPrimaryContainer
    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
    Tone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
}

private fun StatusChip.tone(): Tone = when (this) {
    StatusChip.PUSHED -> Tone.PRIMARY
    StatusChip.QUEUED, StatusChip.SKIPPED_EXISTING, StatusChip.GUEST -> Tone.NEUTRAL
    StatusChip.NEEDS_REVIEW -> Tone.WARNING
}

@Composable
private fun StatusCard(headline: Headline, listening: Boolean) {
    val active = listening && headline != Headline.READY
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (listening) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        ),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (listening) headline.label else "Not listening",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Text(
                when {
                    !listening -> "Bluetooth is off or access is missing."
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
        }
    }
}

@Composable
private fun LiveCard(live: LiveReading) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("On the scale", style = MaterialTheme.typography.labelLarge)
            WeightText(live.weightGrams, 56)
            Pill(
                if (live.stable) "Stable" else "Measuring…",
                if (live.stable) Tone.PRIMARY else Tone.NEUTRAL,
            )
        }
    }
}

@Composable
private fun LatestWeighInCard(latest: LatestCard) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Last weigh-in",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            WeightText(latest.weightGrams, 48)
            Text(
                "${time(latest.recordedAt)} · ${sourceLabel(latest.source)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            Pill(latest.chip.label, latest.chip.tone())
        }
    }
}

/** `84.20` large with a smaller `kg` on the same baseline. */
@Composable
private fun WeightText(weight100: Int, sizeSp: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(kg(weight100), fontSize = sizeSp.sp, fontWeight = FontWeight.SemiBold)
        Text(
            " kg",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = (sizeSp / 6).dp),
        )
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

/** A non-interactive status label (assist chips look tappable; these are not). */
@Composable
private fun Pill(text: String, tone: Tone) {
    Surface(color = tone.container(), contentColor = tone.content(), shape = RoundedCornerShape(50)) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun BannerFor(banner: Banner) = when (banner) {
    is Banner.Silence -> BannerCard(
        "Scale not heard for ${banner.hours} hours. If its Bluetooth icon stays solid when you " +
            "step on, take the batteries out for 10 seconds.",
        tone = Tone.WARNING,
    )
    Banner.AuthRequired -> BannerCard("Garmin sign-in expired — re-login in Settings", tone = Tone.ERROR)
    Banner.NeedsReview -> BannerCard("Someone else weighed in — review", tone = Tone.WARNING)
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

/** 1/100 kg → `80.25`. */
private fun kg(weight100: Int): String = String.format(Locale.US, "%.2f", weight100 / 100.0)

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
