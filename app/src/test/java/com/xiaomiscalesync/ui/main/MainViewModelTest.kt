package com.xiaomiscalesync.ui.main

import com.xiaomiscalesync.data.Category
import com.xiaomiscalesync.data.GarminStatus
import com.xiaomiscalesync.data.WeighInRecord
import com.xiaomiscalesync.data.WeighInSource
import com.xiaomiscalesync.sync.EnginePhase
import com.xiaomiscalesync.sync.EngineState
import com.xiaomiscalesync.sync.LiveReading
import com.xiaomiscalesync.sync.SyncKind
import com.xiaomiscalesync.sync.SyncResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the main weighing screen's decisions (plan Task 15,
 * step 1): the 12 h silence banner boundary, the status-chip mapping for
 * every Category × GarminStatus pair, the headline per engine phase, and
 * the auth / needs-review banners. A fake engine state stands in for
 * [com.xiaomiscalesync.sync.SyncEngine]; no Android runtime.
 */
class MainViewModelTest {

    private val hourMs = 3_600_000L
    private val t0 = 1_790_000_000_000L

    private fun record(
        id: Long = 1,
        recordedAt: Long = 1_790_000_000,
        category: Category = Category.MINE,
        status: GarminStatus = GarminStatus.PENDING,
        source: WeighInSource = WeighInSource.BROADCAST,
    ) = WeighInRecord(
        id = id,
        weightGrams = 8025,
        recordedAt = recordedAt,
        source = source,
        recordNo = null,
        scaleFlag = 0,
        memberMid = 1,
        category = category,
        garminStatus = status,
        createdAt = recordedAt,
    )

    // ------------------------------------------------------ silence banner

    @Test
    fun silenceBannerAppearsAtExactlyTwelveHoursOfListening() {
        val engine = EngineState() // never heard
        val at1159 = MainUi.reduce(engine, emptyList(), listeningSinceMs = t0, nowMs = t0 + 11 * hourMs + 59 * 60_000)
        assertTrue(at1159.banners.none { it is Banner.Silence })

        val at1200 = MainUi.reduce(engine, emptyList(), listeningSinceMs = t0, nowMs = t0 + 12 * hourMs)
        assertEquals(listOf(Banner.Silence(12)), at1200.banners.filterIsInstance<Banner.Silence>())
    }

    @Test
    fun silenceCountsFromTheLastAdvertisementWhenItIsNewer() {
        val heard = EngineState(scaleSeen = true, lastHeardAt = t0 + 5 * hourMs)
        val now = t0 + 16 * hourMs // 16 h listening, but heard 11 h ago
        val s = MainUi.reduce(heard, emptyList(), listeningSinceMs = t0, nowMs = now)
        assertTrue(s.banners.none { it is Banner.Silence })

        val later = MainUi.reduce(heard, emptyList(), listeningSinceMs = t0, nowMs = t0 + 17 * hourMs)
        assertEquals(listOf(Banner.Silence(12)), later.banners.filterIsInstance<Banner.Silence>())
    }

    @Test
    fun noSilenceBannerWhileNotListening() {
        val s = MainUi.reduce(EngineState(), emptyList(), listeningSinceMs = null, nowMs = t0 + 48 * hourMs)
        assertTrue(s.banners.isEmpty())
    }

    // --------------------------------------------------------- status chips

    @Test
    fun statusChipCoversEveryCategoryAndStatus() {
        val expected = mapOf(
            (Category.MINE to GarminStatus.PENDING) to StatusChip.QUEUED,
            (Category.MINE to GarminStatus.PUSHED) to StatusChip.PUSHED,
            (Category.MINE to GarminStatus.SKIPPED_EXISTING) to StatusChip.SKIPPED_EXISTING,
            (Category.MINE to GarminStatus.AUTH_REQUIRED) to StatusChip.QUEUED,
        )
        for ((key, chip) in expected) {
            assertEquals(key.toString(), chip, MainUi.statusChip(key.first, key.second))
        }
        for (status in GarminStatus.entries) {
            val review = MainUi.statusChip(Category.NEEDS_REVIEW, status)
            assertEquals(StatusChip.NEEDS_REVIEW, review)
            assertNotEquals("needs-review is never shown as pushed", StatusChip.PUSHED, review)
            assertEquals(StatusChip.GUEST, MainUi.statusChip(Category.GUEST, status))
        }
    }

    @Test
    fun chipLabelsUseTheSpecCopy() {
        assertEquals("Queued", StatusChip.QUEUED.label)
        assertEquals("Pushed to Garmin", StatusChip.PUSHED.label)
        assertEquals("Skipped (already in Garmin)", StatusChip.SKIPPED_EXISTING.label)
        assertEquals("Needs review", StatusChip.NEEDS_REVIEW.label)
    }

    // ------------------------------------------------------------ headline

