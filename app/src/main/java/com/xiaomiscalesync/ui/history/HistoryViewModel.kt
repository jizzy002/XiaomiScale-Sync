package com.xiaomiscalesync.ui.history

import com.xiaomiscalesync.data.Category
import com.xiaomiscalesync.data.WeighInRecord
import com.xiaomiscalesync.data.WeighInRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** The history filter chips (spec: All / Mine / Needs review / Guest). */
enum class HistoryFilter(val label: String, val category: Category?) {
    ALL("All", null),
    MINE("Mine", Category.MINE),
    NEEDS_REVIEW("Needs review", Category.NEEDS_REVIEW),
    GUEST("Guest", Category.GUEST),
}

/** One chart point: the owner's last weigh-in of [date] (1/100 kg). */
data class DailyPoint(val date: LocalDate, val weightCentiKg: Int)

data class HistoryUiState(
    val filter: HistoryFilter = HistoryFilter.ALL,
    /** Newest first, narrowed by [filter]. */
    val rows: List<WeighInRecord> = emptyList(),
    val counts: Map<HistoryFilter, Int> = HistoryFilter.entries.associateWith { 0 },
    /** Oldest first; MINE rows only — guests never distort the owner's trend. */
    val chart: List<DailyPoint> = emptyList(),
    val selected: Set<Long> = emptySet(),
    /** Scale record numbers known from fetches and not yet queued, ascending. */
    val cleanupCandidates: List<Long> = emptyList(),
    /** Record numbers waiting for the next session's action 6.2. */
    val queuedCleanup: List<Long> = emptyList(),
)

/**
 * History screen logic (plan Task 16): filters with counts, the daily
 * chart series, multi-select delete, the needs-review workflow
 * (approve → MINE and pushable; keep as guest → GUEST, never pushed) and
 * the scale-memory cleanup queue. Deletes here touch the app archive
 * only; scale memory is cleared solely through [queueCleanup], drained by
 * the engine's next session. Pure Kotlin over [WeighInRepository].
 */
class HistoryViewModel(
    private val repo: WeighInRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Queues a Garmin push pass (production: `PushWorker.enqueue`). */
    private val enqueuePush: () -> Unit = {},
) {
    private val filter = MutableStateFlow(HistoryFilter.ALL)
    private val selected = MutableStateFlow<Set<Long>>(emptySet())

    /** Bumped after every queue write so the queued list re-reads. */
    private val queueVersion = MutableStateFlow(0)

    private val queued: Flow<List<Long>> = queueVersion.map { repo.queuedScaleCleanup() }

    val uiState: Flow<HistoryUiState> =
        combine(repo.observe(null), filter, selected, queued) { all, f, sel, q ->
            val ids = all.mapTo(HashSet()) { it.id }
            val queuedSet = q.toSet()
            HistoryUiState(
                filter = f,
                rows = if (f.category == null) all else all.filter { it.category == f.category },
                counts = HistoryFilter.entries.associateWith { chip ->
                    if (chip.category == null) all.size else all.count { it.category == chip.category }
                },
                chart = dailyPoints(all, zone),
                // A row deleted elsewhere silently drops out of the selection.
                selected = sel intersect ids,
                cleanupCandidates = all.mapNotNull { it.recordNo }
                    .filterNot { it in queuedSet }
                    .distinct()
                    .sorted(),
                queuedCleanup = q,
            )
        }

    fun setFilter(f: HistoryFilter) {
        filter.value = f
    }

    fun toggleSelected(id: Long) {
        selected.value = selected.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        selected.value = emptySet()
    }

    /**
     * Needs review → the owner's: MINE, status untouched (PENDING), and a
     * push is queued right away instead of waiting for the next sync.
     */
    suspend fun approve(id: Long) {
        repo.approve(id)
        enqueuePush()
    }

    /** Needs review → a guest's: GUEST, archived only, never pushed. */
    suspend fun keepGuest(id: Long) = repo.keepGuest(id)

    suspend fun deleteSelected() {
        val ids = selected.value.toList()
        if (ids.isEmpty()) return
        repo.delete(ids)
        selected.value = emptySet()
    }

    suspend fun deleteAll() {
        repo.deleteAll()
        selected.value = emptySet()
    }

    /** Queues scale-memory deletion (action 6.2) for the next session. */
    suspend fun queueCleanup(recordNos: List<Long>) {
        if (recordNos.isEmpty()) return
        repo.queueScaleCleanup(recordNos)
        queueVersion.value++
    }

    companion object {
        /** The owner's (MINE) last weigh-in per local day, oldest day first. */
        fun dailyPoints(rows: List<WeighInRecord>, zone: ZoneId): List<DailyPoint> =
            rows.asSequence()
                .filter { it.category == Category.MINE }
                .groupBy { Instant.ofEpochSecond(it.recordedAt).atZone(zone).toLocalDate() }
                .map { (date, day) -> DailyPoint(date, day.maxBy { it.recordedAt }.weightCentiKg) }
                .sortedBy { it.date }
    }
}
