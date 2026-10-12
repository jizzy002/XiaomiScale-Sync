package com.xiaomiscalesync.sync

import com.xiaomiscalesync.ble.Beacon
import com.xiaomiscalesync.ble.BroadcastWeighIn
import com.xiaomiscalesync.data.NewWeighIn
import com.xiaomiscalesync.data.WeighInRepository
import com.xiaomiscalesync.data.WeighInSource
import com.xiaomiscalesync.proto.MiotCodec
import com.xiaomiscalesync.proto.MiotMessage
import com.xiaomiscalesync.proto.ParamType
import com.xiaomiscalesync.proto.Profile
import com.xiaomiscalesync.proto.ProfileJson
import com.xiaomiscalesync.proto.ProtocolError
import com.xiaomiscalesync.proto.ScaleSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock

/**
 * Connect / collect / archive orchestration (plan Task 9): the trigger
 * rules on top of [ScaleSession] (Tasks 5–8) and [WeighInRepository]
 * (Task 11's archive gate).
 *
 * Discipline, per plan Global Constraints (fake-clock-driven in tests):
 *  - one unsolicited sync per [EngineConfig.minConnectIntervalMs];
 *  - at most [EngineConfig.maxAttempts] connection attempts per sync;
 *  - a failed sync gates the next attempt with an exponential backoff
 *    (base [EngineConfig.backoffBaseMs]) instead of the interval;
 *  - a failed or fruitless solicit-triggered sync mutes further solicit
 *    triggers for [EngineConfig.solicitQuietMs]; weigh-in triggers are
 *    never muted;
 *  - the live listen is bounded by [EngineConfig.liveStartMs] (first
 *    event), [EngineConfig.liveIdleMs] (quiet between events) and
 *    [EngineConfig.liveMaxMs] (whole listen), and ends early on 5.4.
 *
 * Protocol facts encoded here (reference client, verbatim):
 *  - every session pushes the pinned profile JSON with action 7.1;
 *  - action 6.1 fetches the stored archive (event `6.1`, param 1 = the
 *    `no,mid,ut,weight,flag,ts` records string, `_`-separated);
 *  - action 6.2 is the only delete, and only ever driven by the explicit
 *    [cleanScaleMemory] API or the queued-cleanup drain — never 4.3;
 *  - live events: `5.3` (p2 stable, p3 weight) while the user steps on,
 *    `5.4` (p4 = `mid,user_type,weight,flag,ts`) when the weighing ends.
 *
 * Binding rulings (progress ledger / dispatch):
 *  - weights are 1/100 kg integers despite the `weightGrams` name;
 *  - implausible timestamps (before 2024-01-01 or more than 300 s in the
 *    future) are replaced with the receive time; implausible weights
 *    (outside 100..30000) are discarded before they reach the merger;
 *  - exactly one LIVE archive record per session: committed from the 5.4
 *    event when it arrives, else from the last stable 5.3 at listen end
 *    (guest-ambiguous markers, flag 2 / mid 0 → NEEDS_REVIEW), with
 *    `recordedAt` = commit time;
 *  - the engine never pushes to Garmin and never auto-deletes.
 */
