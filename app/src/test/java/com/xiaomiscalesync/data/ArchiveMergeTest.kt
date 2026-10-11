package com.xiaomiscalesync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for [ArchiveMerger] — the merge/classification logic behind
 * `WeighInRepository.merge`. No Android, no Room: the merger is a pure
 * function over lists, exactly what the plan's Task 8 extracts.
 *
 * Units: every weight is 1/100 kg (8025 = 80.25 kg) per the on-record ruling,
 * despite the plan-wide field name `weightGrams`.
 */
class ArchiveMergeTest {

    private val ourMid = 1L
    private val now = 1_790_000_100L

    private fun settings(cutoff: Long? = null) = MergeSettings(mid = ourMid, backfillCutoff = cutoff)

    private fun ns(
        weightGrams: Int = 8025,
        recordedAt: Long = 1_790_000_000L,
        source: WeighInSource = WeighInSource.STORED,
        recordNo: Long? = null,
        scaleFlag: Int = 0,
        memberMid: Long = 1L,
    ) = NewWeighIn(weightGrams, recordedAt, source, recordNo, scaleFlag, memberMid)

    // --- (a) dedupe -----------------------------------------------------------

    @Test
    fun broadcastThenStoredSameWeighInIsOneRow() {
        // Broadcast arrives first (no recordNo yet)…
        val first = ArchiveMerger.merge(emptyList(), listOf(ns(source = WeighInSource.BROADCAST)), settings(), now)
        assertEquals(1, first.inserts.size)
        assertNull(first.inserts[0].recordNo)
        assertEquals(0, first.duplicates)

        // …then the stored fetch re-sends it WITH a record number.
        val existing = first.inserts.map { it.copy(id = 1) }
        val second = ArchiveMerger.merge(existing, listOf(ns(source = WeighInSource.STORED, recordNo = 41)), settings(), now)

        assertEquals(0, second.inserts.size)
        assertEquals(1, second.duplicates)
        // recordNo fill rule: the existing row gains the missing record number.
        assertEquals(listOf(RecordNoFill(1, 41)), second.fills)
    }

    @Test
    fun duplicateKeepsFirstRecordNoAndDoesNotOverwrite() {
        val existing = listOf(
            WeighInRecord(
                id = 7, weightGrams = 8025, recordedAt = 1_790_000_000L,
                source = WeighInSource.STORED, recordNo = 41, scaleFlag = 0,
                memberMid = 1, category = Category.MINE, garminStatus = GarminStatus.PENDING,
                pushedAt = null, createdAt = now,
            ),
        )
        val plan = ArchiveMerger.merge(existing, listOf(ns(recordNo = 99)), settings(), now)
        assertEquals(0, plan.inserts.size)
        assertEquals(1, plan.duplicates)
        assertEquals(emptyList<RecordNoFill>(), plan.fills) // already has a recordNo: keep 41
    }

    @Test
    fun dedupeKeyIsTimestampPlusWeight() {
        val base = ns(weightGrams = 8025, recordedAt = 1_790_000_000L)
        val existing = listOf(
            WeighInRecord(
                id = 1, weightGrams = 8025, recordedAt = 1_790_000_000L,
                source = WeighInSource.STORED, recordNo = 41, scaleFlag = 0,
                memberMid = 1, category = Category.MINE, garminStatus = GarminStatus.PENDING,
                pushedAt = null, createdAt = now,
            ),
        )
        // Same ts, different weight → insert.
        assertEquals(1, ArchiveMerger.merge(existing, listOf(base.copy(weightGrams = 7260)), settings(), now).inserts.size)
        // Same weight, different ts → insert.
        assertEquals(1, ArchiveMerger.merge(existing, listOf(base.copy(recordedAt = 1_790_000_060L)), settings(), now).inserts.size)
        // Exact (ts, weight) hit → duplicate.
        val hit = ArchiveMerger.merge(existing, listOf(base), settings(), now)
        assertEquals(0, hit.inserts.size)
        assertEquals(1, hit.duplicates)
    }

    @Test
    fun duplicatesWithinOneBatchCollapseToASingleInsert() {
        val plan = ArchiveMerger.merge(
            emptyList(),
            listOf(ns(recordNo = 41), ns(recordNo = null), ns(recordNo = 42)),
            settings(),
            now,
        )
        assertEquals(1, plan.inserts.size)
        assertEquals(2, plan.duplicates)
        // First occurrence wins its recordNo; later nulls/dupes never overwrite.
        assertEquals(41L, plan.inserts[0].recordNo)
    }

