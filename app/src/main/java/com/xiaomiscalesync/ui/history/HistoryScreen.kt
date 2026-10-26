package com.xiaomiscalesync.ui.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xiaomiscalesync.AppGraph
import com.xiaomiscalesync.data.Category
import com.xiaomiscalesync.data.Settings
import com.xiaomiscalesync.data.WeighInRecord
import com.xiaomiscalesync.data.WeighInSource
import com.xiaomiscalesync.data.WeightUnit
import com.xiaomiscalesync.ui.formatCentiKg
import com.xiaomiscalesync.ui.main.MainUi
import com.xiaomiscalesync.ui.theme.AppIcons
import com.xiaomiscalesync.ui.theme.Pill
import com.xiaomiscalesync.ui.theme.tone
import com.xiaomiscalesync.work.PushWorker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * History (plan Task 16, spec "History"): the owner's daily weight chart,
 * filter chips with counts, the needs-review workflow (Approve as mine /
 * Keep as guest), long-press multi-select with Delete selected, and the
 * overflow's Delete all and Clean scale memory. Archive deletes never
 * touch the scale; scale memory is only ever queued for the next sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen() {
    val graph = AppGraph.of(LocalContext.current)
    val settings by graph.settings.settings.collectAsState(initial = Settings.DEFAULT)
    val appContext = LocalContext.current.applicationContext
    var activityOpen by rememberSaveable { mutableStateOf(false) }
    if (activityOpen) {
        SyncActivityScreen(graph.syncActivity) { activityOpen = false }
        return
    }
    val viewModel = remember(graph) {
        HistoryViewModel(graph.repo, enqueuePush = { PushWorker.enqueue(appContext) })
    }
    val ui by viewModel.uiState.collectAsState(initial = HistoryUiState())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var menuOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<DeleteKind?>(null) }
    var cleanupOpen by remember { mutableStateOf(false) }
    val selecting = ui.selected.isNotEmpty()

    val exportBackup =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) scope.launch {
                val result = runCatching {
                    val json = graph.repo.exportBackup()
                    withContext(Dispatchers.IO) {
                        appContext.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                            ?: error("Could not open the selected file")
                    }
                    "Backup exported. It contains weigh-ins only, not account credentials."
                }.getOrElse { "Backup export failed: ${it.message ?: "could not write file"}" }
                snackbar.showSnackbar(result)
            }
        }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = runCatching {
                val json = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 20_000_000) { "Backup file is too large" }
                            out.write(buffer, 0, count)
                        }
                        out.toString(Charsets.UTF_8.name())
                    } ?: error("Could not open the selected file")
                }
                val restored = graph.repo.restoreBackup(json)
                if (graph.repo.pending().isNotEmpty()) PushWorker.enqueue(appContext)
                "Restored $restored new ${if (restored == 1) "weigh-in" else "weigh-ins"}. Existing records were kept."
            }.getOrElse { "Restore failed: ${it.message ?: "the backup could not be read"}" }
            snackbar.showSnackbar(result)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "${ui.selected.size} selected" else "History") },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(AppIcons.Close, contentDescription = "Clear selection")
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { confirm = DeleteKind.SELECTED }) {
                            Icon(AppIcons.Delete, contentDescription = "Delete selected")
                        }
                    } else {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(AppIcons.More, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Sync activity") },
                                    onClick = {
                                        menuOpen = false
                                        activityOpen = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Export archive backup…") },
                                    enabled = ui.counts.getValue(HistoryFilter.ALL) > 0,
                                    onClick = {
                                        menuOpen = false
                                        exportBackup.launch("xiaomi-scale-sync-backup.json")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Restore archive backup…") },
                                    onClick = {
                                        menuOpen = false
                                        importBackup.launch(arrayOf("application/json", "text/json", "text/plain"))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Clean scale memory…") },
                                    onClick = {
                                        menuOpen = false
                                        cleanupOpen = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete all…") },
                                    enabled = ui.counts.getValue(HistoryFilter.ALL) > 0,
                                    onClick = {
                                        menuOpen = false
                                        confirm = DeleteKind.ALL
                                    },
                                )
                            }
                        }
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
            item(key = "chart") { ChartCard(ui.chart, settings.weightUnit) }
            item(key = "filters") {
                FilterRow(ui.filter, ui.counts, viewModel::setFilter)
            }
            if (ui.queuedCleanup.isNotEmpty()) {
                item(key = "queued") {
                    Text(
                        "Scale cleanup queued for next sync: ${ui.queuedCleanup.joinToString { "#$it" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (ui.rows.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nothing here yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(ui.rows, key = { it.id }) { row ->
                WeighInRow(
                    row = row,
                    weightUnit = settings.weightUnit,
                    selected = row.id in ui.selected,
                    selecting = selecting,
                    onToggle = { viewModel.toggleSelected(row.id) },
                    onApprove = { scope.launch { viewModel.approve(row.id) } },
                    onKeepGuest = { scope.launch { viewModel.keepGuest(row.id) } },
                )
            }
        }
    }

    confirm?.let { kind ->
        val count = if (kind == DeleteKind.ALL) ui.counts.getValue(HistoryFilter.ALL) else ui.selected.size
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (kind == DeleteKind.ALL) "Delete all weigh-ins?" else "Delete $count weigh-in(s)?") },
            text = {
                Text(
                    "Removes ${if (kind == DeleteKind.ALL) "all $count" else "$count"} from this app's archive. " +
                            "Garmin Connect and the scale's own memory are not affected.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        if (kind == DeleteKind.ALL) viewModel.deleteAll() else viewModel.deleteSelected()
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }

    if (cleanupOpen) {
        ScaleCleanupDialog(
            candidates = ui.cleanupCandidates,
            queued = ui.queuedCleanup,
            onQueue = { nos ->
                cleanupOpen = false
                scope.launch { viewModel.queueCleanup(nos) }
            },
            onDismiss = { cleanupOpen = false },
        )
    }
}

private enum class DeleteKind { SELECTED, ALL }

@Composable
private fun FilterRow(
    current: HistoryFilter,
    counts: Map<HistoryFilter, Int>,
    onSelect: (HistoryFilter) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (f in HistoryFilter.entries) {
            FilterChip(
                selected = f == current,
                onClick = { onSelect(f) },
                label = { Text("${f.label} ${counts.getValue(f)}") },
            )
        }
    }
}

/** Plain Canvas line chart of the owner's daily weights (zero extra deps). */
@Composable
private fun ChartCard(points: List<DailyPoint>, weightUnit: WeightUnit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Daily weight",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (points.size < 2) {
                Text(
                    "The trend appears after two days of your weigh-ins.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                return@Column
            }
            val min = points.minOf { it.weightCentiKg }
            val max = points.maxOf { it.weightCentiKg }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    weightUnit.formatCentiKg(points.last().weightCentiKg),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(" ${weightUnit.symbol}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                Spacer(Modifier.weight(1f))
                Text(
                    "${weightUnit.formatCentiKg(min)}–${weightUnit.formatCentiKg(max)} ${weightUnit.symbol}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val line = MaterialTheme.colorScheme.primary
            val grid = MaterialTheme.colorScheme.outlineVariant
            Canvas(Modifier.fillMaxWidth().height(140.dp).padding(vertical = 12.dp)) {
                // Pad the value range so a flat week is not drawn as a wall.
                val span = (max - min).coerceAtLeast(100).toFloat()
                val lo = min - span * 0.15f
                val hi = max + span * 0.15f
                val first = points.first().date.toEpochDay()
                val days = (points.last().date.toEpochDay() - first).coerceAtLeast(1).toFloat()
                fun at(p: DailyPoint) = Offset(
                    x = (p.date.toEpochDay() - first) / days * size.width,
                    y = size.height - (p.weightCentiKg - lo) / (hi - lo) * size.height,
                )
                for (i in 0..2) {
                    val y = size.height * i / 2f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                val path = Path().apply {
                    points.forEachIndexed { i, p ->
                        at(p).let {
                            if (i == 0) moveTo(it.x, it.y) else lineTo(
                                it.x,
                                it.y
                            )
                        }
                    }
                }
                drawPath(path, line, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawCircle(line, radius = 4.dp.toPx(), center = at(points.last()))
            }
            Row {
                Text(points.first().date.format(DAY_FMT), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text(points.last().date.format(DAY_FMT), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WeighInRow(
    row: WeighInRecord,
    weightUnit: WeightUnit,
    selected: Boolean,
    selecting: Boolean,
    onToggle: () -> Unit,
    onApprove: () -> Unit,
    onKeepGuest: () -> Unit,
) {
    val chip = MainUi.statusChip(row.category, row.garminStatus)
    Card(
        Modifier.fillMaxWidth().combinedClickable(
            // Tap toggles only once a selection is active; long-press starts it.
            onClick = { if (selecting) onToggle() },
            onLongClick = onToggle,
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selecting) {
                    Checkbox(checked = selected, onCheckedChange = { onToggle() })
                    Spacer(Modifier.width(4.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "${weightUnit.formatCentiKg(row.weightCentiKg)} ${weightUnit.symbol}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${time(row.recordedAt)} · ${sourceLabel(row.source)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Pill(chip.label, chip.tone(), compact = true)
            }
            if (row.category == Category.NEEDS_REVIEW && !selecting) {
                Text(
                    reviewExplanation(row),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = onApprove) { Text("This was me") }
                    OutlinedButton(onClick = onKeepGuest) { Text("Keep as guest") }
                }
            }
        }
    }
}

private fun reviewExplanation(row: WeighInRecord): String = when {
    row.scaleFlag == 2 && row.memberMid == 0L ->
        "The scale marked this reading as unassigned and did not include a member ID. It will only go to Garmin if you confirm it was yours."

    row.memberMid == 0L ->
        "The scale did not include a member ID for this reading. It will only go to Garmin if you confirm it was yours."

    row.scaleFlag != 0 ->
        "The scale returned flag ${row.scaleFlag} for member ${row.memberMid}, so the app could not identify the owner automatically."

    else ->
        "The scale assigned member ${row.memberMid}, which could not be matched to your profile. Check Profile → Your profile → Advanced if this keeps happening."
}

private fun sourceLabel(source: WeighInSource): String = when (source) {
    WeighInSource.BROADCAST -> "broadcast"
    WeighInSource.LIVE -> "live"
    WeighInSource.STORED -> "scale memory"
}

private val DAY_FMT = DateTimeFormatter.ofPattern("d MMM")
private val ROW_FMT = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")

private fun time(unixSeconds: Long): String =
    Instant.ofEpochSecond(unixSeconds).atZone(ZoneId.systemDefault()).format(ROW_FMT)
