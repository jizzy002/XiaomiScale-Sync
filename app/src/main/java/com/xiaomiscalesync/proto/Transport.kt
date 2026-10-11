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

    /** Closes the link in both directions. */
    suspend fun close()
}
