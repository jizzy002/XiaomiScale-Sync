package com.xiaomiscalesync.data

import kotlinx.coroutines.flow.first
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

    /** The shared in-memory [FakeDao] harness lives next to the DAO's package. */

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
        weightCentiKg: Int = 8025,
        recordedAt: Long = 1_790_000_000L,
        source: WeighInSource = WeighInSource.STORED,
        recordNo: Long? = null,
        scaleFlag: Int = 0,
        memberMid: Long = 1L,
    ) = NewWeighIn(weightCentiKg, recordedAt, source, recordNo, scaleFlag, memberMid)

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
        repo.merge(listOf(ns(weightCentiKg = 8000)))                       // mine → PENDING
        repo.merge(listOf(ns(weightCentiKg = 8100, scaleFlag = 2, memberMid = 0)))  // needs review
        // Approve one as mine (→ pushable), keep another as guest (→ archive only).
        repo.approve(2)
        repo.merge(listOf(ns(weightCentiKg = 8200, scaleFlag = 2, memberMid = 0)))
        repo.keepGuest(3)

        val pending = repo.pending()
        assertEquals(listOf(8000, 8100), pending.map { it.weightCentiKg }) // 8200 guest never pushes
        assertTrue(pending.all { it.category == Category.MINE })
    }

    @Test
    fun approveFlipsCategoryWithoutTouchingGarminStatus() = runBlocking {
        val (repo, dao) = repo()
        repo.merge(listOf(ns(weightCentiKg = 7260, scaleFlag = 2, memberMid = 0)))
        assertEquals(GarminStatus.PENDING, dao.rows.value.single().garminStatus)

        repo.approve(1)
        val row = dao.rows.value.single()
        assertEquals(Category.MINE, row.category)
        assertEquals(GarminStatus.PENDING, row.garminStatus) // approval itself changes no status
        assertEquals(listOf(7260), repo.pending().map { it.weightCentiKg })
    }

    @Test
    fun keepGuestFlipsToGuestAndStaysOutOfPending() = runBlocking {
        val (repo, dao) = repo()
        repo.merge(listOf(ns(weightCentiKg = 7260, scaleFlag = 2, memberMid = 0)))
        repo.keepGuest(1)
        assertEquals(Category.GUEST, dao.rows.value.single().category)
        assertTrue(repo.pending().isEmpty())
    }

    @Test
    fun observeFiltersByCategoryAndEmits() = runBlocking {
        val (repo, _) = repo()
        repo.merge(listOf(ns(weightCentiKg = 8000)))
        repo.merge(listOf(ns(weightCentiKg = 7260, scaleFlag = 2, memberMid = 0)))
        assertEquals(2, repo.observe(null).first().size)
        assertEquals(listOf(7260), repo.observe(Category.NEEDS_REVIEW).first().map { it.weightCentiKg })
        assertEquals(listOf(8000), repo.observe(Category.MINE).first().map { it.weightCentiKg })
        assertTrue(repo.observe(Category.GUEST).first().isEmpty())
    }

    @Test
    fun backfillCutoffMakesOldRecordsSkippedExistingAndUnpushable() = runBlocking {
        val (repo, _) = repo(backfillCutoff = 1_790_000_000L)
        val r = repo.merge(listOf(ns(weightCentiKg = 8000, recordedAt = 1_789_999_999L)))
        assertEquals(1, r.inserted)
        assertTrue(repo.pending().isEmpty()) // archive-only, never pushed
    }

    @Test
    fun setGarminStatusFlipsRows() = runBlocking {
        val (repo, _) = repo()
        repo.merge(listOf(ns(weightCentiKg = 8000)))
        repo.setGarminStatus(listOf(1), GarminStatus.PUSHED)
        assertTrue(repo.pending().isEmpty())
    }

    @Test
    fun deleteAndDeleteAllTouchOnlyTheAppArchive() = runBlocking {
        val (repo, dao) = repo()
        repo.merge(listOf(ns(weightCentiKg = 8000), ns(weightCentiKg = 7260)))
        repo.delete(listOf(1))
        assertEquals(listOf(7260), dao.rows.value.map { it.weightCentiKg })
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
            id = 0, weightCentiKg = 8025, recordedAt = 1_790_000_000L,
            source = WeighInSource.STORED, recordNo = 41, scaleFlag = 0,
            memberMid = 1, category = Category.MINE, garminStatus = GarminStatus.PENDING,
            pushedAt = null, createdAt = now,
        )
        // Must not throw SQLiteConstraintException, must not double-insert.
        val result = repo.merge(listOf(ns()))
        assertEquals(0, result.inserted) // the conflicting insert was ignored, not counted
        val forKey = dao.rows.value.filter { it.recordedAt == 1_790_000_000L && it.weightCentiKg == 8025 }
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


