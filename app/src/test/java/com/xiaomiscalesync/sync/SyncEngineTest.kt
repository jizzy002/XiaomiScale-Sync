package com.xiaomiscalesync.sync

import com.xiaomiscalesync.ble.Beacon
import com.xiaomiscalesync.ble.BroadcastWeighIn
import com.xiaomiscalesync.data.ArchiveDao
import com.xiaomiscalesync.data.Category
import com.xiaomiscalesync.data.GarminStatus
import com.xiaomiscalesync.data.ScaleCleanupItem
import com.xiaomiscalesync.data.Settings
import com.xiaomiscalesync.data.SettingsSource
import com.xiaomiscalesync.data.WeighInRecord
import com.xiaomiscalesync.data.WeighInRepository
import com.xiaomiscalesync.data.WeighInSource
import com.xiaomiscalesync.fixtures.FakeClock
import com.xiaomiscalesync.fixtures.FakeScale
import com.xiaomiscalesync.fixtures.LoopbackTransport
import com.xiaomiscalesync.fixtures.loopback
import com.xiaomiscalesync.proto.Profile
import com.xiaomiscalesync.proto.ProfileJson
import com.xiaomiscalesync.proto.ScaleSession
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SyncEngine]: connection discipline (fake clock, real loopback sessions
 * against [FakeScale]), the session orchestration sequence, archive gating
 * and the LIVE-record rules.
 *
 * Every test wires a fresh engine: hand-moved [FakeClock] + a [Rig] that
 * creates one fresh loopback/[FakeScale] pair per connection attempt (the
 * scale is a fresh connection each time; the rig aggregates the
 * `connectCount`/`deletedRecordNos`/`actionsReceived` assertions the plan
 * asks for). Live-listen waits are scaled down through [EngineConfig];
 * discipline windows (600 s interval, backoff, 3 h solicit mute) move only
 * through the fake clock.
 */
class SyncEngineTest {

    /** The scale's miauth token (12 bytes, the cloud TOKEN); both ends agree. */
    private val token = hex("0123456789abcdef01234567")

    /** Frozen "now": 1790000100 unix seconds — plausible, post-2024. */
    private val now0Ms = 1_790_000_100_000L
    private val now0 = now0Ms / 1000

    // ---------- harness ----------

    /** One connection attempt's worth of fake scale + link, plus aggregates. */
    private class Rig(
        private val token: ByteArray,
        private val configure: (FakeScale) -> Unit,
    ) {
        val fakes = mutableListOf<FakeScale>()
        val appEnds = mutableListOf<LoopbackTransport>()

        /** Engine connection attempts (one [open] per attempt). */
        var connectCount = 0
            private set

        /** Record numbers deleted via action 6.2, across all connections. */
        val deletedRecordNos: List<Long> get() = fakes.flatMap { it.deletedRecordNos }

        /** Every action received, across all connections, in order. */
        val actionsReceived: List<Pair<Int, Int>> get() = fakes.flatMap { it.actionsReceived }

        /** The app end of the most recent connection (for close assertions). */
        val lastAppEnd: LoopbackTransport get() = appEnds.last()

        fun open(): ScaleSession {
            connectCount++
            val (app, scaleEnd) = loopback()
            val fake = FakeScale(token)
            configure(fake)
            fake.accept(scaleEnd)
            fakes += fake
            appEnds += app as LoopbackTransport
            return ScaleSession(fake.watch(app), token)
        }
    }

    /** Everything one test needs, wired the way the app wires it. */
    private inner class Harness(
        configure: (FakeScale) -> Unit = { it.recordsString = "" },
        config: EngineConfig = EngineConfig(liveStartMs = 100, liveIdleMs = 100, liveMaxMs = 3_000),
    ) {
        val clock = FakeClock(now0Ms)
        val dao = FakeDao()
        val repo = WeighInRepository(
            dao,
            SettingsSource { Settings(onboarded = true, backfillCutoff = null, mid = "1") },
            nowUnix = { now0 },
        )
        val rig = Rig(token, configure)
        val engine = SyncEngine(repo, { rig.open() }, Profile(), config, clock)

        val rows: List<WeighInRecord> get() = dao.rows.value
    }

    /** In-memory stand-in for the Room DAO (same contract, no SQLite). */
    private class FakeDao : ArchiveDao {
        private var nextId = 1L
        val rows = MutableStateFlow<List<WeighInRecord>>(emptyList())
        val scaleCleanup = MutableStateFlow<List<Long>>(emptyList())

