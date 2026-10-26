package com.xiaomiscalesync.work

import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import com.xiaomiscalesync.garmin.PushSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pure-JVM tests for [runGarminPush] — the worker's exception boundary and
 * result mapping (the Task 12 review fix: an unexpected throwable must become
 * `retry()`, never an escaping exception that would complete the WorkSpec as
 * FAILED and, under chain append, poison every later enqueue).
 *
 * Instantiating a real [PushWorker] needs `work-testing`'s
 * `TestListenableWorkerBuilder`, which itself needs an Android `Context` (i.e.
 * Robolectric) — deliberately absent from this suite. So the decision path is
 * extracted into [runGarminPush] and pinned here instead. What is NOT pinned:
 * `doWork`'s factory lookup, the `Log.w` call, and
 * `ExistingWorkPolicy.APPEND_OR_REPLACE` chain semantics — WorkManager
 * runtime behavior that only an instrumentation test can observe.
 */
class PushWorkerTest {

    private fun summary(
        pushed: Int = 0,
        skippedExisting: Int = 0,
        failed: Int = 0,
        authBlocked: Boolean = false,
    ) = PushSummary(pushed, skippedExisting, failed, authBlocked)

    private fun unreachable(): Nothing = throw AssertionError("must not be reached")

    @Test
    fun cleanPassSucceedsWithoutLogging() = runBlocking {
        val logs = mutableListOf<Throwable>()
        val result = runGarminPush({ summary(pushed = 2) }) { logs += it }
        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun guardSkipsAloneStillSucceed() = runBlocking {
        val result = runGarminPush({ summary(skippedExisting = 3) }) { unreachable() }
        assertTrue(result is ListenableWorker.Result.Success)
    }

    @Test
    fun nonEmptyPushPassIsRecordedButNoOpPassIsNot() = runBlocking {
        val recorded = mutableListOf<PushSummary?>()
        runGarminPush(
            push = { summary(pushed = 2) },
            record = { value, _ -> recorded += value },
            log = { unreachable() },
        )
        runGarminPush(
            push = { summary() },
            record = { value, _ -> recorded += value },
            log = { unreachable() },
        )

        assertEquals(listOf(summary(pushed = 2)), recorded)
    }

    @Test
    fun retryableFailuresRequestBackoffRetryWithoutLogging() = runBlocking {
        // Rate limit / API / network: typed, handled by the pusher — not an incident.
        val logs = mutableListOf<Throwable>()
        val result = runGarminPush({ summary(failed = 1) }) { logs += it }
        assertTrue(result is ListenableWorker.Result.Retry)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun authBlockedDoesNotRetry() = runBlocking {
        // Re-signing in is the user's move; retrying would burn attempts on the same 401.
        val result = runGarminPush({ summary(failed = 1, authBlocked = true) }) { unreachable() }
        assertTrue(result is ListenableWorker.Result.Success)
    }

    @Test
    fun unexpectedThrowableIsBoundedLoggedAndRetried() = runBlocking {
        // The reviewer's finding, pinned: the throwable must NOT escape (which
        // would mark the WorkSpec FAILED and poison the APPEND chain); it is
        // logged once and mapped to retry() — the rows stayed PENDING and the
        // guard makes the re-run safe by construction.
        val boom = IllegalStateException("unexpected pusher bug")
        val logs = mutableListOf<Throwable>()
        val recorded = mutableListOf<Throwable?>()
        val result = runGarminPush(
            push = { throw boom },
            record = { summary, failure ->
                assertEquals(null, summary)
                recorded += failure
            },
            log = { logs += it },
        )
        assertTrue(result is ListenableWorker.Result.Retry)
        assertEquals(listOf<Throwable>(boom), logs)
        assertEquals(listOf<Throwable?>(boom), recorded)
    }

    @Test
    fun errorsAreBoundedToo() = runBlocking {
        // The boundary catches Throwable, per the ruling — even an Error path
        // becomes a logged retry instead of an escaped FAILED WorkSpec.
        val logs = mutableListOf<Throwable>()
        val result = runGarminPush({ throw OutOfMemoryError("simulated") }) { logs += it }
        assertTrue(result is ListenableWorker.Result.Retry)
        assertEquals(1, logs.size)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsRethrownNotSwallowed() = runBlocking<Unit> {
        // A stopped/cancelled worker is neither a failure nor an incident.
        runGarminPush({ throw CancellationException("worker stopped") }) { unreachable() }
    }

    // ---------- push state for Home's Garmin line ----------

    @Test
    fun pushStateMapsWorkStates() {
        assertEquals(PushState.RUNNING, pushStateOf(WorkInfo.State.RUNNING, 0))
        assertEquals(PushState.IDLE, pushStateOf(WorkInfo.State.ENQUEUED, 0)) // first run, about to start
        assertEquals(PushState.RETRY_SCHEDULED, pushStateOf(WorkInfo.State.ENQUEUED, 1))
        assertEquals(PushState.RETRY_SCHEDULED, pushStateOf(WorkInfo.State.ENQUEUED, 4))
        assertEquals(PushState.IDLE, pushStateOf(WorkInfo.State.SUCCEEDED, 2))
        assertEquals(PushState.IDLE, pushStateOf(WorkInfo.State.FAILED, 1))
        assertEquals(PushState.IDLE, pushStateOf(null, 0))
    }

    // ---------- app-open push (plan Task 17) ----------

    @Test
    fun appOpenEnqueuesOnlyWhenSomethingIsPending() = runBlocking {
        var enqueued = 0
        assertEquals(false, enqueueIfPending({ 0 }) { enqueued++ })
        assertEquals(0, enqueued)

        assertEquals(true, enqueueIfPending({ 2 }) { enqueued++ })
        assertEquals(1, enqueued)
    }
}
