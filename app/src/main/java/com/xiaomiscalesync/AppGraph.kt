package com.xiaomiscalesync

import android.content.Context
import android.util.Log
import com.xiaomiscalesync.ble.Beacon
import com.xiaomiscalesync.ble.BroadcastWeighIn
import com.xiaomiscalesync.ble.ScaleKeys
import com.xiaomiscalesync.ble.ScaleScanner
import com.xiaomiscalesync.data.ArchiveDb
import com.xiaomiscalesync.data.Category
import com.xiaomiscalesync.data.Secrets
import com.xiaomiscalesync.data.SettingsStore
import com.xiaomiscalesync.data.WeighInRepository
import com.xiaomiscalesync.garmin.GarminAuthError
import com.xiaomiscalesync.garmin.GarminClient
import com.xiaomiscalesync.garmin.GarminPusher
import com.xiaomiscalesync.proto.toProfile
import com.xiaomiscalesync.sync.SyncEngine
import com.xiaomiscalesync.work.PushWorker
import com.xiaomiscalesync.work.enqueueIfPending
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

/**
 * The production object graph, one per process: the real [Secrets],
 * [SettingsStore], Room archive, Garmin client/pusher, [ScaleScanner] and
 * the single [SyncEngine] whose discipline state (interval, backoff, mute
 * windows) must outlive any one screen. The main screen (Task 15),
 * [PushWorker] and later Settings (Task 17) all share it.
 *
 * Sync → push hand-off: after a handled beacon runs `syncNow()`, a
 * one-shot [PushWorker] is enqueued when anything is pending — the
 * capture-time enqueue the worker's KDoc reserves for Task 15.
 */
class AppGraph private constructor(private val appContext: Context) {

    val settings: SettingsStore = SettingsStore.of(appContext)

    val secrets = Secrets(appContext)

    val repo = WeighInRepository(ArchiveDb.build(appContext).archiveDao(), settings)

    private val http = OkHttpClient()

    /**
     * Background pushes never prompt: a refresh that would need MFA fails
     * as auth-required and Settings (Task 17) offers the interactive re-login.
     */
    private val garmin = GarminClient(
        http,
        secrets,
        mfa = { throw GarminAuthError("Garmin MFA needs an interactive sign-in") },
    )

    val pusher = GarminPusher(repo, garmin, Clock.systemDefaultZone())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val scanner = ScaleScanner(
        appContext,
        scope,
        keys = {
            secrets.mac()?.let { ScaleKeys(it, secrets.token(), secrets.bleKey()) }
        },
        log = ::log,
        onBeacon = { beacon, weighIn -> handleBeacon(beacon, weighIn) },
    ).apply {
        // Closed until the Home tab opens it (resumed + listening switch on).
        allowed = false
    }

    val engine = SyncEngine(
        repo,
        openSession = { scanner.openSession() },
        profile = {
            val latestMine = repo.observe(Category.MINE).first().firstOrNull()
            settings.current().toProfile(latestMine?.weightGrams)
        },
        log = ::log,
    )

    /**
     * The scanner-loop obligation pinned in Task 9: a handled beacon
     * (weigh-in archived or trigger armed) runs `syncNow()`; the engine's
     * gates decide whether a link actually opens. Then queue a Garmin push
     * for whatever the broadcast or session left pending.
     */
    private suspend fun handleBeacon(beacon: Beacon, weighIn: BroadcastWeighIn?) {
        if (!engine.onBeacon(beacon, weighIn)) return
        val synced = engine.syncNow() != null
        // Only a broadcast weigh-in or a sync that ran can add rows; the
        // scale re-advertises for a while after each weighing, and queuing
        // a pass per repeat stacked ~8 no-op workers on the device.
        if ((weighIn != null || synced) && repo.pending().isNotEmpty()) {
            PushWorker.enqueue(appContext)
        }
    }

    /**
     * The app-open push (plan Task 17): one [PushWorker] pass when anything
     * is pending — e.g. rows left PENDING by a network failure last time.
     */
    suspend fun pushIfPending() {
        enqueueIfPending({ repo.pending().size }) { PushWorker.enqueue(appContext) }
    }

    /** Logcat only; every caller passes masked/sanitized text (no key material). */
    private fun log(line: String) {
        Log.i(TAG, line)
    }

    companion object {
        private const val TAG = "ScaleSync"

        @Volatile
        private var instance: AppGraph? = null

        fun of(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also { instance = it }
            }
    }
}
