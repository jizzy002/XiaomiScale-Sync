package com.xiaomiscalesync.garmin

import com.xiaomiscalesync.data.GarminStatus
import com.xiaomiscalesync.data.SyncFailureCategory
import com.xiaomiscalesync.data.WeighInRecord
import com.xiaomiscalesync.data.WeighInRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Outcome of one queue drain ([GarminPusher.pushPending]).
 *
 * Invariant: `pushed + skippedExisting + failed` equals the number of rows the
 * pass *attempted*; rows after a pass-stopping error are untouched and
 * counted nowhere (they are still `PENDING` and go out with the next run).
 *
 * @param failed attempted rows that reached Garmin (or its guard read) and
 *   errored — auth, rate limit, API or network. The auth one also sets
 *   [authBlocked]; the rest stay `PENDING` for WorkManager's backoff retry.
 */
data class PushSummary(
    val pushed: Int,
    val skippedExisting: Int,
    val failed: Int,
    val authBlocked: Boolean,
    val failure: SyncFailureCategory = SyncFailureCategory.NONE,
)

/**
 * Drains the archive's Garmin queue: for every `PENDING`+`MINE` row, run the
 * Garmin-side duplicate guard (Task 12's core — what keeps the owner's
 * still-running computer setup from being double-posted), upload on a miss,
 * and flip the row's status. Pure logic over injected seams ([WeighInRepository],
 * [GarminClient], [Clock]); no Android types, so every decision is JVM-tested
 * (`GarminPusherTest`). `PushWorker` is the thin WorkManager shell around it.
 *
 * Error mapping (controller-ruled): `GarminAuthError` → the row becomes
 * [GarminStatus.AUTH_REQUIRED], `authBlocked = true`, and the pass **stops**
 * (later rows untouched until the user re-signs in; Settings surfaces it).
 * `GarminRateLimited` / `GarminApiError` / `GarminNetworkError` → the pass
 * stops, the row stays `PENDING`, `failed` is counted — WorkManager retries
 * with backoff, and the next pass re-guards everything anyway. Any other
 * typed [GarminException] (e.g. an unparseable range read) stops the pass the
 * same way; unexpected non-Garmin bugs propagate and fail the worker run.
 *
 * Concurrency: [PushWorker] enqueues under a unique work name, so at most one
 * pass runs at a time; no two passes can race on the same rows.
 */
class GarminPusher(
    private val repo: WeighInRepository,
    private val garmin: GarminClient,
    private val clock: Clock,
) {

    companion object {
        /** The exact stamp shape Garmin reports (`timestampGMT` rendered in UTC). */
        private val GMT_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    }

    /**
     * One queue drain, oldest row first (the DAO orders `recordedAt ASC`).
     * Stops at the first error of any kind — a fail-fast pass keeps an
     * unhealthy Garmin session from churning through the whole backlog.
     */
    suspend fun pushPending(): PushSummary {
        var pushed = 0
        var skipped = 0
        var failed = 0
        var authBlocked = false
        var failure = SyncFailureCategory.NONE
        for (record in repo.pending()) {
            try {
                val existing = garmin.weighInsOn(recordDate(record))
                if (shouldSkip(existing, record)) {
                    repo.setGarminStatus(listOf(record.id), GarminStatus.SKIPPED_EXISTING)
                    skipped++
                    continue
                }
                upload(record)
                repo.markPushed(listOf(record.id), clock.instant().epochSecond)
                pushed++
            } catch (_: GarminAuthError) {
                repo.setGarminStatus(listOf(record.id), GarminStatus.AUTH_REQUIRED)
                failed++
                authBlocked = true
                failure = SyncFailureCategory.AUTHENTICATION
                break // pass stops: no further rows until the user re-signs in
            } catch (_: GarminRateLimited) {
                failed++
                failure = SyncFailureCategory.RATE_LIMITED
                break
            } catch (_: GarminNetworkError) {
                failed++
                failure = SyncFailureCategory.NETWORK
                break
            } catch (_: GarminApiError) {
                failed++
                failure = SyncFailureCategory.SERVICE
                break
            } catch (_: GarminException) {
                // Rate limit, API, network, parse: rows stay PENDING for the
                // WorkManager backoff retry; the pass stops here.
                failed++
                failure = SyncFailureCategory.UNKNOWN
                break
            }
        }
        return PushSummary(
            pushed = pushed,
            skippedExisting = skipped,
            failed = failed,
            authBlocked = authBlocked,
            failure = failure,
        )
    }

    /**
     * The Garmin-side duplicate guard, run before **every** upload: true when
     * any of [existing] (the day's Garmin weigh-ins) is the same weigh-in as
     * [record] — either the same GMT unix second (seconds precision;
     * milliseconds in Garmin's stamp are ignored) or, as the fallback, the
     * same calendar date **and** the same kilogram value
     * (`valueKg == weightCentiKg / 100.0` — exact division of the same decimal
     * value, so `==` is sound; this is what catches a re-upload whose
     * timestamp drifted). A skip marks the row `SKIPPED_EXISTING` and the
     * computer setup's entry stays the single Garmin copy.
     */
    fun shouldSkip(existing: List<GarminWeighIn>, record: WeighInRecord): Boolean {
        val recordDate = recordDate(record)
        // Unit contract: the archive's weightCentiKg is 1/100 kg (8025 = 80.25 kg).
        val recordValueKg = record.weightCentiKg / 100.0
        return existing.any { entry ->
            val entrySecond = entry.gmtTimestamp?.let { stamp ->
                runCatching {
                    LocalDateTime.parse(stamp, GMT_STAMP).toEpochSecond(ZoneOffset.UTC)
                }.getOrNull()
            }
            entrySecond == record.recordedAt ||
                (entry.date == recordDate.toString() && entry.valueKg == recordValueKg)
        }
    }

    /**
     * The day Garmin files [record] under: its calendar date in the pusher
     * clock's zone (the same zone [upload] presents as the local time).
     */
    private fun recordDate(record: WeighInRecord): LocalDate =
        Instant.ofEpochSecond(record.recordedAt).atZone(clock.zone).toLocalDate()

    /**
     * Uploads one guard-cleared record and stamps it `PUSHED`.
     *
     * Unit contract (Task 11 ruling): the archive's `weightCentiKg` is 1/100 kg
     * (8025 = 80.25 kg), so the kilogram value Garmin stores is
     * `weightCentiKg / 100.0` — [GarminClient.addWeighIn] renders it into the
     * payload and the duplicate guard compares that same figure against
     * `GarminWeighIn.valueKg`. The local time is the record's instant in the
     * pusher clock's zone; the client derives the GMT stamp from it, so the
     * scale's real weigh-in time survives the upload.
     */
    private suspend fun upload(record: WeighInRecord) {
        val localTs = Instant.ofEpochSecond(record.recordedAt).atZone(clock.zone)
        garmin.addWeighIn(record.weightCentiKg, localTs)
    }
}
