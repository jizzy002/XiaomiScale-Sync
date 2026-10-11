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
        for (row in plan.inserts) dao.insert(row)
        for (f in plan.fills) dao.fillRecordNo(f.id, f.recordNo)
        return MergeResult(inserted = plan.inserts.size, duplicates = plan.duplicates)
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

    /** Enqueues scale-memory record numbers for the next session's action 6.2. */
    suspend fun queueScaleCleanup(recordNos: List<Long>) =
        dao.queueCleanup(recordNos.map { ScaleCleanupItem(recordNo = it) })

    /** Drains the scale-cleanup queue: each record number is handed out exactly once. */
    suspend fun takeScaleCleanup(): List<Long> = dao.takeScaleCleanup()
}
