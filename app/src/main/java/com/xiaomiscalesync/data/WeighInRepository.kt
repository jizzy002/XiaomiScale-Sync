package com.xiaomiscalesync.data

import kotlinx.coroutines.flow.Flow

/**
 * The archive's single write/read gate: SyncEngine (Task 9), the Garmin
 * pusher (Task 12) and the history UI (Task 16) all go through this.
 *
 * Merge/classification decisions are delegated to the pure [ArchiveMerger];
 * this class reads settings, applies the resulting [MergePlan] to Room, and
 * exposes the queue/filter operations the other layers consume.
 */
class WeighInRepository(
    private val dao: ArchiveDao,
    private val settings: SettingsSource,
    private val nowUnix: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    /** Archives [incoming], deduping against what is already stored. */
    suspend fun merge(incoming: List<NewWeighIn>): MergeResult {
        val s = settings.current()
        val plan = ArchiveMerger.merge(
            existing = dao.all(),
            incoming = incoming,
            settings = MergeSettings(mid = s.mid.toLongOrNull() ?: 1L, backfillCutoff = s.backfillCutoff),
            nowUnix = nowUnix(),
        )
        // Inserts are IGNORE-on-conflict: a concurrent merge that won the race
        // for the same (recordedAt, weightGrams) key makes Room return -1 here
        // rather than throw — count only the rows that actually landed.
        var inserted = 0
        for (row in plan.inserts) if (dao.insert(row) != -1L) inserted++
        for (f in plan.fills) dao.fillRecordNo(f.id, f.recordNo)
        return MergeResult(inserted = inserted, duplicates = plan.duplicates)
    }

    fun observe(category: Category?): Flow<List<WeighInRecord>> =
        if (category == null) dao.observeAll() else dao.observeByCategory(category)

    /** Review action: approve a needs-review row as the owner's own (→ pushable). */
    suspend fun approve(id: Long) = dao.setCategory(id, Category.MINE)

    /** Review action: keep a row as a guest's (archive only, never pushed). */
    suspend fun keepGuest(id: Long) = dao.setCategory(id, Category.GUEST)

    /** Deletes from the app archive only — never touches the scale's memory. */
    suspend fun delete(ids: List<Long>) = dao.delete(ids)

    /** Deletes the whole app archive — never touches the scale's memory. */
    suspend fun deleteAll() = dao.deleteAll()

    /** Rows ready for Garmin: PENDING and MINE. Feeds the Task 12 pusher. */
    suspend fun pending(): List<WeighInRecord> =
        dao.rowsWith(status = GarminStatus.PENDING, category = Category.MINE)

    suspend fun setGarminStatus(ids: List<Long>, s: GarminStatus) = dao.setGarminStatus(ids, s)

    /**
     * After a Garmin re-login: rows the expired sign-in blocked go back to
     * PENDING (the duplicate guard still runs before each push). Returns
     * how many were re-queued.
     */
    suspend fun requeueAuthBlocked(): Int {
        val ids = dao.rowsWith(status = GarminStatus.AUTH_REQUIRED, category = Category.MINE).map { it.id }
        if (ids.isNotEmpty()) dao.setGarminStatus(ids, GarminStatus.PENDING)
        return ids.size
    }

    /** True when any owner row is blocked on an expired Garmin sign-in. */
    suspend fun hasAuthBlocked(): Boolean =
        dao.rowsWith(status = GarminStatus.AUTH_REQUIRED, category = Category.MINE).isNotEmpty()

    /** Marks rows PUSHED and stamps [pushedAt] (unix seconds) in one write. */
    suspend fun markPushed(ids: List<Long>, pushedAt: Long) =
        dao.setGarminStatusAt(ids, GarminStatus.PUSHED, pushedAt)

    /** Enqueues scale-memory record numbers for the next session's action 6.2. */
    suspend fun queueScaleCleanup(recordNos: List<Long>) =
        dao.queueCleanup(recordNos.map { ScaleCleanupItem(recordNo = it) })

    /** Peeks at the scale-cleanup queue without draining it (history UI: "queued for next sync"). */
    suspend fun queuedScaleCleanup(): List<Long> = dao.queuedCleanup().map { it.recordNo }

    /** Drains the scale-cleanup queue: each record number is handed out exactly once. */
    suspend fun takeScaleCleanup(): List<Long> = dao.takeScaleCleanup()
}
