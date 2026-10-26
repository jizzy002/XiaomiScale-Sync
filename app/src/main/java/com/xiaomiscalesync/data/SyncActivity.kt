package com.xiaomiscalesync.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.xiaomiscalesync.garmin.PushSummary
import com.xiaomiscalesync.sync.SyncKind
import com.xiaomiscalesync.sync.SyncResult
import kotlinx.coroutines.flow.Flow

enum class SyncService { SCALE, GARMIN }

enum class SyncEventOutcome { COMPLETED, SCALE_NOT_FOUND, FAILED, AUTH_REQUIRED, RETRYING }

/** Fixed, non-sensitive reason categories. Raw exceptions are never persisted. */
enum class SyncFailureCategory {
    NONE,
    SCALE_CONNECTION,
    AUTHENTICATION,
    RATE_LIMITED,
    NETWORK,
    SERVICE,
    UNKNOWN,
}

@Entity(
    tableName = "sync_activity",
    indices = [Index(value = ["occurredAt"])],
)
data class SyncActivityEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurredAt: Long,
    val service: SyncService,
    val outcome: SyncEventOutcome,
    val failure: SyncFailureCategory = SyncFailureCategory.NONE,
    val attempts: Int = 0,
    val readings: Int = 0,
    val pushed: Int = 0,
    val skipped: Int = 0,
)

/** Maps sync outcomes into the deliberately small, safe activity record. */
object SyncActivityMapper {
    const val MAX_EVENTS = 100

    fun scale(result: SyncResult, occurredAt: Long) = SyncActivityEvent(
        occurredAt = occurredAt,
        service = SyncService.SCALE,
        outcome = when (result.kind) {
            SyncKind.OK -> SyncEventOutcome.COMPLETED
            SyncKind.SCALE_ABSENT -> SyncEventOutcome.SCALE_NOT_FOUND
            SyncKind.FAILED -> SyncEventOutcome.FAILED
        },
        failure = if (result.kind == SyncKind.FAILED) SyncFailureCategory.SCALE_CONNECTION else SyncFailureCategory.NONE,
        attempts = result.attempts,
        readings = result.collected,
    )

    fun garmin(summary: PushSummary, occurredAt: Long): SyncActivityEvent? {
        if (summary.pushed == 0 && summary.skippedExisting == 0 && summary.failed == 0) return null
        return SyncActivityEvent(
            occurredAt = occurredAt,
            service = SyncService.GARMIN,
            outcome = when {
                summary.authBlocked -> SyncEventOutcome.AUTH_REQUIRED
                summary.failed > 0 -> SyncEventOutcome.RETRYING
                else -> SyncEventOutcome.COMPLETED
            },
            failure = summary.failure,
            pushed = summary.pushed,
            skipped = summary.skippedExisting,
        )
    }

    fun unexpectedGarminFailure(occurredAt: Long) = SyncActivityEvent(
        occurredAt = occurredAt,
        service = SyncService.GARMIN,
        outcome = SyncEventOutcome.RETRYING,
        failure = SyncFailureCategory.UNKNOWN,
    )

    /** Newest first, with IDs providing deterministic order for equal timestamps. */
    fun newestFirst(events: List<SyncActivityEvent>): List<SyncActivityEvent> =
        events.sortedWith(compareByDescending<SyncActivityEvent> { it.occurredAt }.thenByDescending { it.id })
}

/** Room-backed, bounded activity history. */
class SyncActivityStore(private val dao: SyncActivityDao) {
    val events: Flow<List<SyncActivityEvent>> = dao.observeRecent()

    suspend fun record(event: SyncActivityEvent) {
        dao.insertAndTrim(event, SyncActivityMapper.MAX_EVENTS)
    }

    suspend fun recordGarmin(summary: PushSummary, occurredAt: Long) {
        SyncActivityMapper.garmin(summary, occurredAt)?.let { record(it) }
    }

    suspend fun recordUnexpectedGarminFailure(occurredAt: Long) {
        record(SyncActivityMapper.unexpectedGarminFailure(occurredAt))
    }
}