    // --- (b) categories ------------------------------------------------------

    @Test
    fun flagZeroWithOwnMidIsMine() {
        val plan = ArchiveMerger.merge(emptyList(), listOf(ns(scaleFlag = 0, memberMid = 1)), settings(), now)
        assertEquals(Category.MINE, plan.inserts.single().category)
    }

    @Test
    fun flagZeroWithForeignMidIsNeedsReview() {
        val plan = ArchiveMerger.merge(emptyList(), listOf(ns(scaleFlag = 0, memberMid = 2)), settings(), now)
        assertEquals(Category.NEEDS_REVIEW, plan.inserts.single().category)
    }

    @Test
    fun flagTwoIsNeedsReviewEvenWithOwnMid() {
        val plan = ArchiveMerger.merge(emptyList(), listOf(ns(scaleFlag = 2, memberMid = 1)), settings(), now)
        assertEquals(Category.NEEDS_REVIEW, plan.inserts.single().category)
    }

    @Test
    fun midZeroIsNeedsReviewEvenWithFlagZero() {
        val plan = ArchiveMerger.merge(emptyList(), listOf(ns(scaleFlag = 0, memberMid = 0)), settings(), now)
        assertEquals(Category.NEEDS_REVIEW, plan.inserts.single().category)
    }

    // --- (c) backfill cutoff --------------------------------------------------

    @Test
    fun recordsBeforeCutoffArchiveOnlyAsSkippedExisting() {
        val plan = ArchiveMerger.merge(
            emptyList(),
            listOf(ns(recordedAt = 1_789_999_999L, memberMid = 1, scaleFlag = 0)),
            settings(cutoff = 1_790_000_000L),
            now,
        )
        val row = plan.inserts.single()
        assertEquals(GarminStatus.SKIPPED_EXISTING, row.garminStatus)
        assertEquals(Category.MINE, row.category) // category still assigned normally
    }

    @Test
    fun recordsAtOrAfterCutoffStayPending() {
        val plan = ArchiveMerger.merge(
            emptyList(),
            listOf(
                ns(weightGrams = 8000, recordedAt = 1_790_000_001L), // after: strict >
                ns(weightGrams = 8100, recordedAt = 1_790_000_000L), // exactly at cutoff: strict < means pushable
            ),
            settings(cutoff = 1_790_000_000L),
            now,
        )
        assertEquals(listOf(GarminStatus.PENDING, GarminStatus.PENDING), plan.inserts.map { it.garminStatus })
    }

    @Test
    fun nullCutoffPushesEverythingRegardlessOfAge() {
        val plan = ArchiveMerger.merge(
            emptyList(),
            listOf(ns(weightGrams = 8000, recordedAt = 1_600_000_000L)),
            settings(cutoff = null),
            now,
        )
        assertEquals(GarminStatus.PENDING, plan.inserts.single().garminStatus)
    }

    @Test
    fun backfillGuestRecordsAreSkippedExistingAndNeedsReview() {
        val plan = ArchiveMerger.merge(
            emptyList(),
            listOf(ns(recordedAt = 1_700_000_000L, scaleFlag = 2, memberMid = 0)),
            settings(cutoff = 1_790_000_000L),
            now,
        )
        val row = plan.inserts.single()
        assertEquals(Category.NEEDS_REVIEW, row.category)
        assertEquals(GarminStatus.SKIPPED_EXISTING, row.garminStatus)
    }

    // --- (d) no plausibility filtering in the merger -------------------------

    @Test
    fun mergerToleratesUpstreamReplacedTimestampsWithoutFiltering() {
        // Implausible scale ts (clock unset) was already replaced with receive
        // time upstream; the merger must archive it as-is, never filter.
        val receiveTime = 1_790_000_050L
        val plan = ArchiveMerger.merge(
            emptyList(),
            listOf(ns(recordedAt = receiveTime, weightGrams = 7215)),
            settings(cutoff = null),
            now,
        )
        val row = plan.inserts.single()
        assertEquals(receiveTime, row.recordedAt)
        assertEquals(7215, row.weightGrams)
        assertEquals(GarminStatus.PENDING, row.garminStatus)
    }

    @Test
    fun newRowsStartPendingPushedNull() {
        val row = ArchiveMerger.merge(emptyList(), listOf(ns()), settings(), now).inserts.single()
        assertEquals(GarminStatus.PENDING, row.garminStatus)
        assertNull(row.pushedAt)
        assertEquals(now, row.createdAt)
    }
}
