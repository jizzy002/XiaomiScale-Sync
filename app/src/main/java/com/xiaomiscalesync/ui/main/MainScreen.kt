package com.xiaomiscalesync.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The main weighing screen (plan Task 15, spec "Main screen"): scans only
 * while this screen is resumed (no background scanning), keeps the display
 * on while listening, and shows the headline state, the live reading
 * while connected, the newest capture with its source/status chips, and
 * the silence / Garmin-auth / needs-review banners. All decisions live in
 * the JVM-tested [MainUi]; this composable only renders them.
 */
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Xiaomi Scale → Garmin", style = MaterialTheme.typography.titleLarge)

        if (missing.isNotEmpty()) {
            BannerCard(
                "Bluetooth access is needed to hear the scale.",
                action = "Grant" to { launcher.launch(missing.toTypedArray()) },
            )
        }
        for (banner in ui.banners) BannerFor(banner)

        HeadlineCard(ui.headline, listening)
        ui.live?.let { LiveCard(it) }
        ui.latest?.let { LatestWeighInCard(it) }

        TextButton(onClick = onOpenDebug, modifier = Modifier.align(Alignment.End)) {
            Text("Debug")
        }
    }
}

@Composable
private fun HeadlineCard(headline: Headline, listening: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(headline.label, style = MaterialTheme.typography.headlineSmall)
            Text(
                when {
                    !listening -> "Not listening — Bluetooth is off or access is missing."
                    headline == Headline.SYNCING -> "Talking to the scale — stay close."
                    else -> "Step on the scale with this screen open."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (headline == Headline.SYNCING) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LiveCard(live: LiveReading) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${kg(live.weightGrams)} kg",
                fontSize = 40.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(if (live.stable) "✓ stable" else "measuring…", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun LatestWeighInCard(latest: LatestCard) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Last weigh-in", style = MaterialTheme.typography.labelLarge)
            Text("${kg(latest.weightGrams)} kg", style = MaterialTheme.typography.displaySmall)
            Text(time(latest.recordedAt), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(sourceLabel(latest.source)) })
                AssistChip(onClick = {}, label = { Text("Recorded") })
                AssistChip(onClick = {}, label = { Text(latest.chip.label) })
            }
        }
    }
}

@Composable
private fun BannerFor(banner: Banner) = when (banner) {
    is Banner.Silence -> BannerCard(
        "Scale not heard for ${banner.hours} hours. If its Bluetooth icon stays solid when you " +
            "step on, take the batteries out for 10 seconds.",
    )
    Banner.AuthRequired -> BannerCard("Garmin sign-in expired — re-login in Settings")
    Banner.NeedsReview -> BannerCard("Someone else weighed in — review")
}

@Composable
private fun BannerCard(text: String, action: Pair<String, () -> Unit>? = null) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            action?.let { (label, onClick) -> Button(onClick = onClick) { Text(label) } }
        }
    }
}

private fun sourceLabel(source: WeighInSource): String = when (source) {
    WeighInSource.BROADCAST -> "broadcast"
    WeighInSource.LIVE -> "live"
    WeighInSource.STORED -> "stored"
}

/** 1/100 kg → `80.25`. */
private fun kg(weight100: Int): String = String.format(Locale.US, "%.2f", weight100 / 100.0)

private val TIME_FMT = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

private fun time(unixSeconds: Long): String =
    Instant.ofEpochSecond(unixSeconds).atZone(ZoneId.systemDefault()).format(TIME_FMT)
