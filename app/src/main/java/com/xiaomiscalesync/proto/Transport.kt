package com.xiaomiscalesync.proto

import kotlinx.coroutines.channels.Channel

/** The four protocol characteristics of GATT service `0xfe95`. */
enum class CharId { LOGIN, AUTH, CMD, PROP }

/**
 * The value-transfer seam between the protocol stack and a BLE link:
 * one buffered notification channel per characteristic, plus writes and
 * a close.
 *
 * Implemented by the Android GATT transport (Task 13, BluetoothGatt) and
 * by the in-memory loopback used in tests; on both ends a write to
 * characteristic X arrives as a notification on the peer end's channel X.
 */
interface BleTransport {
    /** Incoming notifications, one buffered channel per characteristic. */
    val notifications: Map<CharId, Channel<ByteArray>>

    /** Writes [bytes] to characteristic [to]; delivered to the peer's channel for [to]. */
    suspend fun write(to: CharId, bytes: ByteArray)

    /**
     * Asks the link to deliver notifications from [char] to this end (the
     * CCCD "enable notifications" write on real GATT). The loopback's
     * channels are always live, so this is a no-op there — but the order
     * still matters to the scale (AUTH/CMD/PROP before the `a4` probe,
     * LOGIN only ~200 ms after it), and the test fake observes these
     * calls to enforce that. The Android GATT transport (Task 13)
     * overrides this with the CCCD setup.
     */
    suspend fun enableNotifications(char: CharId) {}

    /** Closes the link in both directions. */
    suspend fun close()
}
