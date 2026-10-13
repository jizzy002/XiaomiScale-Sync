package com.xiaomiscalesync.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred

/**
 * One suspendable prompt for the login flows (Mi captcha / Mi email 2FA /
 * Garmin MFA): the login call suspends in [ask] or [askCaptcha] until the
 * dialog submits or dismisses. State is snapshot-backed so the dialog
 * reacts instantly; the deferred hand-off is safe across the login thread
 * (OkHttp IO) and the main thread.
 */
class CodePrompt {
    /** A captcha challenge handed to [askCaptcha]: raw image bytes + resolved URL. */
    class Captcha(val image: ByteArray, val url: String)

    var prompt by mutableStateOf<String?>(null)
        private set

    var captcha by mutableStateOf<Captcha?>(null)
        private set

    @Volatile
    private var pending: CompletableDeferred<String?>? = null

    /** Suspends until the dialog answers; null = cancelled. */
    suspend fun ask(text: String): String? = askWith { prompt = text }

    /**
     * [com.xiaomiscalesync.xiaomi.MiCloudClient.login]'s `onCaptcha` seam: shows [image] in a dialog
     * and suspends until the typed code is submitted; null = cancelled or
     * dismissed (→ [com.xiaomiscalesync.xiaomi.MiLoginBlocked]).
     */
    suspend fun askCaptcha(image: ByteArray, url: String): String? =
        askWith { captcha = Captcha(image, url) }

    private suspend fun askWith(open: () -> Unit): String? {
        check(pending == null) { "a code prompt is already open" }
        val deferred = CompletableDeferred<String?>()
        pending = deferred
        open()
        return try {
            deferred.await()
        } finally {
            prompt = null
            captcha = null
            pending = null
        }
    }

    /** Called from the dialog (main thread). */
    fun submit(code: String?) {
        prompt = null
        captcha = null
        val deferred = pending
        pending = null
        deferred?.complete(code)
    }
}
