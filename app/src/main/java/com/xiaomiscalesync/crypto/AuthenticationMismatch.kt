package com.xiaomiscalesync.crypto

/**
 * Thrown when AES-CCM authentication fails: wrong key, wrong nonce, wrong
 * AAD, tampered ciphertext, or a tampered/short tag. Plaintext is never
 * returned on this path.
 */
class AuthenticationMismatch(
    message: String = "CCM authentication failed",
    cause: Throwable? = null,
) : Exception(message, cause)
