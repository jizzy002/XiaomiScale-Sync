package com.xiaomiscalesync.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * "Clean scale memory" (plan Task 16): pick record numbers known from
 * stored-record fetches — or all of them — to delete from the *scale's*
 * memory. Nothing is sent now: the choice is queued and the next sync
 * session drains it with action 6.2 (the scale is asleep between
 * weigh-ins, and connecting just for this is exactly what the connect
 * discipline forbids).
 */
@Composable
fun ScaleCleanupDialog(
    candidates: List<Long>,
    queued: List<Long>,
    onQueue: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember(candidates) { mutableStateOf(emptySet<Long>()) }
    val allChosen = candidates.isNotEmpty() && chosen.size == candidates.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clean scale memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "This clears the scale's own memory. Your app archive is not affected.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (queued.isNotEmpty()) {
                    Text(
                        "Queued for next sync: ${queued.joinToString { "#$it" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (candidates.isEmpty()) {
                    Text(
                        "No stored scale records are known yet — they appear after a sync fetches them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    CheckRow("All stored records (${candidates.size})", allChosen) {
                        chosen = if (allChosen) emptySet() else candidates.toSet()
                    }
                    HorizontalDivider()
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(candidates, key = { it }) { no ->
                            CheckRow("Record #$no", no in chosen) {
                                chosen = if (no in chosen) chosen - no else chosen + no
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = chosen.isNotEmpty(),
                onClick = { onQueue(candidates.filter { it in chosen }) },
            ) { Text("Queue ${chosen.size.takeIf { it > 0 } ?: ""}".trim()) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
