package com.xiaomiscalesync.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.xiaomiscalesync.proto.BleTransport
import com.xiaomiscalesync.proto.CharId
import com.xiaomiscalesync.proto.ProtocolError
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout

/**
 * The real BLE transport: [BleTransport] over Android's [BluetoothGatt].
 *
 * Construction happens through [connect] — Android's API forces the shape
 * `connectGatt(callback) → gatt → transport`, so the factory creates the
 * callback bridge first, opens the link, then wraps the resulting gatt in
 * this class (the brief's `AndroidTransport(gatt)` constructor, plus the
 * bridge the platform requires). A hand-made instance without that wiring
 * would never receive callbacks; always use [connect].
 *
 * What it does, and why (plan Task 13 / design spec "The scale" §GATT):
 *  - characteristics are looked up **by UUID string** in service `0xfe95`
 *    (`00000010` LOGIN, `00000019` AUTH, `0000001a` CMD, `0000001b` PROP) —
 *    never by ATT handle;
 *  - [enableNotifications] performs the real CCCD (`0x2902`) write behind
 *    `setCharacteristicNotification` — the [BleTransport] seam's no-op
 *    default exists for the loopback fake; on hardware this is the CCCD
 *    setup the scale's notification order depends on;
 *  - [write] uses **write-without-response** (the reference client's write
 *    type) and awaits `onCharacteristicWrite` before returning — that local
 *    completion is the flow control that keeps framing's back-to-back
 *    parcels from outrunning Android's GATT TX queue;
 *  - notifications land in the per-characteristic [notifications] channels
 *    (unbounded, like the loopback, so a burst can never deadlock a receive
 *    loop — silence is caught by [com.xiaomiscalesync.proto.Framing]'s ack
 *    timeouts instead);
 *  - a best-effort MTU 247 request runs after connecting; its result is
 *    logged but never gates the session — the `a4` payload-size probe
 *    ([com.xiaomiscalesync.proto.ScaleSession.setup]) is authoritative.
 *
 * **Untestable on the JVM (honestly):** every line of this class touches
 * Android framework types (`BluetoothGatt*`), which are stubbed-out in the
 * unit-test android.jar; the seam above it *is* tested — `ScaleSession` /
 * `SyncEngine` run against `FakeScale` over the same [BleTransport]
 * interface, so only this platform glue is exercised by hardware. All GATT
 * failures are surfaced as [ProtocolError] (or propagate [CancellationException]),
 * so [com.xiaomiscalesync.sync.SyncEngine]'s retry/absent handling sees them
 * instead of an escaped runtime exception.
 *
 * Debug-quality warning: one GATT operation at a time is serialized by the
 * caller's order ([com.xiaomiscalesync.proto.ScaleSession] never overlaps
 * them); each stage has its own timeout so a dead link fails the sync
 * instead of hanging it.
 */