class SyncEngine(
    private val repo: WeighInRepository,
    private val openSession: suspend () -> ScaleSession?,
    /**
     * The profile pushed with action 7.1, read fresh per session so a
     * Settings edit or a new weigh-in lands on the very next connection.
     */
    private val profile: suspend () -> Profile = { Profile() },
    private val config: EngineConfig = EngineConfig(),
    private val clock: Clock = Clock.systemUTC(),
    private val log: (String) -> Unit = {},
) {

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** A trigger armed by [onBeacon] and not yet run by [syncNow]. */
    private var pendingTrigger: TriggerKind? = null

    /** One sync at a time: a concurrent [syncNow] gets null, never a second link. */
    private val syncMutex = Mutex()

    /** What armed the pending trigger — the failure handling differs. */
    private enum class TriggerKind { SOLICIT, WEIGH_IN }

    /** The earliest the next sync may run (interval after success, backoff after failure). */
    private var nextSyncAllowedAtMs = Long.MIN_VALUE

    /** Consecutive failed syncs; drives the backoff growth. */
    private var consecutiveFailures = 0

    /** Until when solicit beacons stay muted after a fruitless solicit sync. */
    private var solicitMutedUntilMs = Long.MIN_VALUE

    /** Broadcast event keys already archived (bounded dedupe memory). */
    private val seenEventKeys = LinkedHashSet<Long>()

    /**
     * One advertisement, heard by the scanner. Updates the heard-state
     * ([EngineState.scaleSeen] / [EngineState.lastHeardAt] /
     * [EnginePhase.Listening]), archives a weigh-in broadcast when one is
     * attached (dedupe + plausibility first), and arms a sync trigger when
     * the beacon asks for it — a solicit (`fc` bit 9) or an archived
     * weigh-in. Purely local: never opens a connection.
     *
     * @return true when the beacon was *handled* — a weigh-in was archived
     *   or a sync trigger was armed. Visibility-only and status-only
     *   advertisements return false, and a duplicate or implausible
     *   weigh-in is discarded (also false).
     */
    suspend fun onBeacon(beacon: Beacon, weighIn: BroadcastWeighIn?): Boolean {
        heard()

        var archived = false
        if (weighIn != null) {
            archived = archiveBroadcast(beacon, weighIn)
        }

        var armed = false
        if ((archived || (weighIn == null && beacon.solicit)) && solicitMuteOk(weighIn)) {
            armed = true
            // A weigh-in trigger outranks a pending solicit trigger: a real
            // measurement is never downgraded to a silent sync.
            val kind = if (archived) TriggerKind.WEIGH_IN else TriggerKind.SOLICIT
            if (pendingTrigger != TriggerKind.WEIGH_IN) pendingTrigger = kind
        }
        return archived || armed
    }

    /**
     * Runs the pending trigger when the discipline gates allow it, and
     * suspends for the whole sync. Returns the result, or null when nothing
     * ran — no trigger is pending, the connect gate (600 s interval after a
     * success, backoff after a failure) has not expired yet, or another sync
     * is already in flight; the trigger stays armed in those cases, so it is
     * never lost.
     *
     * With [force] the gates are bypassed and a sync runs even without a
     * pending trigger (user-initiated "sync now" from Settings / the sync
     * icon). At most one sync runs at a time: even a forced [syncNow]
     * landing mid-session returns null rather than opening a second link.
     */
    suspend fun syncNow(force: Boolean = false): SyncResult? {
        val kind = pendingTrigger
        if (!force && kind == null) return null
        if (!force && !gateOpen()) return null
        if (!syncMutex.tryLock()) return null // a sync is already running
        try {
            pendingTrigger = null

            _state.update { it.copy(phase = EnginePhase.Connecting) }
            val result = runSync()
            val nowMs = clock.millis()
            if (result.success) {
                consecutiveFailures = 0
                nextSyncAllowedAtMs = nowMs + config.minConnectIntervalMs
                if (result.collected == 0) {
                    // Fruitless: nothing on the scale. Mute the solicit trigger
                    // that caused it so an idle app does not reconnect all night.
                    solicitMutedUntilMs = nowMs + config.solicitQuietMs
                }
            } else {
                consecutiveFailures++
                nextSyncAllowedAtMs = nowMs + backoffMs()
                when (kind) {
                    // Spec §3: a failed solicited sync mutes solicitations for
                    // 3 h — the trigger is dropped, not kept for retry.
                    TriggerKind.SOLICIT -> solicitMutedUntilMs = nowMs + config.solicitQuietMs
                    // A weigh-in trigger is kept: the real measurement retries
                    // once the backoff allows.
                    TriggerKind.WEIGH_IN -> pendingTrigger = kind
                    // Forced sync: there is no trigger to keep.
                    null -> Unit
                }
            }
            _state.update {
                it.copy(phase = EnginePhase.LastResult(result.code), lastResult = result)
            }
            return result
        } finally {
            syncMutex.unlock()
        }
    }

    /**
     * Deletes [recordNos] from the *scale's* memory with action 6.2 — the
     * only path that ever sends a delete. The queued-cleanup list is owned
     * by the caller (the drain path in [collect] already took it): this call
     * never drains or discards items it was not handed, and what the scale
     * refuses is re-queued for the next session. Returns how many record
     * numbers the scale actually deleted.
     */
    suspend fun cleanScaleMemory(recordNos: List<Long>): Int {
        if (recordNos.isEmpty()) return 0
        val session = openSession() ?: return 0
        try {
            session.setup()
            session.login()
            session.startPump()
            session.hello()
            session.action(7, 1, MiotCodec.param(1, ParamType.STRING, profileJson()))
            return deleteRecords(session, recordNos)
        } finally {
            runCatching { session.close() }
        }
    }

    // ---------- one sync ----------

    /** Opens one session per attempt (at most [EngineConfig.maxAttempts]). */
    private suspend fun runSync(): SyncResult {
        var attempts = 0
        var collected = 0
        while (attempts < config.maxAttempts) {
            attempts++
            val session = openSession() ?: return SyncResult(SyncKind.SCALE_ABSENT, attempts, collected)
            try {
                session.setup()
                session.login()
                session.startPump()
                session.hello()
                collected += collect(session)
                return SyncResult(SyncKind.OK, attempts, collected)
            } catch (e: ProtocolError) {
                // Silence or a refused status: the next attempt reconnects
                // from scratch — the scale does not survive a half session.
                // Logged (message only, never key material) so a silent scale
                // is diagnosable from the log pane: this catch is where the
                // milestone-0 PROP-direction bug spent its reasons.
                log("sync attempt $attempts/$config.maxAttempts failed: ${e.message}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Anything else is not retryable in-session; it fails the sync
                // outright rather than burning every attempt on the same error.
                log("sync attempt $attempts aborted: ${e::class.simpleName}: ${e.message}")
                return SyncResult(SyncKind.FAILED, attempts, collected)
            } finally {
                runCatching { session.close() }
            }
        }
        return SyncResult(SyncKind.FAILED, attempts, collected)
    }

    /** Profile push, stored fetch, queued cleanup drain, live listen. */
    private suspend fun collect(session: ScaleSession): Int {
        session.action(7, 1, MiotCodec.param(1, ParamType.STRING, profileJson()))

        // Stored fetch: 6.1 answers with the count, then pushes event 6.1
        // carrying the records string — the archive sweep of this session.
        val fetch = session.action(6, 1)
        val storedCount = (fetch.params[3] as? Int) ?: 0
        var collected = 0
        if (storedCount > 0) {
            val event = try {
                withTimeoutOrNull(config.storedEventTimeoutMs) { session.storedEvents.receive() }
            } catch (e: ClosedReceiveChannelException) {
                throw ProtocolError("session died during the stored fetch: ${e.message}")
            } ?: throw ProtocolError("no stored event for $storedCount records within ${config.storedEventTimeoutMs}ms")
            collected = archiveStored(event.params[1] as? String ?: return 0)
        }

        // Queued scale-memory cleanup (Task 11's queue): drained here, one
        // 6.2 per batch; failures stay queued for the next session.
        val queued = repo.takeScaleCleanup()
        if (queued.isNotEmpty()) deleteRecords(session, queued)

        listenLive(session)
        return collected
    }

    /** Parses the records string and merges the plausible records. */
    private suspend fun archiveStored(records: String): Int {
        val receiveUnix = clock.instant().epochSecond
        val news = records.split("_").mapNotNull { parseStored(it, receiveUnix) }
        if (news.isEmpty()) return 0
        return repo.merge(news).inserted
    }

    /** `no,mid,ut,weight,flag,ts` → [NewWeighIn]; null on garbage. */
    private fun parseStored(line: String, receiveUnix: Long): NewWeighIn? {
        val f = line.split(",")
        if (f.size < 6) return null
        val recordNo = f[0].toLongOrNull() ?: return null
        val memberMid = f[1].toLongOrNull() ?: return null
        val weight = f[3].toIntOrNull() ?: return null
        val flag = f[4].toIntOrNull() ?: return null
        val ts = f[5].toLongOrNull() ?: return null
        if (weight !in MIN_WEIGHT100..MAX_WEIGHT100) return null // implausible weight: discarded
        return NewWeighIn(
            weightGrams = weight,
            recordedAt = saneTs(ts, receiveUnix),
            source = WeighInSource.STORED,
            recordNo = recordNo,
            scaleFlag = flag,
            memberMid = memberMid,
        )
    }

    /**
     * Live listen: the 5.3 run while the user is on the scale, ended by the
     * 5.4 event (or the idle window). Commits exactly one LIVE record — the
     * 5.4's when it arrived, else the last stable 5.3 — at commit time.
     */
    private suspend fun listenLive(session: ScaleSession) {
        val startedAt = System.nanoTime()
        var commit: NewWeighIn? = null
        var lastStable: NewWeighIn? = null

        // First event: short window (the scale pushes 5.3 as weight lands).
        var msg = liveReceive(session, config.liveStartMs)
        try {
        while (msg != null) {
            val finished = msg.iid == IID_LIVE_FINISHED
            when (msg.iid) {
                IID_LIVE_WEIGHT -> {
                    val stable = (msg.params[2] as? Int) == 1
                    val weight = msg.params[3] as? Int
                    if (weight != null && weight in MIN_WEIGHT100..MAX_WEIGHT100) {
                        _state.update { it.copy(live = LiveReading(weight, stable)) }
                    }
                    if (stable && weight != null && weight in MIN_WEIGHT100..MAX_WEIGHT100) {
                        lastStable = NewWeighIn(
                            weightGrams = weight,
                            recordedAt = clock.instant().epochSecond,
                            source = WeighInSource.LIVE,
                            recordNo = null,
                            // No 5.4 arrived: who stood on the scale is
                            // ambiguous, so the record carries guest markers
                            // (flag 2, mid 0) → NEEDS_REVIEW, never auto-pushed.
                            scaleFlag = AMBIGUOUS_FLAG,
                            memberMid = AMBIGUOUS_MID,
                        )
                    }
                }

                IID_LIVE_FINISHED -> {
                    val parsed = parseFinished(msg.params[4] as? String ?: "")
                    if (parsed != null) commit = parsed
                }
            }
            // 5.4 ends the listen: the weighing is over.
            if (finished) break
            if (System.nanoTime() - startedAt > config.liveMaxMs * NANOS_PER_MS) break
            msg = liveReceive(session, config.liveIdleMs)
        }
        } finally {
            // The reading is only meaningful while someone is on the scale.
            _state.update { it.copy(live = null) }
        }

        val record = commit ?: lastStable ?: return
        repo.merge(listOf(record))
    }

    /**
     * Event 5.4, param 4: `mid,user_type,weight,flag,ts`. The `recordedAt`
     * is the commit time (ruling), so only plausibility of weight matters;
     * the timestamp field is carried through the sanity replacement.
     */
    private fun parseFinished(payload: String): NewWeighIn? {
        val f = payload.split(",")
        if (f.size < 5) return null
        val memberMid = f[0].toLongOrNull() ?: return null
        val weight = f[2].toIntOrNull() ?: return null
        val flag = f[3].toIntOrNull() ?: return null
        if (weight !in MIN_WEIGHT100..MAX_WEIGHT100) return null
        return NewWeighIn(
            weightGrams = weight,
            recordedAt = clock.instant().epochSecond,
            source = WeighInSource.LIVE,
            recordNo = null,
            scaleFlag = flag,
            memberMid = memberMid,
        )
    }

    /** One live event within [timeoutMs]; null on idle timeout or a dead link. */
    private suspend fun liveReceive(session: ScaleSession, timeoutMs: Long): MiotMessage? =
        try {
            withTimeoutOrNull(timeoutMs) { session.liveEvents.receive() }
        } catch (e: ClosedReceiveChannelException) {
            null
        }

    /** Action 6.2 per batch; failed batches fall back per record. */
    private suspend fun deleteRecords(session: ScaleSession, recordNos: List<Long>): Int {
        var removed = 0
        val failed = mutableListOf<Long>()
        for (batch in recordNos.chunked(CLEANUP_BATCH)) {
            try {
                session.action(6, 2, MiotCodec.param(2, ParamType.STRING, batch.joinToString(",")))
                removed += batch.size
            } catch (_: ProtocolError) {
                if (batch.size == 1) {
                    failed += batch
                } else {
                    // Retry one by one: a bad record number must not hold
                    // the rest of the batch hostage.
                    for (no in batch) {
                        try {
                            session.action(6, 2, MiotCodec.param(2, ParamType.STRING, no.toString()))
                            removed++
                        } catch (_: ProtocolError) {
                            failed += no
                        }
                    }
                }
            }
        }
        if (failed.isNotEmpty()) repo.queueScaleCleanup(failed)
        return removed
    }

    // ---------- trigger helpers ----------

    /** Records that the scale was heard — any advertisement counts. */
    private fun heard() {
        _state.update {
            it.copy(
                scaleSeen = true,
                lastHeardAt = clock.millis(),
                // A running sync owns the phase; hearing never stomps it.
                phase = if (syncMutex.isLocked) it.phase else EnginePhase.Listening,
            )
        }
    }

    /**
     * Archives one weigh-in broadcast: dedupe by event key, plausibility
     * gate, timestamp sanity. Returns false when the record was discarded.
     */
    private suspend fun archiveBroadcast(beacon: Beacon, weighIn: BroadcastWeighIn): Boolean {
        if (beacon.eventKey in seenEventKeys) return false
        seenEventKeys += beacon.eventKey
        if (seenEventKeys.size > EVENT_KEY_MEMORY) {
            val eldest = seenEventKeys.first()
            seenEventKeys.remove(eldest)
        }
        if (weighIn.weightGrams !in MIN_WEIGHT100..MAX_WEIGHT100) return false
        repo.merge(
            listOf(
                NewWeighIn(
                    weightGrams = weighIn.weightGrams,
                    recordedAt = saneTs(weighIn.unixTs, clock.instant().epochSecond),
                    source = WeighInSource.BROADCAST,
                    recordNo = null,
                    scaleFlag = 0,
                    memberMid = weighIn.profileId.toLong(),
                ),
            ),
        )
        return true
    }

    /**
     * A solicit trigger stays muted after a failed or fruitless solicit sync
     * (the idle app stops waking for a scale with nothing to give); weigh-in
     * triggers are never muted — a real measurement always syncs.
     */
    private fun solicitMuteOk(weighIn: BroadcastWeighIn?): Boolean {
        if (weighIn != null) return true
        return clock.millis() >= solicitMutedUntilMs
    }

    /** The connect gate: interval after success, backoff after failure. */
    private fun gateOpen(): Boolean =
        nextSyncAllowedAtMs == Long.MIN_VALUE || clock.millis() >= nextSyncAllowedAtMs

    /** Exponential in the failure streak: 60 s, 120 s, … capped at 30 min. */
    private fun backoffMs(): Long {
        val shift = (consecutiveFailures - 1).coerceIn(0, 16)
        return (config.backoffBaseMs shl shift).coerceAtMost(config.backoffMaxMs)
    }

    private suspend fun profileJson(): String {
        val p = profile()
        return ProfileJson.build(
            mid = p.mid,
            age = p.age,
            sex = p.sex,
            heightCm = p.heightCm,
            weightGrams = p.weightGrams,
            nowUnix = clock.instant().epochSecond,
        )
    }

    /** Implausible timestamp (scale clock unset / absurd) → receive time. */
    private fun saneTs(ts: Long, receiveUnix: Long): Long =
        if (ts < MIN_PLAUSIBLE_TS || ts > receiveUnix + MAX_FUTURE_SKEW_S) receiveUnix else ts

    private companion object {
        /** Sanity floor: 2024-01-01 00:00:00 UTC. */
        const val MIN_PLAUSIBLE_TS = 1_704_067_200L

        /** A scale clock may not run this far ahead of ours. */
        const val MAX_FUTURE_SKEW_S = 300L

        /** Weight plausibility, 1/100 kg: 1 kg .. 300 kg. */
        const val MIN_WEIGHT100 = 100
        const val MAX_WEIGHT100 = 30000

        /** Live event iids (siid 5). */
        const val IID_LIVE_WEIGHT = 3
        const val IID_LIVE_FINISHED = 4

        /** Record numbers per 6.2 call. */
        const val CLEANUP_BATCH = 20

        /**
         * Attribution markers for a 5.3-only LIVE commit (no 5.4 arrived):
         * flag 2 + mid 0 classify as NEEDS_REVIEW per the merger's rule —
         * a guest standing on the scale is never pushed as the owner.
         */
        const val AMBIGUOUS_FLAG = 2
        const val AMBIGUOUS_MID = 0L

        /** Broadcast event keys remembered for dedupe. */
        const val EVENT_KEY_MEMORY = 256

        const val NANOS_PER_MS = 1_000_000L
    }
}

/** Tunables; defaults are the plan's Global Constraints thresholds. */
data class EngineConfig(
    val minConnectIntervalMs: Long = 600_000L,
    val maxAttempts: Int = 2,
    val solicitQuietMs: Long = 10_800_000L,
    val backoffBaseMs: Long = 60_000L,
    val backoffMaxMs: Long = 1_800_000L,
    val liveStartMs: Long = 5_000L,
    val liveIdleMs: Long = 15_000L,
    val liveMaxMs: Long = 600_000L,
    val storedEventTimeoutMs: Long = 5_000L,
)

/** What a sync ended as. `LastResult` phases carry the [SyncResult.code]. */
sealed class EnginePhase {
    /** Nothing heard yet. */
    object Idle : EnginePhase()

    /** An advertisement was heard; a trigger may be armed. */
    object Listening : EnginePhase()

    /** A sync is running (transient, between [syncNow] calls). */
    object Connecting : EnginePhase()

    /** The most recent sync ended with this code (0 = OK). */
    data class LastResult(val code: Int) : EnginePhase()
}

/** Why a sync ended. */
enum class SyncKind { OK, SCALE_ABSENT, FAILED }

/**
 * The outcome of one [SyncEngine.syncNow]: [success] means the session ran
 * to completion — possibly fruitless ([collected] 0, which the engine reads
 * as a mute-worthy result for solicit triggers).
 */
data class SyncResult(
    val kind: SyncKind,
    val attempts: Int = 0,
    val collected: Int = 0,
) {
    val success: Boolean get() = kind == SyncKind.OK

    /** The phase code shown after the sync (0 = OK). */
    val code: Int get() = kind.ordinal
}

/** Observable engine state for the Task 11 UI. */
data class EngineState(
    val phase: EnginePhase = EnginePhase.Idle,
    /** True once the scale has been heard at all (advertisement or session). */
    val scaleSeen: Boolean = false,
    /** Last time the scale was heard — advertisement or session, millis. */
    val lastHeardAt: Long? = null,
    /** The most recent sync's outcome, if any sync has run. */
    val lastResult: SyncResult? = null,
    /** The 5.3 reading while someone stands on the scale; null otherwise. */
    val live: LiveReading? = null,
)

/** One live 5.3 weight (1/100 kg) and whether the scale marked it stable. */
data class LiveReading(val weightGrams: Int, val stable: Boolean)
