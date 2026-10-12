package com.xiaomiscalesync.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xiaomiscalesync.garmin.GarminPusher

/**
 * One-shot WorkManager shell around [GarminPusher]: drain the Garmin push
 * queue once. Deliberately thin — every decision (guard, statuses, error
 * mapping) lives in the JVM-tested pusher; this class only maps the
 * [com.xiaomiscalesync.garmin.PushSummary] to a WorkManager result.
 *
 * **Never periodic:** a push runs only when explicitly enqueued (after a
 * capture — Task 15 — and on app open — Task 17). No background scanning.
 *
 * Result mapping: `authBlocked` → `success()` (retrying would burn attempts
 * on the same 401; the row now reads `AUTH_REQUIRED` and Settings surfaces
 * re-login). `failed > 0` (rate limit / API / network — rows stayed
 * `PENDING`) → `retry()` with WorkManager's exponential backoff. Clean pass
 * → `success()`.
 */
class PushWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val pusher = pusherFactory(applicationContext)
            ?: return Result.success() // not wired yet — nothing to push, nothing to retry
        val summary = pusher.pushPending()
        return when {
            summary.authBlocked -> Result.success()
            summary.failed > 0 -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        /**
         * The single unique work name both enqueue sites share, which is what
         * makes them idempotent: capture-time (Task 15) and open-time
         * (Task 17) enqueues can never stack parallel passes — see [enqueue].
         */
        const val WORK_NAME = "garmin-push"

        /**
         * Builds the pusher for a run. Default `null` ("not wired yet" → this
         * worker is a no-op success) because the production [GarminPusher]
         * needs a token store and sign-in wiring that land with the auth
         * settings task. That wiring installs the real factory once:
         *
         * ```
         * PushWorker.pusherFactory = { ctx ->
         *     GarminPusher(
         *         repo = WeighInRepository(ArchiveDb.build(ctx).archiveDao(), SettingsStore.of(ctx)),
         *         garmin = GarminClient(OkHttpClient(), tokenStore, mfa = { "" }), // no-op prompt: only interactive sign-in asks for MFA
         *         clock = Clock.systemDefaultZone(),
         *     )
         * }
         * ```
         */
        @Volatile
        var pusherFactory: (Context) -> GarminPusher? = { null }

        /**
         * Idempotent enqueue under the shared [WORK_NAME]: with
         * [ExistingWorkPolicy.APPEND], an enqueue while a push is already
         * queued or running joins that chain instead of racing it, and a new
         * run is guaranteed afterwards — closing the window where a weigh-in
         * captured *mid-pass* would otherwise miss the pass's `pending()`
         * snapshot and wait for the next trigger. [GarminPusher] reads the
         * queue when it starts, so an appended pass drains exactly what the
         * earlier one couldn't see.
         */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND,
                OneTimeWorkRequestBuilder<PushWorker>().build(),
            )
        }
    }
}