class AndroidTransport private constructor(
    private val gatt: BluetoothGatt,
    private val bridge: Bridge,
    private val log: (String) -> Unit,
) : BleTransport {

    /** Incoming notifications, one unbounded buffered channel per characteristic. */
    override val notifications: Map<CharId, Channel<ByteArray>> =
        CharId.entries.associateWith { Channel(Channel.UNLIMITED) }

    /** The four characteristics, resolved from service `0xfe95` after discovery. */
    private var chars: Map<CharId, BluetoothGattCharacteristic>? = null

    /** Per-characteristic in-flight write completion (local, TX-queue). */
    private val writeWait = HashMap<CharId, CompletableDeferred<Int>>()

    /** The one in-flight CCCD write ([enableNotifications] is never concurrent). */
    private var cccdWait: CompletableDeferred<Int>? = null

    /** Completes when the link is up (or fails with the connect error). */
    private val connected = CompletableDeferred<Unit>()

    /** Service-discovery and MTU completions (single-flight each). */
    private var servicesWait: CompletableDeferred<Int>? = null
    private var mtuWait: CompletableDeferred<Int>? = null

    private var closed = false

    companion object {
        /** GATT service `0xfe95` (Mi service), full UUID string. */
        const val SERVICE_UUID = "0000fe95-0000-1000-8000-00805f9b34fb"

        /**
         * The four protocol characteristics, looked up **by UUID string**
         * in [SERVICE_UUID] (brief-pinned): LOGIN `…0010`, AUTH `…0019`,
         * CMD `…001a`, PROP `…001b`.
         */
        val CHAR_UUIDS: Map<CharId, String> = mapOf(
            CharId.LOGIN to "00000010-0000-1000-8000-00805f9b34fb",
            CharId.AUTH to "00000019-0000-1000-8000-00805f9b34fb",
            CharId.CMD to "0000001a-0000-1000-8000-00805f9b34fb",
            CharId.PROP to "0000001b-0000-1000-8000-00805f9b34fb",
        )

        /** Client Characteristic Configuration Descriptor (`0x2902`). */
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private val SERVICE = UUID.fromString(SERVICE_UUID)
        private val BY_UUID: Map<String, CharId> =
            CHAR_UUIDS.entries.associate { (id, uuid) -> uuid to id }

        /** Requested MTU; best-effort — the `a4` probe stays authoritative. */
        private const val REQUESTED_MTU = 247

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val MTU_TIMEOUT_MS = 3_000L
        private const val SERVICES_TIMEOUT_MS = 10_000L

        /**
         * Local write/CCCD completion timeout: equal to the framing layer's
         * [com.xiaomiscalesync.proto.Framing.ACK_TIMEOUT_MS] so a stack that
         * never completes a write surfaces as a protocol timeout, not a
         * hang, and never outlives the ack wait it feeds.
         */
        private const val GATT_OP_TIMEOUT_MS = 4_000L

        /**
         * Opens the link and returns a ready [AndroidTransport]:
         * connect → best-effort MTU 247 → discover services → resolve the
         * four `0xfe95` characteristics by UUID. See the class KDoc.
         *
         * The callback must exist *before* `connectGatt`, so the [Bridge]
         * is created first and attached once the gatt exists — any state
         * change that raced the attach is drained by [Bridge.attach] and
         * replayed into the transport, so no connect/disconnect event can
         * be lost in that window.
         */
        suspend fun connect(
            device: BluetoothDevice,
            context: Context,
            log: (String) -> Unit = {},
        ): AndroidTransport {
            val bridge = Bridge()
            // SDK 37 deprecates these overloads for
            // connectGatt(BluetoothGattConnectionSettings, Executor, …), but
            // minSdk 26 must keep the classic entry point — it remains fully
            // functional (silenced deprecation, not a behavior problem).
            @Suppress("DEPRECATION")
            val gatt = try {
                // 5-arg overload (API 26+): the 4-arg one is deprecated.
                device.connectGatt(
                    context, false, bridge.callback, BluetoothDevice.TRANSPORT_LE, 0,
                )
            } catch (e: SecurityException) {
                throw ProtocolError("BLUETOOTH_CONNECT permission missing (${e.message})")
            } ?: throw ProtocolError("connectGatt returned no GATT handle")
            val transport = AndroidTransport(gatt, bridge, log)
            val raced = bridge.attach(transport)
            try {
                transport.awaitConnected(raced)
                transport.requestMtuBestEffort()
                transport.discoverAndResolve()
            } catch (c: CancellationException) {
                transport.abort("connect cancelled")
                throw c
            } catch (t: Throwable) {
                transport.abort(t.message ?: t::class.java.simpleName)
                throw t
            }
            return transport
        }
    }

    // ---------------------------------------------------------- lifecycle

    /** Replays state changes that arrived before [bridge] was attached, then waits for CONNECTED. */
    private suspend fun awaitConnected(raced: List<Pair<Int, Int>>) {
        raced.forEach { (status, newState) -> handleConnectionState(status, newState) }
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { connected.await() }
        } catch (_: TimeoutCancellationException) {
            throw ProtocolError("scale did not connect within ${CONNECT_TIMEOUT_MS}ms")
        }
    }

    /**
     * Best-effort MTU 247: request, await the report briefly, log either
     * way, never fail. The `a4` probe derives the real max value from the
     * scale's own answer, so a stack that ignores the request only costs
     * throughput, not correctness.
     */
    private suspend fun requestMtuBestEffort() {
        val done = CompletableDeferred<Int>()
        mtuWait = done
        try {
            val accepted = try {
                gatt.requestMtu(REQUESTED_MTU)
            } catch (e: SecurityException) {
                log("MTU request denied: ${e.message}")
                false
            }
            if (!accepted) {
                log("MTU $REQUESTED_MTU request refused; staying at 23 (a4 probe is authoritative)")
                return
            }
            val mtu = try {
                withTimeout(MTU_TIMEOUT_MS) { done.await() }
            } catch (_: TimeoutCancellationException) {
                log("MTU change not reported within ${MTU_TIMEOUT_MS}ms; proceeding (a4 probe is authoritative)")
                return
            } catch (e: ProtocolError) {
                log("MTU change failed: ${e.message}")
                return
            }
            log("MTU negotiated: $mtu (requested $REQUESTED_MTU; a4 probe is authoritative)")
        } finally {
            mtuWait = null
        }
    }

    /** Discovers services and resolves the four protocol characteristics by UUID. */
    private suspend fun discoverAndResolve() {
        val done = CompletableDeferred<Int>()
        servicesWait = done
        val accepted = try {
            gatt.discoverServices()
        } catch (e: SecurityException) {
            throw ProtocolError("BLUETOOTH_CONNECT permission missing (${e.message})")
        }
        if (!accepted) throw ProtocolError("discoverServices refused")
        val status = try {
            withTimeout(SERVICES_TIMEOUT_MS) { done.await() }
        } catch (_: TimeoutCancellationException) {
            throw ProtocolError("service discovery timed out after ${SERVICES_TIMEOUT_MS}ms")
        } finally {
            servicesWait = null
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            throw ProtocolError("service discovery failed (status $status)")
        }
        chars = discoverChars() // throws with the missing UUID named
        log("GATT ready: service 0xfe95, ${CHAR_UUIDS.size} characteristics resolved by UUID")
    }

    /** Looks the four characteristics up by UUID string in service `0xfe95`. */
    private fun discoverChars(): Map<CharId, BluetoothGattCharacteristic> {
        val service = gatt.getService(SERVICE)
            ?: throw ProtocolError("service 0xfe95 not found on the scale")
        return CHAR_UUIDS.entries.associate { (id, uuid) ->
            val char = service.getCharacteristic(UUID.fromString(uuid))
                ?: throw ProtocolError("characteristic $uuid ($id) not found in service 0xfe95")
            id to char
        }
    }

    /** The resolved characteristics; [ProtocolError] before discovery. */
    private fun requireChars(): Map<CharId, BluetoothGattCharacteristic> =
        chars ?: throw ProtocolError("characteristics not discovered (link not ready)")

    // ------------------------------------------------------- BleTransport

    /**
     * Writes [bytes] to characteristic [to] with **write-without-response**
     * and suspends until the stack reports the local completion (its TX
     * queue has room again) — the flow control that lets the framing layer
     * emit parcels back-to-back safely. [ProtocolError] on a refused or
     * never-completing write; a concurrent second write to the same
     * characteristic is rejected rather than allowed to mis-pair its
     * completion.
     */
    override suspend fun write(to: CharId, bytes: ByteArray) {
        val char = requireChars().getValue(to)
        val done = CompletableDeferred<Int>()
        synchronized(writeWait) {
            if (writeWait.containsKey(to)) {
                throw ProtocolError("concurrent write to $to while one is in flight")
            }
            writeWait[to] = done
        }
        try {
            val status = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                } else {
                    @Suppress("DEPRECATION")
                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    char.value = bytes
                    @Suppress("DEPRECATION")
                    if (gatt.writeCharacteristic(char)) BluetoothGatt.GATT_SUCCESS else -1
                }
            } catch (e: SecurityException) {
                throw ProtocolError("BLUETOOTH_CONNECT permission missing (${e.message})")
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                throw ProtocolError("write to $to refused (status $status)")
            }
            val result = try {
                withTimeout(GATT_OP_TIMEOUT_MS) { done.await() }
            } catch (_: TimeoutCancellationException) {
                throw ProtocolError("write to $to not completed within ${GATT_OP_TIMEOUT_MS}ms")
            }
            if (result != BluetoothGatt.GATT_SUCCESS) {
                throw ProtocolError("write to $to failed (status $result)")
            }
        } finally {
            synchronized(writeWait) { writeWait.remove(to) }
        }
    }

    /**
     * The real CCCD setup: `setCharacteristicNotification` + the `0x2902`
     * "enable notifications" descriptor write, awaited. This is the seam
     * whose default is a no-op for the loopback fake — on hardware the
     * scale's required enable order (AUTH/CMD/PROP → probe → LOGIN, driven
     * by [com.xiaomiscalesync.proto.ScaleSession.setup]) runs through here.
     */
    override suspend fun enableNotifications(char: CharId) {
        val characteristic = requireChars().getValue(char)
        val cccd = characteristic.getDescriptor(CCCD_UUID)
            ?: throw ProtocolError("characteristic $char has no CCCD (0x2902)")
        val notificationsEnabled = try {
            gatt.setCharacteristicNotification(characteristic, true)
        } catch (e: SecurityException) {
            throw ProtocolError("BLUETOOTH_CONNECT permission missing (${e.message})")
        }
        if (!notificationsEnabled) {
            throw ProtocolError("setCharacteristicNotification failed for $char")
        }
        val done = CompletableDeferred<Int>()
        synchronized(this) {
            if (cccdWait != null) throw ProtocolError("concurrent CCCD write while enabling $char")
            cccdWait = done
        }
        try {
            val status = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    if (gatt.writeDescriptor(cccd)) BluetoothGatt.GATT_SUCCESS else -1
                }
            } catch (e: SecurityException) {
                throw ProtocolError("BLUETOOTH_CONNECT permission missing (${e.message})")
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                throw ProtocolError("CCCD write for $char refused (status $status)")
            }
            val result = try {
                withTimeout(GATT_OP_TIMEOUT_MS) { done.await() }
            } catch (_: TimeoutCancellationException) {
                throw ProtocolError("CCCD write for $char not completed within ${GATT_OP_TIMEOUT_MS}ms")
            }
            if (result != BluetoothGatt.GATT_SUCCESS) {
                throw ProtocolError("CCCD write for $char failed (status $result)")
            }
        } finally {
            synchronized(this) { cccdWait = null }
        }
    }

    /**
     * Tears the link down in both directions: disconnect + `gatt.close()`
     * (mandatory — an unclosed gatt leaks a client slot), pending
     * operations failed, notification channels closed. Idempotent, like the
     * loopback's [close].
     */
    override suspend fun close() {
        closeNow()
    }

    /** The [close] body, callable from the factory's non-suspend failure path. */
    private fun closeNow() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        try {
            gatt.disconnect()
        } catch (_: SecurityException) {
            // Already torn down or permission revoked mid-close: closing still proceeds.
        }
        try {
            gatt.close()
        } catch (_: SecurityException) {
            // Permission may be revoked while the connection is being torn down.
        }
        failAll(ProtocolError("link closed"))
        log("GATT link closed")
    }

    // ------------------------------------------------------ GATT callbacks

    /** Routed from [Bridge]; runs on a framework callback thread. */
    internal fun handleConnectionState(status: Int, newState: Int) {
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                log("GATT connected (status $status)")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    connected.complete(Unit)
                } else {
                    connected.completeExceptionally(ProtocolError("GATT connect failed (status $status)"))
                }
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                log("GATT disconnected (status $status)")
                val error = ProtocolError("GATT link lost (status $status)")
                connected.completeExceptionally(error)
                failAll(error)
            }
        }
    }

    internal fun handleServicesDiscovered(status: Int) {
        servicesWait?.complete(status)
    }

    internal fun handleMtuChanged(mtu: Int) {
        log("MTU reported: $mtu")
        mtuWait?.complete(mtu)
    }

    internal fun handleWriteComplete(charUuid: UUID, status: Int) {
        val id = BY_UUID[charUuid.toString()] ?: return
        synchronized(writeWait) { writeWait[id] }?.complete(status)
    }

    internal fun handleDescriptorWrite(descriptorUuid: UUID, status: Int) {
        if (descriptorUuid != CCCD_UUID) return
        synchronized(this) { cccdWait }?.complete(status)
    }

    internal fun handleNotification(charUuid: UUID, value: ByteArray) {
        val id = BY_UUID[charUuid.toString()] ?: return
        notifications[id]?.trySend(value.copyOf())
    }

    // ------------------------------------------------------------- plumbing

    /** Fails every in-flight wait and closes the notification channels. */
    private fun failAll(error: Throwable) {
        val waits = synchronized(writeWait) { writeWait.values.toList() }
        waits.forEach { it.completeExceptionally(error) }
        synchronized(this) { cccdWait }?.completeExceptionally(error)
        servicesWait?.completeExceptionally(error)
        mtuWait?.completeExceptionally(error)
        connected.completeExceptionally(error)
        notifications.values.forEach { it.close() }
    }

    /** Best-effort close used by the factory's failure path. */
    private fun abort(reason: String) {
        try {
            closeNow()
        } catch (_: Throwable) {
            // Already closing; the original reason is what the caller sees.
        }
        log("GATT aborted: $reason")
    }
}

