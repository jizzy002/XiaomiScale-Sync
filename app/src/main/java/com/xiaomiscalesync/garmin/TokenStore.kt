package com.xiaomiscalesync.garmin

/**
 * Persisted Garmin Connect OAuth tokens.
 *
 * Implementations must survive process restarts (encrypted storage is wired in
 * a later task); the client itself stays storage-agnostic behind this interface.
 */
interface TokenStore {
    /** Returns the stored tokens, or null when the user has never signed in. */
    suspend fun load(): GarminTokens?

    /** Persists tokens, replacing any previously stored pair. */
    suspend fun save(tokens: GarminTokens)
}

/**
 * OAuth tokens from the Garmin DI token exchange.
 *
 * [expiresAtMillis] is epoch millis in UTC; null when Garmin returned no
 * expiry. [clientId] is the DI client the access token was minted for and is
 * replayed as the `Basic base64(clientId:)` credential on token-endpoint
 * calls (refresh, and the ticket exchange's next attempt after a restart).
 */
data class GarminTokens(
    val accessToken: String,
    val refreshToken: String?,
    val clientId: String,
    val expiresAtMillis: Long?,
) {
    /** Never leak tokens into logs or crash reports. */
    override fun toString(): String =
        "GarminTokens(accessToken=REDACTED, refreshToken=REDACTED, " +
            "clientId=$clientId, expiresAtMillis=$expiresAtMillis)"
}
