package com.xiaomiscalesync.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xiaomiscalesync.garmin.GarminPusher
import com.xiaomiscalesync.garmin.PushSummary
import kotlin.coroutines.cancellation.CancellationException

/**
 * One-shot WorkManager shell around [GarminPusher]: drain the Garmin push
 * queue once. Deliberately thin — every decision (guard, statuses, error
 * mapping) lives in the JVM-tested pusher; this class only maps the
 * [com.xiaomiscalesync.garmin.PushSummary] to a WorkManager result, behind
 * [runGarminPush]'s exception boundary.
 *
 * **Never periodic:** a push runs only when explicitly enqueued (after a
 * capture — Task 15 — and on app open — Task 17). No background scanning.
 *
 * Result mapping: `authBlocked` → `success()` (retrying would burn attempts
 * on the same 401; the row now reads `AUTH_REQUIRED` and Settings surfaces
 * re-login). `failed > 0` (rate limit / API / network — rows stayed
 * `PENDING`) → `retry()` with WorkManager's exponential backoff. Clean pass
 * → `success()`. A non-`GarminException` throwable escaping the pusher is
 * bounded the same way (logged, `retry()`) — it must never escape
 * [doWork], because an escaping exception completes the run exceptionally →
 * the WorkSpec is FAILED, not retried, and a FAILED chain member poisons
 * every later enqueue (see [enqueue]).
 */
class PushWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val pusher = pusherFactory(applicationContext)
            ?: return Result.success() // not wired yet — nothing to push, nothing to retry
        return runGarminPush(pusher::pushPending) { t ->
            // Hygiene: our exception texts carry only paths and HTTP codes; a
            // Garmin token never appears in them (TokenStore/GarminTokens
            // redact toString — Task 11), so the message is safe to log.
            Log.w(TAG, "Garmin push pass failed unexpectedly; retrying with backoff", t)
        }
    }

    companion object {
        private const val TAG = "PushWorker"

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
         * Idempotent enqueue under the shared [WORK_NAME], policy
         * [ExistingWorkPolicy.APPEND_OR_REPLACE] — Google's recommendation
         * "when you want the new work to run regardless of the status of the
         * existing work." An enqueue while a push is queued or running joins
         * that chain instead of racing it, and a new run is guaranteed
         * afterwards — closing the window where a weigh-in captured *mid-pass*
         * would otherwise miss the pass's `pending()` snapshot and wait for
         * the next trigger ([GarminPusher] reads the queue when it starts, so
         * an appended pass drains exactly what the earlier one couldn't see).
         *
         * **Plain APPEND was rejected — it can brick the queue.** Per
         * Google's docs, "if the existing work becomes CANCELLED or FAILED,
         * the new work is also CANCELLED or FAILED", and appending to a chain
         * that has failed leaves the new request FAILED. WorkManager state
         * persists across process death, so one escaped bug (a FAILED
         * WorkSpec) or one future `cancelUniqueWork` (sign-out/re-login)
         * would make every later [enqueue] silently join the dead chain and
         * never run — PENDING rows would never drain. APPEND_OR_REPLACE keeps
         * APPEND's live-chain behavior but *replaces* a chain whose work
         * failed or was cancelled, so the queue always recovers.
         */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<PushWorker>().build(),
            )
        }
    }
}

/**
 * The worker's whole decision path, extracted so it pins on the plain JVM
 * (`PushWorkerTest`): run [push] once behind the exception boundary and map
 * the outcome to a WorkManager result. [log] receives an unexpected throwable
 * (production logs it via `Log.w`).
 *
 * The boundary exists because an exception escaping `doWork` completes the
 * run exceptionally → the WorkSpec is FAILED, not retried — and under chain
 * append that failure poisons every later enqueue (see
 * `PushWorker.enqueue`). The rows were left `PENDING`, the duplicate guard
 * makes a re-run safe by construction, so `retry()` (with backoff) is always
 * the right answer here. [CancellationException] is rethrown, not swallowed:
 * a stopped worker is neither a failure nor an incident.
 */
internal suspend fun runGarminPush(
    push: suspend () -> PushSummary,
    log: (Throwable) -> Unit,
): ListenableWorker.Result = try {
    val summary = push()
    when {
        summary.authBlocked -> ListenableWorker.Result.success()
        summary.failed > 0 -> ListenableWorker.Result.retry()
        else -> ListenableWorker.Result.success()
    }
} catch (c: CancellationException) {
    throw c
} catch (t: Throwable) {
    log(t)
    ListenableWorker.Result.retry()
}
