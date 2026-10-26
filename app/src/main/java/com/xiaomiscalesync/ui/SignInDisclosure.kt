package com.xiaomiscalesync.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

enum class SignInProvider { XIAOMI, GARMIN }

/** A short sign-in disclosure with provider-specific storage and data-flow details. */
@Composable
fun SignInDisclosure(provider: SignInProvider, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(provider) { mutableStateOf(false) }
    val summary = when (provider) {
        SignInProvider.XIAOMI ->
            "Used to find your scale and retrieve its connection and Bluetooth keys. " +
                "Your password is sent directly to Xiaomi and is not written to app storage. " +
                "Weigh-ins are read over Bluetooth; this app does not send them to Xiaomi."
        SignInProvider.GARMIN ->
            "Used to authorize uploads and check Garmin weight entries for duplicates. " +
                "Your password is sent directly to Garmin and is not written to app storage."
    }
    val details = when (provider) {
        SignInProvider.XIAOMI ->
            "The app saves your Xiaomi email or phone in settings to prefill future sign-ins. " +
                "It reads your Xiaomi device list to find the scale, then stores its MAC, " +
            "connection TOKEN and BLE KEY encrypted with Android Keystore. Xiaomi session " +
                "cookies are used temporarily in memory and are not saved to app storage. " +
                "Any captcha or verification code is sent to Xiaomi. " +
                "You can enter the keys manually instead."
        SignInProvider.GARMIN ->
            "The app saves your Garmin email in settings for display. Encrypted access and " +
                "refresh tokens keep syncing after sign-in. It checks Garmin weight history " +
                "before uploads and sends eligible weigh-ins with their recorded weight and time. " +
                "Any MFA code is sent to Garmin."
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = { expanded = !expanded },
        ) {
            Text(if (expanded) "Hide sign-in details" else "How sign-in works")
        }
        if (expanded) {
            Text(
                details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
