package com.xiaomiscalesync.data

/** A dedupe hit that lets an existing row gain a record number it lacked. */
data class RecordNoFill(val id: Long, val recordNo: Long)

/** What a merge pass wants the repository to do with Room. */
data class MergePlan(
    val inserts: List<WeighInRecord>,
    val fills: List<RecordNoFill>,
    val duplicates: Int,
)

/** Summary of one applied merge. */
data class MergeResult(val inserted: Int, val duplicates: Int)

/** The two settings fields the merger needs (mid already parsed to a number). */
data class MergeSettings(val mid: Long, val backfillCutoff: Long?)

/**
 * The archive's merge semantics as a pure function object — no Room, no
 * Android, fully JVM-testable.
 *
 * Rules (owner decisions, spec §SyncEngine 5–7):
 *  - Dedupe key = (recordedAt unix second, weightCentiKg). A hit is skipped;
 *    if the hit's row has no `recordNo` yet and the incoming one does, the
 *    row gains it ([RecordNoFill]).
 *  - Category: flag 0 + our member id → MINE; flag 2 / mid 0 / any other
 *    combination → NEEDS_REVIEW. GUEST is never assigned here (explicit
 *    "keep as guest" only).
 *  - Backfill: `recordedAt < backfillCutoff` (strict) archives as
 *    SKIPPED_EXISTING — never pushed; a null cutoff pushes everything.
 *  - The merger does no plausibility filtering; implausible timestamps were
 *    already replaced upstream (Task 9's engine), weights validated there.
 */
object ArchiveMerger {

    fun categoryFor(scaleFlag: Int, memberMid: Long, ourMid: Long): Category =
        if (scaleFlag == 0 && memberMid != 0L && memberMid == ourMid) Category.MINE else Category.NEEDS_REVIEW

    fun merge(
        existing: List<WeighInRecord>,
        incoming: List<NewWeighIn>,
        settings: MergeSettings,
        nowUnix: Long,
    ): MergePlan {
        val inserts = mutableListOf<WeighInRecord>()
        val fills = mutableListOf<RecordNoFill>()
        var duplicates = 0

        // Dedupe map: (recordedAt, weight) → row (existing first, then planned).
        val seen = HashMap<Pair<Long, Int>, WeighInRecord>(existing.size + incoming.size)
        val plannedIndex = HashMap<Pair<Long, Int>, Int>()
        existing.forEach { seen[it.recordedAt to it.weightCentiKg] = it }

        for (w in incoming) {
            val key = w.recordedAt to w.weightCentiKg
            val hit = seen[key]
            if (hit != null) {
                duplicates++
                // recordNo fill: the row keeps/gains a record number, first non-null wins.
                if (hit.recordNo == null && w.recordNo != null) {
                    val idx = plannedIndex[key]
                    if (idx != null) {
                        inserts[idx] = inserts[idx].copy(recordNo = w.recordNo)
                    } else {
                        fills += RecordNoFill(hit.id, w.recordNo)
                    }
                    seen[key] = hit.copy(recordNo = w.recordNo)
                }
                continue
            }
            val row = WeighInRecord(
                id = 0,
                weightCentiKg = w.weightCentiKg,
                recordedAt = w.recordedAt,
                source = w.source,
                recordNo = w.recordNo,
                scaleFlag = w.scaleFlag,
                memberMid = w.memberMid,
                category = categoryFor(w.scaleFlag, w.memberMid, settings.mid),
                garminStatus = if (settings.backfillCutoff != null && w.recordedAt < settings.backfillCutoff) {
                    GarminStatus.SKIPPED_EXISTING
                } else {
                    GarminStatus.PENDING
                },
                pushedAt = null,
                createdAt = nowUnix,
            )
            plannedIndex[key] = inserts.size
            inserts += row
            seen[key] = row
        }
        return MergePlan(inserts, fills, duplicates)
    }
}
