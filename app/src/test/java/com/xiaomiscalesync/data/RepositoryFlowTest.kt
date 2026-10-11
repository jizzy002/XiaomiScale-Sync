package com.xiaomiscalesync.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [WeighInRepository] against an in-memory fake DAO — no
 * Robolectric, no Android runtime (deliberate choice: the DAO stays thin and
 * rides the device checklist; everything decision-shaped is JVM-testable).
 */
class RepositoryFlowTest {

    private val now = 1_790_000_100L

    /** In-memory stand-in for the Room DAO; same contract, no SQLite. */
    private class FakeDao : ArchiveDao {
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
            rows.value.filter { it.garminStatus == status && it.category == category }

        override suspend fun setGarminStatus(ids: List<Long>, status: GarminStatus) {
            rows.value = rows.value.map { if (it.id in ids) it.copy(garminStatus = status) else it }
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

    private fun repo(
        dao: FakeDao = FakeDao(),
        backfillCutoff: Long? = null,
        mid: String = "1",
    ) = WeighInRepository(
        dao,
        SettingsSource { Settings(onboarded = true, backfillCutoff = backfillCutoff, mid = mid) },
        nowUnix = { now },
    ) to dao

    private fun ns(
        weightGrams: Int = 8025,
        recordedAt: Long = 1_790_000_000L,
        source: WeighInSource = WeighInSource.STORED,
        recordNo: Long? = null,
        scaleFlag: Int = 0,
        memberMid: Long = 1L,
    ) = NewWeighIn(weightGrams, recordedAt, source, recordNo, scaleFlag, memberMid)

    @Test
    fun mergeInsertsAndDedupesAcrossCalls() = runBlocking {
        val (repo, dao) = repo()
        val r1 = repo.merge(listOf(ns(recordNo = 41)))
        assertEquals(1, r1.inserted)
        assertEquals(0, r1.duplicates)

        // Same weigh-in re-fetched from the scale: one row total, recordNo kept.
        val r2 = repo.merge(listOf(ns(recordNo = 41)))
        assertEquals(0, r2.inserted)
        assertEquals(1, r2.duplicates)
        assertEquals(1, dao.rows.value.size)
        assertEquals(41L, dao.rows.value.single().recordNo)
    }

    @Test
    fun broadcastWithoutRecordNoGainsItFromStoredFetch() = runBlocking {
        val (repo, dao) = repo()
        repo.merge(listOf(ns(source = WeighInSource.BROADCAST, recordNo = null)))
        repo.merge(listOf(ns(source = WeighInSource.STORED, recordNo = 41)))
        assertEquals(1, dao.rows.value.size)
        assertEquals(41L, dao.rows.value.single().recordNo)
    }

    @Test
    fun pendingExcludesNeedsReviewAndGuest() = runBlocking {
        val (repo, _) = repo()
        repo.merge(listOf(ns(weightGrams = 8000)))                       // mine → PENDING
        repo.merge(listOf(ns(weightGrams = 8100, scaleFlag = 2, memberMid = 0)))  // needs review
        // Approve one as mine (→ pushable), keep another as guest (→ archive only).
        repo.approve(2)
        repo.merge(listOf(ns(weightGrams = 8200, scaleFlag = 2, memberMid = 0)))
        repo.keepGuest(3)

        val pending = repo.pending()
        assertEquals(listOf(8000, 8100), pending.map { it.weightGrams }) // 8200 guest never pushes
        assertTrue(pending.all { it.category == Category.MINE })
    }

    @Test
    fun approveFlipsCategoryWithoutTouchingGarminStatus() = runBlocking {
        val (repo, dao) = repo()
        repo.merge(listOf(ns(weightGrams = 7260, scaleFlag = 2, memberMid = 0)))
        assertEquals(GarminStatus.PENDING, dao.rows.value.single().garminStatus)

        repo.approve(1)
        val row = dao.rows.value.single()
        assertEquals(Category.MINE, row.category)
        assertEquals(GarminStatus.PENDING, row.garminStatus) // approval itself changes no status
        assertEquals(listOf(7260), repo.pending().map { it.weightGrams })
    }

    @Test
    fun keepGuestFlipsToGuestAndStaysOutOfPending() = runBlocking {
        val (repo, dao) = repo()
        repo.merge(listOf(ns(weightGrams = 7260, scaleFlag = 2, memberMid = 0)))
        repo.keepGuest(1)
        assertEquals(Category.GUEST, dao.rows.value.single().category)
        assertTrue(repo.pending().isEmpty())
    }

    @Test
    fun observeFiltersByCategoryAndEmits() = runBlocking {
        val (repo, _) = repo()
        repo.merge(listOf(ns(weightGrams = 8000)))
        repo.merge(listOf(ns(weightGrams = 7260, scaleFlag = 2, memberMid = 0)))
        assertEquals(2, repo.observe(null).first().size)
        assertEquals(listOf(7260), repo.observe(Category.NEEDS_REVIEW).first().map { it.weightGrams })
        assertEquals(listOf(8000), repo.observe(Category.MINE).first().map { it.weightGrams })
        assertTrue(repo.observe(Category.GUEST).first().isEmpty())
    }

    @Test
    fun backfillCutoffMakesOldRecordsSkippedExistingAndUnpushable() = runBlocking {
        val (repo, _) = repo(backfillCutoff = 1_790_000_000L)
        val r = repo.merge(listOf(ns(weightGrams = 8000, recordedAt = 1_789_999_999L)))
        assertEquals(1, r.inserted)
        assertTrue(repo.pending().isEmpty()) // archive-only, never pushed
    }

    @Test
    fun setGarminStatusFlipsRows() = runBlocking {
        val (repo, _) = repo()
        repo.merge(listOf(ns(weightGrams = 8000)))
        repo.setGarminStatus(listOf(1), GarminStatus.PUSHED)
        assertTrue(repo.pending().isEmpty())
    }

    @Test
    fun deleteAndDeleteAllTouchOnlyTheAppArchive() = runBlocking {
        val (repo, dao) = repo()
        repo.merge(listOf(ns(weightGrams = 8000), ns(weightGrams = 7260)))
        repo.delete(listOf(1))
        assertEquals(listOf(7260), dao.rows.value.map { it.weightGrams })
        repo.deleteAll()
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun scaleCleanupQueueIsHandedOutExactlyOnce() = runBlocking {
        val (repo, dao) = repo()
        repo.queueScaleCleanup(listOf(41, 42))
        repo.queueScaleCleanup(listOf(42, 43)) // 42 re-queued before the session ran
        assertEquals(listOf(41L, 42L, 43L), dao.scaleCleanup.value) // deduped, order preserved

        val taken = repo.takeScaleCleanup()
        assertEquals(listOf(41L, 42L, 43L), taken)
        assertEquals(emptyList<Long>(), dao.scaleCleanup.value)     // atomically cleared
        assertEquals(emptyList<Long>(), repo.takeScaleCleanup())    // second take is empty
    }

    @Test
    fun mergeIsBenignWhenASecondWriterWinsTheSameKey() = runBlocking {
        val (repo, dao) = repo()
        // A conflicting row for the exact dedupe key the incoming weigh-in will
        // plan as an INSERT: the second writer commits it right after the
        // merger's read snapshot — the two-concurrent-merges interleaving.
        dao.raceRow = WeighInRecord(
            id = 0, weightGrams = 8025, recordedAt = 1_790_000_000L,
            source = WeighInSource.STORED, recordNo = 41, scaleFlag = 0,
            memberMid = 1, category = Category.MINE, garminStatus = GarminStatus.PENDING,
            pushedAt = null, createdAt = now,
        )
        // Must not throw SQLiteConstraintException, must not double-insert.
        val result = repo.merge(listOf(ns()))
        assertEquals(0, result.inserted) // the conflicting insert was ignored, not counted
        val forKey = dao.rows.value.filter { it.recordedAt == 1_790_000_000L && it.weightGrams == 8025 }
        assertEquals(1, forKey.size)      // exactly one row survives the race
        assertEquals(41L, forKey.single().recordNo)
    }

    @Test
    fun settingsDefaultsToMidOneAndNullCutoff() {
        val s = Settings.DEFAULT
        assertEquals("1", s.mid)
        assertNull(s.backfillCutoff)
    }
}