    @Test
    fun headlineFollowsEnginePhase() {
        fun headline(engine: EngineState) = MainUi.reduce(engine, emptyList(), t0, t0).headline

        assertEquals(Headline.READY, headline(EngineState()))
        assertEquals(
            Headline.SCALE_SEEN,
            headline(EngineState(phase = EnginePhase.Listening, scaleSeen = true, lastHeardAt = t0 - 1_000)),
        )
        assertEquals(
            "a sighting long ago is not 'seen' any more",
            Headline.READY,
            headline(EngineState(phase = EnginePhase.Listening, scaleSeen = true, lastHeardAt = t0 - hourMs)),
        )
        assertEquals(Headline.SYNCING, headline(EngineState(phase = EnginePhase.Connecting, scaleSeen = true)))
        assertEquals(
            Headline.READY,
            headline(EngineState(phase = EnginePhase.LastResult(0), lastResult = SyncResult(SyncKind.OK))),
        )
    }

    // ------------------------------------------------------ latest + banners

    @Test
    fun latestCardIsTheNewestRecord() {
        val records = listOf(
            record(id = 1, recordedAt = 100, status = GarminStatus.PUSHED),
            record(id = 2, recordedAt = 300, source = WeighInSource.LIVE),
            record(id = 3, recordedAt = 200),
        )
        val latest = MainUi.reduce(EngineState(), records, t0, t0).latest!!
        assertEquals(2L, latest.id)
        assertEquals(WeighInSource.LIVE, latest.source)
        assertEquals(StatusChip.QUEUED, latest.chip)
    }

    @Test
    fun authRequiredBannerWhenAnyRecordIsBlocked() {
        val records = listOf(
            record(id = 1, recordedAt = 100, status = GarminStatus.AUTH_REQUIRED),
            record(id = 2, recordedAt = 200, status = GarminStatus.PUSHED),
        )
        val s = MainUi.reduce(EngineState(), records, t0, t0)
        assertTrue(Banner.AuthRequired in s.banners)
    }

    @Test
    fun needsReviewBannerOnlyWhenNewestCaptureNeedsReview() {
        val review = listOf(
            record(id = 1, recordedAt = 100),
            record(id = 2, recordedAt = 200, category = Category.NEEDS_REVIEW),
        )
        assertTrue(Banner.NeedsReview in MainUi.reduce(EngineState(), review, t0, t0).banners)

        val older = listOf(
            record(id = 1, recordedAt = 300),
            record(id = 2, recordedAt = 200, category = Category.NEEDS_REVIEW),
        )
        assertFalse(Banner.NeedsReview in MainUi.reduce(EngineState(), older, t0, t0).banners)
    }

    // ------------------------------------------------- recent list + deltas

    @Test
    fun recentListsTheNextFourAfterTheLatestNewestFirst() {
        val records = (1..7L).map { record(id = it, recordedAt = it * 100) }
        val s = MainUi.reduce(EngineState(), records, t0, t0)
        assertEquals(7L, s.latest!!.id)
        assertEquals(listOf(6L, 5L, 4L, 3L), s.recent.map { it.id })
    }

    @Test
    fun deltaIsAgainstTheOwnersPreviousWeighInSkippingOthers() {
        val records = listOf(
            record(id = 1, recordedAt = 100).copy(weightGrams = 8500),
            record(id = 2, recordedAt = 200, category = Category.NEEDS_REVIEW).copy(weightGrams = 6000),
            record(id = 3, recordedAt = 300).copy(weightGrams = 8420),
        )
        val s = MainUi.reduce(EngineState(), records, t0, t0)
        assertEquals("guest row in between is ignored", -80, s.latest!!.deltaGrams)
        assertNull("no delta for a row that is not the owner's", s.recent.first { it.id == 2L }.deltaGrams)
        assertNull("the owner's first weigh-in has nothing to compare", s.recent.first { it.id == 1L }.deltaGrams)
    }

    @Test
    fun liveReadingPassesThrough() {
        val s = MainUi.reduce(EngineState(live = LiveReading(7020, stable = true)), emptyList(), t0, t0)
        assertEquals(LiveReading(7020, stable = true), s.live)
        assertNull(MainUi.reduce(EngineState(), emptyList(), t0, t0).live)
    }

    // ---------------------------------------------------------- view model

    @Test
    fun viewModelTracksListeningWindow() = runBlocking {
        var now = t0
        val engine = MutableStateFlow(EngineState())
        val records = MutableStateFlow(emptyList<WeighInRecord>())
        val vm = MainViewModel(engine, records, nowMs = { now }, tickMs = 1)

        vm.onListeningStarted()
        now = t0 + 12 * hourMs
        assertTrue(vm.uiState.first { s -> s.banners.any { it is Banner.Silence } }.banners.isNotEmpty())

        vm.onListeningStopped()
        assertTrue(vm.uiState.first { s -> s.banners.none { it is Banner.Silence } }.banners.isEmpty())

        // Re-opening restarts the 12 h window from now.
        vm.onListeningStarted()
        assertTrue(vm.uiState.first().banners.none { it is Banner.Silence })
    }
}
