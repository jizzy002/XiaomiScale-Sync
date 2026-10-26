package com.xiaomiscalesync.data

import com.xiaomiscalesync.garmin.PushSummary
import com.xiaomiscalesync.sync.SyncKind
import com.xiaomiscalesync.sync.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncActivityMapperTest {
    @Test
    fun scaleOutcomesMapWithoutRawErrorDetails() {
        val success = SyncActivityMapper.scale(SyncResult(SyncKind.OK, attempts = 2, collected = 4), 50)
        assertEquals(SyncService.SCALE, success.service)
        assertEquals(SyncEventOutcome.COMPLETED, success.outcome)
        assertEquals(4, success.readings)
        assertEquals(2, success.attempts)
        assertEquals(SyncFailureCategory.NONE, success.failure)

        val absent = SyncActivityMapper.scale(SyncResult(SyncKind.SCALE_ABSENT, 3), 51)
        assertEquals(SyncEventOutcome.SCALE_NOT_FOUND, absent.outcome)

        val failed = SyncActivityMapper.scale(SyncResult(SyncKind.FAILED, 3), 52)
        assertEquals(SyncEventOutcome.FAILED, failed.outcome)
        assertEquals(SyncFailureCategory.SCALE_CONNECTION, failed.failure)
    }

    @Test
    fun garminPassesCaptureCountsAndSafeFailureCategories() {
        val completed = SyncActivityMapper.garmin(PushSummary(2, 1, 0, false), 60)!!
        assertEquals(SyncService.GARMIN, completed.service)
        assertEquals(SyncEventOutcome.COMPLETED, completed.outcome)
        assertEquals(2, completed.pushed)
        assertEquals(1, completed.skipped)

        val auth = SyncActivityMapper.garmin(
            PushSummary(0, 0, 1, true, SyncFailureCategory.AUTHENTICATION),
            61,
        )!!
        assertEquals(SyncEventOutcome.AUTH_REQUIRED, auth.outcome)
        assertEquals(SyncFailureCategory.AUTHENTICATION, auth.failure)

        val retry = SyncActivityMapper.garmin(
            PushSummary(0, 0, 1, false, SyncFailureCategory.NETWORK),
            62,
        )!!
        assertEquals(SyncEventOutcome.RETRYING, retry.outcome)
        assertEquals(SyncFailureCategory.NETWORK, retry.failure)
    }

    @Test
    fun emptyPushPassIsNotAnActivityEvent() {
        assertNull(SyncActivityMapper.garmin(PushSummary(0, 0, 0, false), 70))
    }

    @Test
    fun eventsSortNewestFirstWithStableTieBreakingAndKeepLatestHundred() {
        val events = (1L..120L).map { id ->
            SyncActivityEvent(id = id, occurredAt = id / 2, service = SyncService.SCALE, outcome = SyncEventOutcome.COMPLETED)
        }

        val retained = SyncActivityMapper.newestFirst(events).take(SyncActivityMapper.MAX_EVENTS)

        assertEquals(100, retained.size)
        assertEquals(120L, retained.first().id)
        assertEquals(21L, retained.last().id)
    }
}
