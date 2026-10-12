package com.xiaomiscalesync.garmin

import com.xiaomiscalesync.data.Category
import com.xiaomiscalesync.data.FakeDao
import com.xiaomiscalesync.data.GarminStatus
import com.xiaomiscalesync.data.NewWeighIn
import com.xiaomiscalesync.data.Settings
import com.xiaomiscalesync.data.SettingsSource
import com.xiaomiscalesync.data.WeighInRecord
import com.xiaomiscalesync.data.WeighInRepository
import com.xiaomiscalesync.data.WeighInSource
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [GarminPusher] — the queue drain + the Garmin-side
 * duplicate guard that keeps the still-running computer setup from being
 * double-posted. Uses the shared in-memory [FakeDao] harness (Task 8) and a
 * scripted [FakeGarmin] client; no Android runtime, no HTTP.
 */
class GarminPusherTest {

    // A mid-day unix second (2026-09-21 ~14:21 UTC) — three distinct times for
    // ordering, and a "now" safely after all of them.
    private val t1 = 1_790_000_000L
    private val t2 = t1 + 60
    private val t3 = t1 + 120
    private val now = t3 + 3_600

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val clock: Clock = Clock.fixed(Instant.ofEpochSecond(now), zone)

    // ------------------------------------------------------------------ fakes

    /** Scripted [GarminClient]: records every call, throws pre-programmed typed errors. */
    private class FakeGarmin : GarminClient(
        http = OkHttpClient(),
        store = object : TokenStore {
            override suspend fun load(): GarminTokens? = null
            override suspend fun save(tokens: GarminTokens) = Unit
        },
        mfa = { "" }, // interactive sign-in is not under test here
    ) {
        data class Add(val weightGrams: Int, val localTs: Instant)

        val fetchedDates = mutableListOf<LocalDate>()
        val added = mutableListOf<Add>()

        /** Weigh-ins the fake reports for a fetched day (the guard's list). */
        var weighInsFor: (LocalDate) -> List<GarminWeighIn> = { emptyList() }

        /** When set, thrown by [weighInsOn] (the guard fetch). */
        var readError: GarminException? = null

        /** When set, thrown by [addWeighIn] (the upload). */
        var writeError: GarminException? = null

        override suspend fun weighInsOn(date: LocalDate): List<GarminWeighIn> {
            fetchedDates += date
            readError?.let { throw it }
            return weighInsFor(date)
        }

        override suspend fun addWeighIn(weightGrams: Int, localTs: ZonedDateTime) {
            added += Add(weightGrams, localTs.toInstant())
            writeError?.let { throw it }
        }
    }

    // --------------------------------------------------------------- harness

    private fun harness(): Pair<WeighInRepository, FakeDao> {
        val dao = FakeDao()
        val repo = WeighInRepository(
            dao,
            SettingsSource { Settings(onboarded = true, backfillCutoff = null, mid = "1") },
            nowUnix = { now },
        )
        return repo to dao
    }

    private fun ns(
        weightGrams: Int = 8025,
        recordedAt: Long = t1,
        scaleFlag: Int = 0,
        memberMid: Long = 1L,
    ) = NewWeighIn(weightGrams, recordedAt, WeighInSource.STORED, recordNo = null, scaleFlag, memberMid)

    private fun record(
        weightGrams: Int = 8025,
        recordedAt: Long = t1,
        category: Category = Category.MINE,
        status: GarminStatus = GarminStatus.PENDING,
    ) = WeighInRecord(
        id = 1, weightGrams = weightGrams, recordedAt = recordedAt,
        source = WeighInSource.STORED, recordNo = null, scaleFlag = 0,
        memberMid = 1, category = category, garminStatus = status,
        pushedAt = null, createdAt = now,
    )

    private fun FakeDao.rowAt(recordedAt: Long): WeighInRecord =
        rows.value.single { it.recordedAt == recordedAt }

    /** The GMT stamp Garmin reports (`timestampGMT` rendered in UTC). */
    private val gmtStamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    private fun utcStamp(unixSeconds: Long): String =
        gmtStamp.format(Instant.ofEpochSecond(unixSeconds).atZone(ZoneOffset.UTC))

    /** The record's calendar day in the pusher clock's zone (the day Garmin filed it under). */
    private fun dayOf(unixSeconds: Long): String =
        Instant.ofEpochSecond(unixSeconds).atZone(zone).toLocalDate().toString()

    private fun pusher(repo: WeighInRepository, garmin: FakeGarmin) =
        GarminPusher(repo, garmin, clock)

    // ------------------------------------------------------------------ tests

