package com.xiaomiscalesync.proto

/**
 * A wire-protocol violation or an ack timeout — e.g. [Framing]'s deadlock
 * guard firing when a ready/received ack never arrives within
 * [Framing.ACK_TIMEOUT_MS]. Carries a message so callers (and tests) can
 * match on the reason, like the reference client's `ProtocolError`.
 */
class ProtocolError(message: String) : Exception(message)