/**
 * The callback bridge [AndroidTransport.connect] needs *before* the gatt
 * exists: it owns the [BluetoothGattCallback] handed to `connectGatt` and
 * forwards every event to the attached transport. Attachment and the
 * connection-state latch share one lock, so a state change that arrives
 * while the transport is being attached is either routed directly or
 * drained by [attach] — never lost (a lost CONNECTED would turn into a
 * spurious 15 s connect timeout).
 */
private class Bridge {

    private val lock = Any()

    private var transport: AndroidTransport? = null

    /** Connection-state changes that arrived before [attach] drained them. */
    private val early = ArrayList<Pair<Int, Int>>()

    val callback: BluetoothGattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            synchronized(lock) {
                val target = transport
                if (target != null) {
                    target.handleConnectionState(status, newState)
                } else {
                    early.add(status to newState)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            target()?.handleServicesDiscovered(status)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) target()?.handleMtuChanged(mtu)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            target()?.handleWriteComplete(characteristic.uuid, status)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            target()?.handleDescriptorWrite(descriptor.uuid, status)
        }

        // API 33+ delivers the value-carrying overload.
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            target()?.handleNotification(characteristic.uuid, value)
        }

        // Pre-33 devices deliver only this one; the value rides the characteristic.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            target()?.handleNotification(characteristic.uuid, characteristic.value ?: ByteArray(0))
        }
    }

    private fun target(): AndroidTransport? = synchronized(lock) { transport }

    /**
     * Attaches the transport under the lock and returns the state changes
     * that raced ahead of it; the caller replays them into the transport.
     */
    fun attach(transport: AndroidTransport): List<Pair<Int, Int>> = synchronized(lock) {
        this.transport = transport
        early.toList().also { early.clear() }
    }
}