        override fun observeAll(): Flow<List<WeighInRecord>> =
            rows.map { list -> list.sortedByDescending { it.recordedAt } }

        override fun observeByCategory(category: Category): Flow<List<WeighInRecord>> =
            rows.map { list -> list.filter { it.category == category }.sortedByDescending { it.recordedAt } }

        override suspend fun all(): List<WeighInRecord> = rows.value

        override suspend fun insert(record: WeighInRecord): Long {
            val conflict = rows.value.any {
                it.recordedAt == record.recordedAt && it.weightGrams == record.weightGrams
            }
            if (conflict) return -1L
            val id = nextId++
            rows.value = rows.value + record.copy(id = id)
            return id
        }

        override suspend fun fillRecordNo(id: Long, recordNo: Long) {
            rows.value = rows.value.map {
                if (it.id == id && it.recordNo == null) it.copy(recordNo = recordNo) else it
            }
        }

        override suspend fun setCategory(id: Long, category: Category) {
            rows.value = rows.value.map { if (it.id == id) it.copy(category = category) else it }
        }

        override suspend fun delete(ids: List<Long>) {
            rows.value = rows.value.filterNot { it.id in ids }
        }

        override suspend fun deleteAll() {
            rows.value = emptyList()
        }

        override suspend fun rowsWith(status: GarminStatus, category: Category): List<WeighInRecord> =
            rows.value.filter { it.garminStatus == status && it.category == category }

        override suspend fun setGarminStatus(ids: List<Long>, status: GarminStatus) {
            rows.value = rows.value.map { if (it.id in ids) it.copy(garminStatus = status) else it }
        }

        override suspend fun setGarminStatusAt(ids: List<Long>, status: GarminStatus, pushedAt: Long) {
            rows.value = rows.value.map {
                if (it.id in ids) it.copy(garminStatus = status, pushedAt = pushedAt) else it
            }
        }

        override suspend fun queueCleanup(items: List<ScaleCleanupItem>) {
            val known = scaleCleanup.value.toMutableSet()
            scaleCleanup.value = scaleCleanup.value + items.filter { known.add(it.recordNo) }.map { it.recordNo }
        }

        override suspend fun queuedCleanup(): List<ScaleCleanupItem> =
            scaleCleanup.value.map { ScaleCleanupItem(recordNo = it) }

        override suspend fun clearCleanup() {
            scaleCleanup.value = emptyList()
        }
    }

    // ---------- advertisement helpers ----------

    private fun beacon(
        fc: Int,
        counter: Int,
        solicit: Boolean = fc and 0x0200 != 0,
        objects: List<Pair<Int, ByteArray>> = emptyList(),
    ) = Beacon(
        frameControl = fc,
        frameCounter = counter,
        solicit = solicit,
        eventKey = counter.toLong() shl 24,
        objects = objects,
    )

    /** fc 0x5910 — awake, no object, no connect request. */
    private fun plainBeacon(counter: Int) = beacon(0x5910, counter, solicit = false)

    /** fc 0x5b10 — "connect to me". */
    private fun solicitBeacon(counter: Int) = beacon(0x5b10, counter)

    /** fc 0x5948 — encrypted object frame (the weigh-in broadcast). */
    private fun weighInBeacon(counter: Int) = beacon(0x5948, counter, solicit = false)

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ---------- (a) visibility-only / status-only never connects ----------

    @Test(timeout = 20_000)
    fun visibilityOnlyAndStatusOnlyAdvertisementsNeverConnect() = runBlocking<Unit> {
        val h = Harness()

        // Awake advertising, no object: heard, but no trigger.
        assertFalse(h.engine.onBeacon(plainBeacon(1), null))
        assertNull(h.engine.syncNow())

        // The ~50-minute status broadcast (0x6623 object): still no trigger.
        val status = beacon(0x5958, 2, solicit = false, objects = listOf(0x6623 to byteArrayOf(0)))
        assertFalse(h.engine.onBeacon(status, null))
        assertNull(h.engine.syncNow())

        assertEquals(0, h.rig.connectCount)
        assertTrue(h.rig.fakes.isEmpty())
    }

    // ---------- (b) weigh-in broadcast archives AND connects exactly once ----------

