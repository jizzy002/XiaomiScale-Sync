package com.xiaomiscalesync.ui.profile

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xiaomiscalesync.AppGraph
import com.xiaomiscalesync.data.Settings
import com.xiaomiscalesync.data.ThemeMode
import com.xiaomiscalesync.garmin.GarminAuthError
import com.xiaomiscalesync.ui.CodePrompt
import com.xiaomiscalesync.ui.onboarding.CaptchaDialog
import com.xiaomiscalesync.ui.onboarding.CodeDialog
import com.xiaomiscalesync.ui.onboarding.GarminSso
import com.xiaomiscalesync.ui.onboarding.MiCloudKeyExtractor
import com.xiaomiscalesync.ui.theme.Pill
import com.xiaomiscalesync.ui.theme.Tone
import com.xiaomiscalesync.work.PushWorker
import com.xiaomiscalesync.xiaomi.MiCloudClient
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * The Profile tab (plan Task 17 "Settings", owner request 2026-10-13):
 * scale keys (status, tap-to-reveal + copy, re-extract via Mi sign-in),
 * Garmin account (status, re-login), the scale profile editor, debug
 * tools and about. Logic lives in the JVM-tested [ProfileViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onOpenDebug: () -> Unit) {
    val context = LocalContext.current
    val graph = AppGraph.of(context)
    val miPrompt = remember { CodePrompt() }
    val garminPrompt = remember { CodePrompt() }
    val viewModel = remember(graph) {
        val http = OkHttpClient()
        ProfileViewModel(
            settingsSource = graph.settings,
            settings = graph.settings,
            keys = graph.secrets,
            vault = graph.secrets,
            mi = MiCloudKeyExtractor(MiCloudClient(http)),
            garmin = GarminSso(http, graph.secrets),
            garminSignedIn = { graph.secrets.load() != null },
            repo = graph.repo,
            enqueuePush = { PushWorker.enqueue(context.applicationContext) },
        )
    }
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) { viewModel.load() }

    var miDialog by remember { mutableStateOf(false) }
    var garminDialog by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Profile") }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            // ------------------------------------------------ scale keys
            Section("Scale keys") {
                InfoRow("Mi account", state.miAccount ?: "—")
                InfoRow("MAC", state.macMasked ?: "missing", mono = true)
                KeyRow("TOKEN", state.tokenOk)
                KeyRow("BLE KEY", state.bleKeyOk)
                val revealed = state.revealed
                if (revealed != null) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    SecretRow(context, "MAC", revealed.mac)
                    SecretRow(context, "TOKEN", revealed.token)
                    SecretRow(context, "BLE KEY", revealed.bleKey)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { if (revealed == null) viewModel.reveal() else viewModel.hide() }) {
                        Text(if (revealed == null) "Reveal keys" else "Hide keys")
                    }
                    FilledTonalButton(enabled = !state.busy, onClick = { miDialog = true }) {
                        Text("Re-extract")
                    }
                }
            }

            // ---------------------------------------------------- garmin
            Section("Garmin Connect") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.garminEmail ?: if (state.garminSignedIn) "Signed in" else "Not signed in",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    when {
                        state.authExpired -> Pill("Sign-in expired", Tone.ERROR, compact = true)
                        state.garminSignedIn -> Pill("Connected", Tone.PRIMARY, compact = true)
                        else -> Pill("Not connected", Tone.NEUTRAL, compact = true)
                    }
                }
                FilledTonalButton(enabled = !state.busy, onClick = { garminDialog = true }) {
                    Text(if (state.garminSignedIn && !state.authExpired) "Sign in again" else "Sign in")
                }
            }

            // --------------------------------------------------- profile
            Section("Your profile") {
                Text(
                    "Sent to the scale on every sync (units: kg).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.heightCm,
                        onValueChange = viewModel::setHeightCm,
                        label = { Text("Height (cm)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = state.age,
                        onValueChange = viewModel::setAge,
                        label = { Text("Age") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.sex == 1, onClick = { viewModel.setSex(1) }, label = { Text("Male") })
                    FilterChip(selected = state.sex == 2, onClick = { viewModel.setSex(2) }, label = { Text("Female") })
                }
                // Member ID is advanced-only (owner ruling 2026-10-13): a wrong
                // value silently routes every weigh-in to Needs review.
                TextButton(onClick = { advancedOpen = !advancedOpen }) {
                    Text(if (advancedOpen) "Advanced ▾" else "Advanced ▸")
                }
                if (advancedOpen) {
                    Text(
                        "Leave this at 1. Only change it if you're moving from an older setup that used " +
                            "a different XIAOMI_PROFILE_MEMBER_ID. A wrong value sends your weigh-ins to " +
                            "Needs review instead of Garmin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = state.mid,
                            onValueChange = viewModel::setMid,
                            label = { Text("Member ID") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.setMid(Settings.DEFAULT_MID) }) { Text("Reset to 1") }
                    }
                }
                FilledTonalButton(onClick = { scope.launch { viewModel.saveProfile() } }) { Text("Save profile") }
            }

            // ------------------------------------------------ appearance
            Section("Appearance") {
                val settings by graph.settings.settings.collectAsState(initial = null)
                val current = settings?.themeMode ?: ThemeMode.SYSTEM
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = mode == current,
                            onClick = { scope.launch { graph.settings.setThemeMode(mode) } },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        ) { Text(mode.label) }
                    }
                }
            }

            // ----------------------------------------------------- other
            Section("About") {
                InfoRow("Version", versionName(context))
                InfoRow("Licence", "MIT")
                Text(
                    "Based on the reverse-engineered S200 protocol from faraga1/xiaomi-scale-s200-ble (MIT). " +
                        "All keys and tokens stay on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenDebug) { Text("Debug tools") }
            }
        }
    }

    if (miDialog) {
        CredentialsDialog(
            title = "Re-extract scale keys",
            userLabel = "Mi account email / phone",
            initialUser = state.miAccount ?: "",
            onDismiss = { miDialog = false },
            onSubmit = { user, pass ->
                miDialog = false
                scope.launch {
                    viewModel.reExtract(
                        user,
                        pass,
                        onCaptcha = { image, url -> miPrompt.askCaptcha(image, url) },
                        onSecondFactor = { prompt -> miPrompt.ask(prompt) },
                    )
                }
            },
        )
    }
    if (garminDialog) {
        CredentialsDialog(
            title = "Garmin Connect sign-in",
            userLabel = "Garmin email",
            initialUser = state.garminEmail ?: "",
            onDismiss = { garminDialog = false },
            onSubmit = { user, pass ->
                garminDialog = false
                scope.launch {
                    viewModel.garminSignIn(user, pass) { method ->
                        garminPrompt.ask("Garmin MFA via $method — enter the emailed code")
                            ?: throw GarminAuthError("Garmin MFA dialog cancelled")
                    }
                }
            },
        )
    }
    miPrompt.prompt?.let { text -> CodeDialog(text) { code -> miPrompt.submit(code) } }
    miPrompt.captcha?.let { challenge -> CaptchaDialog(challenge.image) { code -> miPrompt.submit(code) } }
    garminPrompt.prompt?.let { text -> CodeDialog(text) { code -> garminPrompt.submit(code) } }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else null,
        )
    }
}

@Composable
private fun KeyRow(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Pill(if (ok) "Stored" else "Missing", if (ok) Tone.PRIMARY else Tone.ERROR, compact = true)
    }
}

/** A revealed value with a copy button (clipboard flagged sensitive on 13+). */
@Composable
private fun SecretRow(context: Context, label: String, value: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value ?: "missing", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
        if (value != null) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { copySensitive(context, label, value) }) { Text("Copy") }
        }
    }
}

private fun copySensitive(context: Context, label: String, value: String) {
    val clip = ClipData.newPlainText(label, value)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // Keeps the value out of the clipboard preview/overlay.
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/** Username + password; the password lives only in this dialog's memory. */
@Composable
private fun CredentialsDialog(
    title: String,
    userLabel: String,
    initialUser: String,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    var user by remember { mutableStateOf(initialUser) }
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(user, { user = it }, label = { Text(userLabel) }, singleLine = true)
                OutlinedTextField(
                    pass,
                    { pass = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = user.isNotBlank() && pass.isNotBlank(), onClick = { onSubmit(user, pass) }) {
                Text("Sign in")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun versionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull() ?: "?"
