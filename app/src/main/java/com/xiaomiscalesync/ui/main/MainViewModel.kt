package com.xiaomiscalesync.ui.main

import com.xiaomiscalesync.data.Category
import com.xiaomiscalesync.data.GarminStatus
import com.xiaomiscalesync.data.WeighInRecord
import com.xiaomiscalesync.data.WeighInSource
import com.xiaomiscalesync.sync.EnginePhase
import com.xiaomiscalesync.sync.EngineState
import com.xiaomiscalesync.sync.LiveReading
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/** The big line at the top of the weighing screen. */
enum class Headline(val label: String) {
    READY("Ready to weigh"),
    SCALE_SEEN("Scale seen"),
    SYNCING("Syncing"),
}

/** Where a captured weigh-in stands (spec status chips). */
enum class StatusChip(val label: String) {
    QUEUED("Queued"),
    PUSHED("Pushed to Garmin"),
    SKIPPED_EXISTING("Skipped (already in Garmin)"),
    NEEDS_REVIEW("Needs review"),
    GUEST("Guest"),
}

/** Banners above the cards; copy lives with the screen. */
sealed class Banner {
    /** No advertisement for [hours] (≥ 12) while the app listened. */
    data class Silence(val hours: Int) : Banner()

    /** Some row is blocked on an expired Garmin sign-in. */
    object AuthRequired : Banner()

    /** The newest capture is attributed to nobody — review it. */
    object NeedsReview : Banner()
}

/** The confirmation card for the newest capture. */
data class LatestCard(
    val id: Long,
    val weightGrams: Int,
    val recordedAt: Long,
    val source: WeighInSource,
    val chip: StatusChip,
    /**
     * Change vs the owner's previous weigh-in (1/100 kg); null when this row
     * is not the owner's or there is nothing earlier to compare with.
     */
    val deltaGrams: Int? = null,
)

data class MainUiState(
    val headline: Headline = Headline.READY,
    val latest: LatestCard? = null,
    val live: LiveReading? = null,
    val banners: List<Banner> = emptyList(),
    /** The few captures before [latest], newest first (Home's short history). */
    val recent: List<LatestCard> = emptyList(),
)

/**
 * The weighing screen's decisions as pure functions (plan Task 15) —
 * everything the tests pin, with no clock or coroutine inside.
 */
object MainUi {

    /** Continuous listening without any advertisement that raises the banner. */
    const val SILENCE_MS = 12 * 3_600_000L

    /** A sighting older than this no longer reads as "Scale seen". */
    const val SEEN_FRESH_MS = 60_000L

    /**
     * Category first: anything not the owner's never shows a Garmin state
     * (it is never pushed). `AUTH_REQUIRED` rows read *Queued* — they push
     * after re-login — and the banner carries the call to action.
     */
    fun statusChip(category: Category, status: GarminStatus): StatusChip = when (category) {
        Category.NEEDS_REVIEW -> StatusChip.NEEDS_REVIEW
        Category.GUEST -> StatusChip.GUEST
        Category.MINE -> when (status) {
            GarminStatus.PENDING, GarminStatus.AUTH_REQUIRED -> StatusChip.QUEUED
            GarminStatus.PUSHED -> StatusChip.PUSHED
            GarminStatus.SKIPPED_EXISTING -> StatusChip.SKIPPED_EXISTING
        }
    }

    /**
     * @param listeningSinceMs when the current scan window opened; null
     *   while the screen is not listening (no silence tracking then).
     */
    fun reduce(
        engine: EngineState,
        records: List<WeighInRecord>,
        listeningSinceMs: Long?,
        nowMs: Long,
    ): MainUiState {
        val headline = when {
            engine.phase == EnginePhase.Connecting -> Headline.SYNCING
            engine.lastHeardAt != null && nowMs - engine.lastHeardAt < SEEN_FRESH_MS -> Headline.SCALE_SEEN
            else -> Headline.READY
        }
        val sorted = records.sortedByDescending { it.recordedAt }
        val newest = sorted.firstOrNull()
        val banners = buildList {
            if (listeningSinceMs != null) {
                val quietSince = maxOf(listeningSinceMs, engine.lastHeardAt ?: Long.MIN_VALUE)
                val quietMs = nowMs - quietSince
                if (quietMs >= SILENCE_MS) add(Banner.Silence((quietMs / 3_600_000L).toInt()))
            }
            if (records.any { it.garminStatus == GarminStatus.AUTH_REQUIRED }) add(Banner.AuthRequired)
            if (newest?.category == Category.NEEDS_REVIEW) add(Banner.NeedsReview)
        }
        return MainUiState(
            headline = headline,
            latest = newest?.let { card(it, sorted) },
            live = engine.live,
            banners = banners,
            recent = sorted.drop(1).take(RECENT_COUNT).map { card(it, sorted) },
        )
    }

    /** How many earlier captures Home lists under the latest one. */
    const val RECENT_COUNT = 4

    /** [row] as a card; [sortedDesc] (newest first) supplies the delta's baseline. */
    private fun card(row: WeighInRecord, sortedDesc: List<WeighInRecord>): LatestCard {
        val previousMine = if (row.category != Category.MINE) {
            null
        } else {
            sortedDesc.firstOrNull { it.category == Category.MINE && it.recordedAt < row.recordedAt }
        }
        return LatestCard(
            id = row.id,
            weightGrams = row.weightGrams,
            recordedAt = row.recordedAt,
            source = row.source,
            chip = statusChip(row.category, row.garminStatus),
            deltaGrams = previousMine?.let { row.weightGrams - it.weightGrams },
        )
    }
}

/**
 * Holds the listening window and turns engine state + archive rows into
 * [MainUiState]. A clock tick ([tickMs]) re-evaluates the time-based
 * parts (silence banner, "Scale seen" freshness) between engine events.
 * Pure Kotlin — the screen owns the scope that collects [uiState].
 */
class MainViewModel(
    engineState: Flow<EngineState>,
    records: Flow<List<WeighInRecord>>,
    private val nowMs: () -> Long = System::currentTimeMillis,
    tickMs: Long = 30_000L,
) {
    private val listeningSince = MutableStateFlow<Long?>(null)

    private val ticks: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(tickMs)
        }
    }

    val uiState: Flow<MainUiState> =
        combine(engineState, records, listeningSince, ticks) { engine, rows, since, _ ->
            MainUi.reduce(engine, rows, since, nowMs())
        }.distinctUntilChanged()

    /** The scan started (screen resumed): the silence window opens now. */
    fun onListeningStarted() {
        if (listeningSince.value == null) listeningSince.value = nowMs()
    }

    /** The scan stopped (screen paused): silence is no longer tracked. */
    fun onListeningStopped() {
        listeningSince.value = null
    }
}