    @Test(timeout = 20_000)
    fun weighInBroadcastArchivesAndTriggersExactlyOneConnect() = runBlocking<Unit> {
        val h = Harness()

        val archived = h.engine.onBeacon(
            weighInBeacon(3),
            BroadcastWeighIn(profileId = 1, weightGrams = 8025, unixTs = 1_789_999_000),
        )
        assertTrue(archived)
        val row = h.rows.single()
        assertEquals(8025, row.weightGrams)
        assertEquals(1_789_999_000L, row.recordedAt)
        assertEquals(WeighInSource.BROADCAST, row.source)
        assertEquals(Category.MINE, row.category) // profile 1, flag 0, mid "1"
        assertEquals(GarminStatus.PENDING, row.garminStatus)

        val result = h.engine.syncNow()
        assertTrue(result!!.success)
        assertEquals(1, result.attempts)
        assertEquals(1, h.rig.connectCount) // exactly one connect, not one per beacon
    }

    // ---------- (c) solicit (fc 0x5b10) connects ----------

    @Test(timeout = 20_000)
    fun solicitBeaconConnects() = runBlocking<Unit> {
        val h = Harness()

        assertTrue(h.engine.onBeacon(solicitBeacon(4), null))
        val result = h.engine.syncNow()

        assertTrue(result!!.success)
        assertEquals(1, h.rig.connectCount)
    }

    // ---------- (d) 600 s minimum interval between connects ----------

    @Test(timeout = 20_000)
    fun secondConnectWithinMinIntervalIsSkippedThenAllowed() = runBlocking<Unit> {
        val h = Harness()

        h.engine.onBeacon(weighInBeacon(5), BroadcastWeighIn(1, 8025, 1_789_999_000))
        assertTrue(h.engine.syncNow()!!.success)
        assertEquals(1, h.rig.connectCount)

        // 10 s later the scale offers another weigh-in: archived, but no connect.
        h.clock.advance(10_000)
        assertTrue(h.engine.onBeacon(weighInBeacon(6), BroadcastWeighIn(1, 8100, 1_789_999_010)))
        assertNull(h.engine.syncNow()) // inside the 600 s window
        assertEquals(1, h.rig.connectCount)

        // The trigger is not lost: past the interval the sync runs.
        h.clock.advance(600_000)
        assertTrue(h.engine.syncNow()!!.success)
        assertEquals(2, h.rig.connectCount)
    }

    // ---------- (e) 2-attempt cap, then backoff gates the next attempt ----------

    @Test(timeout = 60_000)
    fun failingLoginsCapAtTwoAttemptsThenBackoffGates() = runBlocking<Unit> {
        // The scale refuses to complete the miauth login (silent after rand_key).
        val h = Harness(configure = { it.silentAfterRandKey = true })

        h.engine.onBeacon(weighInBeacon(7), BroadcastWeighIn(1, 8025, 1_789_999_000))

        val first = h.engine.syncNow()
        assertFalse(first!!.success)
        assertEquals(2, first.attempts) // maxAttempts per sync
        assertEquals(2, h.rig.connectCount)

        // Backoff: no attempt until the clock moves past it (trigger kept).
        assertNull(h.engine.syncNow())
        assertEquals(2, h.rig.connectCount)

        h.clock.advance(61_000) // > backoffBaseMs (60 s), way inside the 600 s interval
        val second = h.engine.syncNow()
        assertFalse(second!!.success)
        assertEquals(2, second.attempts)
        assertEquals(4, h.rig.connectCount) // gated by backoff, not by the 600 s interval

        assertTrue(h.rig.fakes.all { it.errors.isEmpty() }) // silence, not protocol errors
    }

    // ---------- (f) stored records archive; no 6.2, no 4.3 ----------