    @Test
    fun happyPushMarksPushedStampsPushedAtAndUploadsInLocalTime() = runBlocking {
        val (repo, dao) = harness()
        repo.merge(listOf(ns(weightGrams = 8025, recordedAt = t1)))
        val garmin = FakeGarmin()

        val summary = pusher(repo, garmin).pushPending()

        // Correct addWeighIn args: weightGrams passed through, local time of the record.
        assertEquals(
            listOf(8025 to Instant.ofEpochSecond(t1)),
            garmin.added.map { it.weightGrams to it.localTs },
        )
        // The guard queried the record's calendar day before uploading.
        assertEquals(
            listOf(Instant.ofEpochSecond(t1).atZone(zone).toLocalDate()),
            garmin.fetchedDates,
        )
        val row = dao.rows.value.single()
        assertEquals(GarminStatus.PUSHED, row.garminStatus)
        assertEquals(now, row.pushedAt) // unix seconds from the injected clock
        assertEquals(PushSummary(pushed = 1, skippedExisting = 0, failed = 0, authBlocked = false), summary)
    }

    @Test
    fun guardSkipsWhenGmtTimestampMatchesTheSameSecond() = runBlocking {
        val (repo, dao) = harness()
        repo.merge(listOf(ns(weightGrams = 8025, recordedAt = t1)))
        val garmin = FakeGarmin().apply {
            // Same unix second, wildly different value: the timestamp match wins.
            weighInsFor = { listOf(GarminWeighIn(utcStamp(t1), dayOf(t1), valueKg = 99.99)) }
        }

        val summary = pusher(repo, garmin).pushPending()

        assertTrue(garmin.added.isEmpty()) // never uploaded
        assertEquals(GarminStatus.SKIPPED_EXISTING, dao.rows.value.single().garminStatus)
        assertEquals(PushSummary(pushed = 0, skippedExisting = 1, failed = 0, authBlocked = false), summary)
    }

    @Test
    fun guardSkipsWhenDateAndValueMatchEvenWithADifferentTimestamp() = runBlocking {
        val (repo, dao) = harness()
        repo.merge(listOf(ns(weightGrams = 8025, recordedAt = t1)))
        val garmin = FakeGarmin().apply {
            // Different second on the same day, identical kg: the date+value fallback wins.
            weighInsFor = { listOf(GarminWeighIn(utcStamp(t1 + 3_600), dayOf(t1), valueKg = 80.25)) }
        }

        val summary = pusher(repo, garmin).pushPending()

        assertTrue(garmin.added.isEmpty())
        assertEquals(GarminStatus.SKIPPED_EXISTING, dao.rows.value.single().garminStatus)
        assertEquals(1, summary.skippedExisting)
    }

    @Test
    fun guardMissPushesTheWeighIn() = runBlocking {
        val (repo, dao) = harness()
        repo.merge(listOf(ns(weightGrams = 8025, recordedAt = t1)))
        val garmin = FakeGarmin().apply {
            // Same day, different value, different second → not a duplicate.
            weighInsFor = { listOf(GarminWeighIn(utcStamp(t1 + 3_600), dayOf(t1), valueKg = 70.00)) }
        }

        val summary = pusher(repo, garmin).pushPending()

        assertEquals(listOf(8025), garmin.added.map { it.weightGrams })
        assertEquals(GarminStatus.PUSHED, dao.rows.value.single().garminStatus)
        assertEquals(1, summary.pushed)
    }

    @Test
    fun shouldSkipTreatsTimestampsAsSecondsPrecision() {
        val pusher = pusher(harness().first, FakeGarmin())
        // Garmin stamps carry milliseconds; the record is whole seconds. Same
        // second with .750 ms is still the same weigh-in.
        val stamp750 = utcStamp(t1).replace(Regex("""\.\d{3}$"""), ".750")
        val hit = GarminWeighIn(stamp750, dayOf(t1), valueKg = 1.0) // value deliberately wrong
        assertTrue(pusher.shouldSkip(listOf(hit), record(weightGrams = 8025, recordedAt = t1)))
    }

    @Test
    fun authErrorOnUploadStopsThePassAndBlocksAuth() = runBlocking {
        val (repo, dao) = harness()
        repo.merge(listOf(ns(weightGrams = 8000, recordedAt = t1), ns(weightGrams = 8100, recordedAt = t2)))
        val garmin = FakeGarmin().apply { writeError = GarminAuthError("401 Unauthorized") }

        val summary = pusher(repo, garmin).pushPending()

        assertEquals(PushSummary(pushed = 0, skippedExisting = 0, failed = 1, authBlocked = true), summary)
        assertEquals(GarminStatus.AUTH_REQUIRED, dao.rowAt(t1).garminStatus)
        assertEquals(GarminStatus.PENDING, dao.rowAt(t2).garminStatus) // later rows untouched
        assertEquals(1, garmin.fetchedDates.size) // the second row never reached Garmin
        assertEquals(1, garmin.added.size)
    }

