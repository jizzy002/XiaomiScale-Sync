package com.xiaomiscalesync.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xiaomiscalesync.data.SyncActivityEvent
import com.xiaomiscalesync.data.SyncActivityStore
import com.xiaomiscalesync.data.SyncEventOutcome
import com.xiaomiscalesync.data.SyncFailureCategory
import com.xiaomiscalesync.data.SyncService
import com.xiaomiscalesync.ui.theme.AppIcons
import com.xiaomiscalesync.ui.theme.Pill
import com.xiaomiscalesync.ui.theme.Tone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncActivityScreen(store: SyncActivityStore, onBack: () -> Unit) {
    val events by store.events.collectAsState(initial = emptyList())
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync activity") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.Close, contentDescription = "Back to History")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (events.isEmpty()) {
                item {
                    Text(
                        "Completed scale checks and Garmin upload attempts will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(events, key = { it.id }) { event -> ActivityRow(event) }
        }
    }
}

@Composable
private fun ActivityRow(event: SyncActivityEvent) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (event.service == SyncService.SCALE) "Scale check" else "Garmin upload",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        activityTime(event.occurredAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Pill(event.outcome.label(), event.outcome.tone(), compact = true)
            }
            Text(
                event.description(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun SyncEventOutcome.label(): String = when (this) {
    SyncEventOutcome.COMPLETED -> "Completed"
    SyncEventOutcome.SCALE_NOT_FOUND -> "Scale not found"
    SyncEventOutcome.FAILED -> "Failed"
    SyncEventOutcome.AUTH_REQUIRED -> "Sign-in needed"
    SyncEventOutcome.RETRYING -> "Will retry"
}

private fun SyncEventOutcome.tone(): Tone = when (this) {
    SyncEventOutcome.COMPLETED -> Tone.PRIMARY
    SyncEventOutcome.SCALE_NOT_FOUND -> Tone.NEUTRAL
    SyncEventOutcome.FAILED, SyncEventOutcome.AUTH_REQUIRED -> Tone.ERROR
    SyncEventOutcome.RETRYING -> Tone.WARNING
}

private fun SyncActivityEvent.description(): String = when (service) {
    SyncService.SCALE -> when (outcome) {
        SyncEventOutcome.COMPLETED -> "${readings} ${if (readings == 1) "reading" else "readings"} collected · ${attempts} ${if (attempts == 1) "attempt" else "attempts"}"
        SyncEventOutcome.SCALE_NOT_FOUND -> "No scale found after ${attempts} ${if (attempts == 1) "attempt" else "attempts"}."
        else -> "Scale connection or protocol issue after ${attempts} ${if (attempts == 1) "attempt" else "attempts"}."
    }
    SyncService.GARMIN -> when (outcome) {
        SyncEventOutcome.COMPLETED -> when {
            pushed > 0 && skipped > 0 -> "$pushed sent · $skipped already in Garmin"
            pushed > 0 -> "$pushed ${if (pushed == 1) "weigh-in" else "weigh-ins"} sent"
            skipped > 0 -> "$skipped already in Garmin"
            else -> "No weigh-ins needed uploading"
        }
        SyncEventOutcome.AUTH_REQUIRED -> "Sign in to Garmin Connect to resume uploads."
        SyncEventOutcome.RETRYING -> when (failure) {
            SyncFailureCategory.RATE_LIMITED -> "Garmin rate limited the request; it will retry automatically."
            SyncFailureCategory.NETWORK -> "Network request failed; it will retry automatically."
            SyncFailureCategory.SERVICE -> "Garmin returned an error; it will retry automatically."
            SyncFailureCategory.AUTHENTICATION -> "Garmin sign-in is required."
            SyncFailureCategory.SCALE_CONNECTION -> "Scale connection issue."
            SyncFailureCategory.NONE, SyncFailureCategory.UNKNOWN -> "Upload failed; it will retry automatically."
        }
        else -> "Upload did not complete."
    }
}

private fun activityTime(seconds: Long): String =
    Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.getDefault()))