    @Test(timeout = 20_000)
    fun successfulSyncArchivesStoredRecordsWithoutDeletingOrClaiming() = runBlocking<Unit> {
        val h = Harness(configure = {
            it.recordsString = "41,1,1,8025,0,1789999000_42,0,0,7260,2,1789999060"
        })

        h.engine.onBeacon(solicitBeacon(8), null)
        val result = h.engine.syncNow()

        assertTrue(result!!.success)
        assertEquals(2, result.collected)

        // Mapping ruling: ts→recordedAt, no→recordNo, mid→memberMid, flag→scaleFlag.
        val mine = h.rows.single { it.weightGrams == 8025 }
        assertEquals(41L, mine.recordNo)
        assertEquals(1_789_999_000L, mine.recordedAt)
        assertEquals(1L, mine.memberMid)
        assertEquals(0, mine.scaleFlag)
        assertEquals(Category.MINE, mine.category)
        val other = h.rows.single { it.weightGrams == 7260 }
        assertEquals(42L, other.recordNo)
        assertEquals(Category.NEEDS_REVIEW, other.category)

        // Never 6.2 (queue empty), never 4.3 (never, ever).
        assertTrue(h.rig.deletedRecordNos.isEmpty())
        assertEquals(listOf(7 to 1, 6 to 1), h.rig.actionsReceived)
        assertFalse(h.rig.actionsReceived.any { it.first == 4 && it.second == 3 })
    }

    // ---------- (g) 6.2 only via the explicit cleanup API ----------

    @Test(timeout = 20_000)
    fun cleanScaleMemorySends62AndNothingElseEverDoes() = runBlocking<Unit> {
        val h = Harness()

        val deleted = h.engine.cleanScaleMemory(listOf(41))

        assertEquals(1, deleted)
        assertEquals(listOf(41L), h.rig.deletedRecordNos)
        assertEquals(listOf(7 to 1, 6 to 2), h.rig.actionsReceived)
        assertFalse(h.rig.actionsReceived.any { it.first == 4 && it.second == 3 })
        assertEquals(1, h.rig.connectCount)
    }

    // ---------- (h) live path: 5.3 ×3 then 5.4 → exactly one LIVE record ----------

    @Test(timeout = 20_000)
    fun liveScriptCommitsExactlyOneLiveRecordAndClosesAfterIdle() = runBlocking<Unit> {
        val h = Harness(configure = { fake ->
            fake.recordsString = ""
            fake.liveScriptOnFetch += {
                fake.emitLive(7000, stable = false)
                fake.emitLive(7010, stable = false)
                fake.emitLive(7020, stable = false)
                fake.emitFinished(
                    mid = 1, userType = 1, weightGrams = 7025, flag = 0, ts = 1_790_000_050,
                )
            }
        })

        h.engine.onBeacon(solicitBeacon(9), null)
        val result = h.engine.syncNow()
        assertTrue(result!!.success)

        // Exactly one record, LIVE, from the 5.4 weight; recordedAt = commit time.
        val row = h.rows.single()
        assertEquals(WeighInSource.LIVE, row.source)
        assertEquals(7025, row.weightGrams)
        assertEquals(now0, row.recordedAt)
        assertEquals(Category.MINE, row.category) // 5.4: flag 0, mid 1
        assertNull(row.recordNo)

        // The engine closed the session after the idle window.
        assertTrue(h.rig.lastAppEnd.notifications.values.all { it.isClosedForReceive })
        assertEquals(1, h.rig.connectCount)
    }

    // ---------- (i) pinned profile template ----------

    @Test
    fun profileJsonMatchesPinnedTemplate() {
        val json = ProfileJson.build(
            mid = "1", age = 30, sex = 1, heightCm = 180, weightGrams = 8025, nowUnix = 1_790_000_000L,
        )
        assertEquals(
            """{"mid":"1","duid":1,"uc":1,"ow":1,"unit":1,"time":1790000000,"ud":[{"duid":1,"ut":1,"age":30,"sex":1,"hi":180,"wt":8025}]}""",
            json,
        )
    }

    // ---------- (j) queued cleanup drains via 6.2 during a sync ----------

    @Test(timeout = 20_000)
    fun syncDrainsQueuedScaleCleanupVia62() = runBlocking<Unit> {
        val h = Harness()
        h.repo.queueScaleCleanup(listOf(7))

        h.engine.onBeacon(solicitBeacon(10), null)
        assertTrue(h.engine.syncNow()!!.success)

        assertEquals(listOf(7L), h.rig.deletedRecordNos)
        assertEquals(listOf(7 to 1, 6 to 1, 6 to 2), h.rig.actionsReceived)
        assertTrue(h.repo.takeScaleCleanup().isEmpty()) // handed out exactly once
    }

    // ---------- (k) failed 6.2 re-queues the record numbers ----------