    @Test
    fun authErrorOnTheGuardFetchAlsoBlocksAuth() = runBlocking {
        val (repo, dao) = harness()
        repo.merge(listOf(ns(weightGrams = 8000, recordedAt = t1)))
        val garmin = FakeGarmin().apply { readError = GarminAuthError("401 on weight range read") }

        val summary = pusher(repo, garmin).pushPending()

        assertEquals(GarminStatus.AUTH_REQUIRED, dao.rows.value.single().garminStatus)
        assertTrue(summary.authBlocked)
        assertTrue(garmin.added.isEmpty())
    }

    @Test
    fun rateLimitedStopsThePassAndRowsStayPending() = runBlocking {
        val (repo, dao) = harness()
        repo.merge(listOf(ns(weightGrams = 8000, recordedAt = t1), ns(weightGrams = 8100, recordedAt = t2)))
        val garmin = FakeGarmin().apply { writeError = GarminRateLimited("429 Too Many Requests") }

        val summary = pusher(repo, garmin).pushPending()

        assertEquals(PushSummary(pushed = 0, skippedExisting = 0, failed = 1, authBlocked = false), summary)
        assertEquals(GarminStatus.PENDING, dao.rowAt(t1).garminStatus) // no state flip
        assertEquals(GarminStatus.PENDING, dao.rowAt(t2).garminStatus)
        assertEquals(1, garmin.fetchedDates.size) // pass stopped, later rows untouched
    }

    @Test
    fun apiErrorStopsThePassAndRowsStayPending() = runBlocking {
        val (repo, dao) = harness()
        repo.merge(listOf(ns(weightGrams = 8000, recordedAt = t1)))
        val garmin = FakeGarmin().apply { writeError = GarminApiError(500, "/weight-service/user-weight") }

        val summary = pusher(repo, garmin).pushPending()

        assertEquals(PushSummary(pushed = 0, skippedExisting = 0, failed = 1, authBlocked = false), summary)
        assertEquals(GarminStatus.PENDING, dao.rows.value.single().garminStatus)
    }

    @Test
    fun networkErrorStopsThePassAndRowsStayPending() = runBlocking {
        val (repo, dao) = harness()
        repo.merge(listOf(ns(weightGrams = 8000, recordedAt = t1)))
        val garmin = FakeGarmin().apply {
            writeError = GarminNetworkError("/weight-service/user-weight", IOException("dns"))
        }

        val summary = pusher(repo, garmin).pushPending()

        assertEquals(PushSummary(pushed = 0, skippedExisting = 0, failed = 1, authBlocked = false), summary)
        assertEquals(GarminStatus.PENDING, dao.rows.value.single().garminStatus)
    }

    @Test
    fun emptyQueueMakesNoClientCallsAtAll() = runBlocking {
        val (repo, _) = harness()
        val garmin = FakeGarmin()

        val summary = pusher(repo, garmin).pushPending()

        assertEquals(PushSummary(pushed = 0, skippedExisting = 0, failed = 0, authBlocked = false), summary)
        assertTrue(garmin.fetchedDates.isEmpty())
        assertTrue(garmin.added.isEmpty())
    }

    @Test
    fun multipleRowsArePushedInRecordedOrder() = runBlocking {
        val (repo, dao) = harness()
        // Inserted out of chronological order: the queue still drains oldest first.
        repo.merge(listOf(ns(weightGrams = 8300, recordedAt = t3), ns(weightGrams = 8100, recordedAt = t2), ns(weightGrams = 8200, recordedAt = t1)))
        val garmin = FakeGarmin()

        val summary = pusher(repo, garmin).pushPending()

        assertEquals(
            listOf(8200 to t1, 8100 to t2, 8300 to t3),
            garmin.added.map { it.weightGrams to it.localTs.epochSecond },
        )
        assertEquals(3, summary.pushed)
        assertTrue(dao.rows.value.all { it.garminStatus == GarminStatus.PUSHED && it.pushedAt == now })
    }

    @Test
    fun needsReviewAndGuestRowsAreNeverAttempted() = runBlocking {
        val (repo, dao) = harness()
        repo.merge(listOf(ns(weightGrams = 8000, recordedAt = t1)))                            // mine
        repo.merge(listOf(ns(weightGrams = 8100, recordedAt = t2, scaleFlag = 2, memberMid = 0))) // needs review
        repo.merge(listOf(ns(weightGrams = 8200, recordedAt = t3, scaleFlag = 2, memberMid = 0)))
        repo.keepGuest(3) // → guest, archive only
        val garmin = FakeGarmin()

        val summary = pusher(repo, garmin).pushPending()

        assertEquals(PushSummary(pushed = 1, skippedExisting = 0, failed = 0, authBlocked = false), summary)
        assertEquals(listOf(8000), garmin.added.map { it.weightGrams }) // only the MINE row
        assertEquals(GarminStatus.PENDING, dao.rowAt(t2).garminStatus)  // untouched
        assertEquals(GarminStatus.PENDING, dao.rowAt(t3).garminStatus)
    }
}
