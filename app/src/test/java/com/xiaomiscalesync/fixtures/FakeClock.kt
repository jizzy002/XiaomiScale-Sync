package com.xiaomiscalesync.fixtures

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A [Clock] the tests move by hand — the SyncEngine's discipline gates
 * (600 s connect interval, failure backoff, 3 h solicit mute) read time
 * through it, so a test can jump past a gate with [advance] instead of
 * actually waiting out the wall clock.
 *
 * Deliberately frozen: only [advance]/[set] move it. The engine's short
 * waits (live-listen idle timeouts) run on real coroutine delays, scaled
 * down via [com.xiaomiscalesync.sync.EngineConfig] in the tests.
 */
class FakeClock(private var nowMs: Long) : Clock() {

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = Instant.ofEpochMilli(nowMs)

    override fun millis(): Long = nowMs

    /** Moves the clock forward — crossing a backoff/interval gate. */
    fun advance(deltaMs: Long) {
        nowMs += deltaMs
    }

    /** Jumps the clock to an absolute instant (millis since epoch). */
    fun set(ms: Long) {
        nowMs = ms
    }
}