    @Test(timeout = 20_000)
    fun failedScaleCleanupRequeuesRecordNos() = runBlocking<Unit> {
        val h = Harness(configure = {
            it.recordsString = ""
            it.failDeleteStatus = 5
        })

        val deleted = h.engine.cleanScaleMemory(listOf(41))

        assertEquals(0, deleted)
        assertTrue(h.rig.deletedRecordNos.isEmpty()) // the scale stored no deletion
        // The hand-out is restored: the next session's 6.2 still gets them.
        assertEquals(listOf(41L), h.repo.takeScaleCleanup())
        assertEquals(1, h.rig.connectCount)
    }

    // ---------- (l) EngineState: scaleSeen + lastHeardAt + phase ----------

    @Test(timeout = 20_000)
    fun engineStateExposesScaleSeenAndLastHeardAt() = runBlocking<Unit> {
        val h = Harness()

        assertEquals(EngineState(), h.engine.state.value) // Idle, never heard

        h.engine.onBeacon(plainBeacon(11), null)
        val heard = h.engine.state.value
        assertTrue(heard.scaleSeen)
        assertEquals(now0Ms, heard.lastHeardAt!!)
        assertEquals(EnginePhase.Listening, heard.phase)

        h.engine.onBeacon(solicitBeacon(12), null)
        val result = h.engine.syncNow()
        assertTrue(result!!.success)
        val done = h.engine.state.value
        assertEquals(EnginePhase.LastResult(0), done.phase)
        assertTrue(done.scaleSeen) // remembered across the sync
        assertEquals(now0Ms, done.lastHeardAt!!)
    }

    // ---------- (m) stored records: timestamp sanity + weight gate ----------

    @Test(timeout = 20_000)
    fun storedRecordsImplausibleTimestampsReplacedAndBadWeightsDiscarded() = runBlocking<Unit> {
        val h = Harness(configure = {
            it.recordsString = listOf(
                "50,1,1,8025,0,1789999000", // plausible → kept as-is
                "51,1,1,50,0,1789999060",   // 0.5 kg → discarded
                "52,1,1,8100,0,0",          // scale clock unset → receive time
                "53,1,1,8200,0,1790000600", // now+500 s (future) → receive time
            ).joinToString("_")
        })

        h.engine.onBeacon(solicitBeacon(13), null)
        val result = h.engine.syncNow()
        assertTrue(result!!.success)
        assertEquals(3, result.collected) // the implausible weight never lands

        val pairs = h.rows.map { it.weightGrams to it.recordedAt }.toSet()
        assertEquals(
            setOf(8025 to 1_789_999_000L, 8100 to now0, 8200 to now0),
            pairs,
        )
        assertTrue(h.rows.none { it.weightGrams == 50 })
    }

    // ---------- (n) broadcast: timestamp sanity + weight gate ----------

    @Test(timeout = 20_000)
    fun broadcastImplausibleTimestampReplacedAndBadWeightDiscarded() = runBlocking<Unit> {
        val h = Harness()

        // Implausible weight: discarded, and it never triggers a connect.
        assertFalse(h.engine.onBeacon(weighInBeacon(14), BroadcastWeighIn(1, 50, 1_789_999_000)))
        assertTrue(h.rows.isEmpty())
        assertNull(h.engine.syncNow())
        assertEquals(0, h.rig.connectCount)

        // Implausible timestamp: the record survives with receive time (spec:
        // "scale clock unset" — never dropped).
        assertTrue(h.engine.onBeacon(weighInBeacon(15), BroadcastWeighIn(1, 8025, unixTs = 0)))
        assertEquals(now0, h.rows.single().recordedAt)
    }

    // ---------- (o) broadcast dedupe by event key ----------

    @Test(timeout = 20_000)
    fun sameBroadcastEventKeyArchivesOnlyOnce() = runBlocking<Unit> {
        val h = Harness()
        val beacon = weighInBeacon(16)
        val weighIn = BroadcastWeighIn(1, 8025, 1_789_999_000)

        assertTrue(h.engine.onBeacon(beacon, weighIn))
        assertFalse(h.engine.onBeacon(beacon, weighIn)) // same counter+ext: seen already
        assertEquals(1, h.rows.size)
    }

    // ---------- (p) fruitless solicit mutes solicitations for 3 h, weigh-ins unaffected ----------

