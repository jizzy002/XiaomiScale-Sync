package com.xiaomiscalesync.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory stand-in for the Room DAO — same contract, no SQLite. Extracted
 * from `RepositoryFlowTest` (Task 8) so the repository tests (Task 8), the
 * Garmin pusher tests (Task 12) and beyond all exercise the exact same fake.
 *
 * Ordering follows the real DAO's SQL: `rowsWith` is `ORDER BY recordedAt
 * ASC` (stable, so insertion order survives timestamp ties), `observeAll` is
 * `ORDER BY recordedAt DESC`. The unique index on (recordedAt, weightGrams)
 * is enforced in [insert] with IGNORE semantics — a conflict returns -1 and
 * stores nothing, never a throw.
 */
class FakeDao : ArchiveDao {
    private var nextId = 1L
    val rows = MutableStateFlow<List<WeighInRecord>>(emptyList())
    val scaleCleanup = MutableStateFlow<List<Long>>(emptyList())

    /**
     * Race simulation: when set, this row is committed by a "second writer"
     * immediately after the next [all] snapshot is taken — i.e. between the
     * merger's read and the repository's writes, the exact interleaving of
     * two concurrent merge() calls planning the same key.
     */
    var raceRow: WeighInRecord? = null

    override fun observeAll(): Flow<List<WeighInRecord>> =
        rows.map { list -> list.sortedByDescending { it.recordedAt } }

    override fun observeByCategory(category: Category): Flow<List<WeighInRecord>> =
        rows.map { list -> list.filter { it.category == category }.sortedByDescending { it.recordedAt } }

    override suspend fun all(): List<WeighInRecord> {
        val snapshot = rows.value // the read both mergers plan against
        raceRow?.let { r ->
            raceRow = null
            rows.value = rows.value + r.copy(id = nextId++) // second writer lands
        }
        return snapshot
    }

    override suspend fun insert(record: WeighInRecord): Long {
        // Unique index on (recordedAt, weightGrams) with OnConflictStrategy.IGNORE:
        // a conflicting row is silently not inserted and -1 is returned, never a throw.
        val conflict = rows.value.any { it.recordedAt == record.recordedAt && it.weightGrams == record.weightGrams }
        if (conflict) return -1L
        val id = nextId++
        rows.value = rows.value + record.copy(id = id)
        return id
    }

    override suspend fun fillRecordNo(id: Long, recordNo: Long) {
        rows.value = rows.value.map { if (it.id == id && it.recordNo == null) it.copy(recordNo = recordNo) else it }
    }

    override suspend fun setCategory(id: Long, category: Category) {
        rows.value = rows.value.map { if (it.id == id) it.copy(category = category) else it }
    }

    override suspend fun delete(ids: List<Long>) {
        rows.value = rows.value.filterNot { it.id in ids }
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }

    override suspend fun rowsWith(status: GarminStatus, category: Category): List<WeighInRecord> =
        rows.value
            .filter { it.garminStatus == status && it.category == category }
            .sortedBy { it.recordedAt } // matches ORDER BY recordedAt ASC; stable on ties

    override suspend fun setGarminStatus(ids: List<Long>, status: GarminStatus) {
        rows.value = rows.value.map { if (it.id in ids) it.copy(garminStatus = status) else it }
    }

    override suspend fun setGarminStatusAt(ids: List<Long>, status: GarminStatus, pushedAt: Long) {
        rows.value = rows.value.map { if (it.id in ids) it.copy(garminStatus = status, pushedAt = pushedAt) else it }
    }

    override suspend fun queueCleanup(items: List<ScaleCleanupItem>) {
        val known = scaleCleanup.value.toMutableSet() // primary key + IGNORE, as Room will do
        scaleCleanup.value = scaleCleanup.value + items.filter { known.add(it.recordNo) }.map { it.recordNo }
    }

    override suspend fun queuedCleanup(): List<ScaleCleanupItem> =
        scaleCleanup.value.map { ScaleCleanupItem(recordNo = it) }

    override suspend fun clearCleanup() {
        scaleCleanup.value = emptyList()
    }
}
