package com.xiaomiscalesync.ui.history

import com.xiaomiscalesync.data.Category
import com.xiaomiscalesync.data.FakeDao
import com.xiaomiscalesync.data.GarminStatus
import com.xiaomiscalesync.data.NewWeighIn
import com.xiaomiscalesync.data.Settings
import com.xiaomiscalesync.data.SettingsSource
import com.xiaomiscalesync.data.WeighInRecord
import com.xiaomiscalesync.data.WeighInRepository
import com.xiaomiscalesync.data.WeighInSource
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the history screen's decisions (plan Task 16, step
 * 1): approve / keep-as-guest flip categories with the right pushability,
 * delete-all empties the archive, the cleanup dialog enqueues exactly the
 * chosen record numbers — plus the filter counts and the daily chart
 * points. Runs the real [WeighInRepository] over the shared [FakeDao].
 */
class HistoryViewModelTest {

    private val day = 1_790_000_000L // 2026-09-21 14:13:20 UTC

    private fun harness(): Triple<HistoryViewModel, WeighInRepository, FakeDao> {
        val dao = FakeDao()
        val repo = WeighInRepository(
            dao,
            SettingsSource { Settings(onboarded = true, backfillCutoff = null, mid = "1") },
            nowUnix = { day + 86_400 * 30 },
        )
        return Triple(HistoryViewModel(repo, zone = ZoneOffset.UTC), repo, dao)
    }

    private fun mine(weight: Int, at: Long, recordNo: Long? = null) =
        NewWeighIn(weight, at, WeighInSource.STORED, recordNo, scaleFlag = 0, memberMid = 1)

    private fun ambiguous(weight: Int, at: Long, recordNo: Long? = null) =
        NewWeighIn(weight, at, WeighInSource.STORED, recordNo, scaleFlag = 2, memberMid = 0)

    private suspend fun HistoryViewModel.state() = uiState.first()

    private fun List<WeighInRecord>.byWeight(w: Int) = single { it.weightGrams == w }

    // ---------------------------------------------------------- review flow

    @Test
    fun approveFlipsToMineAndBecomesPushable() = runBlocking {
        val (vm, repo, dao) = harness()
        repo.merge(listOf(ambiguous(7260, day)))
        val id = dao.rows.value.single().id
        assertTrue("needs-review is never pending", repo.pending().isEmpty())

        vm.approve(id)

        val row = dao.rows.value.single()
        assertEquals(Category.MINE, row.category)
        assertEquals(GarminStatus.PENDING, row.garminStatus)
        assertEquals(listOf(id), repo.pending().map { it.id })
    }

    @Test
    fun keepGuestFlipsToGuestAndStaysOutOfPending() = runBlocking {
        val (vm, repo, dao) = harness()
        repo.merge(listOf(ambiguous(7260, day)))
        val id = dao.rows.value.single().id

        vm.keepGuest(id)

        assertEquals(Category.GUEST, dao.rows.value.single().category)
        assertTrue(repo.pending().isEmpty())
    }

    // -------------------------------------------------------------- delete

    @Test
    fun deleteAllEmptiesTheArchive() = runBlocking {
        val (vm, repo, _) = harness()
        repo.merge(listOf(mine(8025, day), ambiguous(7260, day + 60)))

        vm.deleteAll()

        assertTrue(repo.observe(null).first().isEmpty())
        assertTrue(vm.state().rows.isEmpty())
    }

    @Test
    fun deleteSelectedRemovesOnlyTheSelectionAndClearsIt() = runBlocking {
        val (vm, repo, dao) = harness()
        repo.merge(listOf(mine(8025, day), mine(8010, day + 86_400), mine(7990, day + 2 * 86_400)))
        val target = dao.rows.value.byWeight(8010).id

        vm.toggleSelected(target)
        assertEquals(setOf(target), vm.state().selected)
        vm.deleteSelected()

        assertEquals(listOf(8025, 7990).sorted(), dao.rows.value.map { it.weightGrams }.sorted())
        assertTrue(vm.state().selected.isEmpty())
    }

    // ------------------------------------------------------------- cleanup

    @Test
    fun cleanupEnqueuesExactlyTheChosenRecordNumbers() = runBlocking {
        val (vm, repo, _) = harness()
        repo.merge(
            listOf(
                mine(8025, day, recordNo = 41),
                ambiguous(7260, day + 60, recordNo = 42),
                mine(8000, day + 120, recordNo = 43),
                mine(7990, day + 180), // broadcast-style row: no record number
            ),
        )
        assertEquals(listOf(41L, 42L, 43L), vm.state().cleanupCandidates)

        vm.queueCleanup(listOf(41L, 43L))

        assertEquals(listOf(41L, 43L), repo.takeScaleCleanup())
    }

    @Test
    fun queuedRecordNumbersShowAsQueuedAndLeaveTheCandidates() = runBlocking {
        val (vm, repo, _) = harness()
        repo.merge(listOf(mine(8025, day, recordNo = 41), mine(8000, day + 60, recordNo = 42)))

        vm.queueCleanup(listOf(41L))

        val state = vm.state()
        assertEquals(listOf(41L), state.queuedCleanup)
        assertEquals(listOf(42L), state.cleanupCandidates)
    }

    // ------------------------------------------------------ filter + chart

    @Test
    fun filterNarrowsRowsAndCountsCoverEveryCategory() = runBlocking {
        val (vm, repo, dao) = harness()
        repo.merge(listOf(mine(8025, day), mine(8010, day + 60), ambiguous(7260, day + 120)))
        vm.keepGuest(dao.rows.value.byWeight(7260).id)
        repo.merge(listOf(ambiguous(6000, day + 180)))

        val all = vm.state()
        assertEquals(
            mapOf(
                HistoryFilter.ALL to 4,
                HistoryFilter.MINE to 2,
                HistoryFilter.NEEDS_REVIEW to 1,
                HistoryFilter.GUEST to 1,
            ),
            all.counts,
        )
        assertEquals(4, all.rows.size)

        vm.setFilter(HistoryFilter.NEEDS_REVIEW)
        val review = vm.state()
        assertEquals(HistoryFilter.NEEDS_REVIEW, review.filter)
        assertEquals(listOf(6000), review.rows.map { it.weightGrams })
    }

    @Test
    fun chartHasOneDailyPointFromTheOwnersLastWeighInOfEachDay() = runBlocking {
        val (vm, repo, _) = harness()
        repo.merge(
            listOf(
                mine(8025, day),
                mine(8015, day + 3_600), // same day, later → wins
                ambiguous(6000, day + 7_200), // not the owner's → never charted
                mine(7990, day + 86_400),
            ),
        )

        val chart = vm.state().chart
        val d0 = LocalDate.of(2026, 9, 21)
        assertEquals(listOf(DailyPoint(d0, 8015), DailyPoint(d0.plusDays(1), 7990)), chart)
    }
}