    @Test(timeout = 20_000)
    fun fruitlessSolicitMutesSolicitsButNotWeighInTriggers() = runBlocking<Unit> {
        val h = Harness()

        // Fruitless solicited sync (success, nothing collected) → 3 h mute.
        h.engine.onBeacon(solicitBeacon(17), null)
        assertTrue(h.engine.syncNow()!!.success)
        assertEquals(1, h.rig.connectCount)

        h.clock.advance(600_000) // past the connect interval, inside the mute
        h.engine.onBeacon(solicitBeacon(18), null)
        assertNull(h.engine.syncNow())
        assertEquals(1, h.rig.connectCount)

        // A real weigh-in broadcast is not muted: it still connects.
        h.engine.onBeacon(weighInBeacon(19), BroadcastWeighIn(1, 8025, 1_789_999_020))
        assertTrue(h.engine.syncNow()!!.success)
        assertEquals(2, h.rig.connectCount)
    }

    // ---------- review fix (q): a FAILED solicit sync also mutes for 3 h ----------

    @Test(timeout = 60_000)
    fun failedSolicitSyncMutesSolicitsForThreeHours() = runBlocking<Unit> {
        // The scale solicits but stays silent mid-login: the sync fails.
        val h = Harness(configure = { it.silentAfterRandKey = true })

        assertTrue(h.engine.onBeacon(solicitBeacon(20), null))
        val first = h.engine.syncNow()
        assertFalse(first!!.success)
        assertEquals(2, first.attempts)
        assertEquals(2, h.rig.connectCount)

        // Past the backoff but inside the 3 h mute: fresh solicits connect nothing.
        h.clock.advance(61_000)
        assertFalse(h.engine.onBeacon(solicitBeacon(21), null))
        assertNull(h.engine.syncNow())
        assertEquals(2, h.rig.connectCount)

        // Past the mute, solicits trigger again.
        h.clock.advance(3 * 3_600_000L)
        assertTrue(h.engine.onBeacon(solicitBeacon(22), null))
        val second = h.engine.syncNow()
        assertFalse(second!!.success)
        assertEquals(4, h.rig.connectCount)
    }

    // ---------- review fix (r): explicit cleanup never discards unrelated queue items ----------

    @Test(timeout = 20_000)
    fun cleanScaleMemoryLeavesUnrelatedQueuedItemsAlone() = runBlocking<Unit> {
        val h = Harness()
        h.repo.queueScaleCleanup(listOf(9)) // re-queued by an earlier failed drain

        val deleted = h.engine.cleanScaleMemory(listOf(41))

        assertEquals(1, deleted)
        assertEquals(listOf(41L), h.rig.deletedRecordNos)
        assertEquals(listOf(9L), h.repo.takeScaleCleanup()) // still queued for the next sync
    }

    // ---------- review fix (s): 5.3-only commit is ambiguous → needs review ----------

    @Test(timeout = 20_000)
    fun stableLive53Without54CommitsNeedsReviewRecord() = runBlocking<Unit> {
        val h = Harness(configure = { fake ->
            fake.recordsString = ""
            fake.liveScriptOnFetch += {
                fake.emitLive(7100, stable = false)
                fake.emitLive(7150, stable = true)
                fake.emitLive(7160, stable = true)
                // No 5.4: the user steps off before the scale finishes.
            }
        })

        h.engine.onBeacon(solicitBeacon(23), null)
        assertTrue(h.engine.syncNow()!!.success)

        val row = h.rows.single()
        assertEquals(WeighInSource.LIVE, row.source)
        assertEquals(7160, row.weightGrams) // the last stable weight
        assertEquals(Category.NEEDS_REVIEW, row.category) // attribution is ambiguous
        assertEquals(now0, row.recordedAt) // commit time
        assertNull(row.recordNo)
    }

    // ---------- review fix (t): no second connection while a sync is in flight ----------

    @Test(timeout = 20_000)
    fun concurrentSyncNowCallsOpenExactlyOneConnection() = runBlocking<Unit> {
        val h = Harness()
        assertTrue(h.engine.onBeacon(solicitBeacon(24), null))

        val scanner = async { h.engine.syncNow() }
        val uiButton = async { h.engine.syncNow(force = true) } // lands mid-session
        val results = listOf(scanner.await(), uiButton.await())

        assertEquals(1, results.count { it != null }) // the loser gets null
        assertEquals(1, h.rig.connectCount) // exactly one connect attempt
        assertTrue(results.filterNotNull().single().success)
    }
}
