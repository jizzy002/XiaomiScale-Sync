package com.xiaomiscalesync.ui.onboarding

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaomiscalesync.garmin.GarminAuthError
import com.xiaomiscalesync.ui.debug.CodePrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The first-launch onboarding wizard (plan Task 14, spec "Onboarding
 * wizard"): Mi sign-in → profile → history question → Garmin sign-in →
 * done. Every decision lives in [OnboardingViewModel]; this file is the
 * Material 3 rendering plus the two suspend-prompt dialogs.
 *
 * Dialogs reuse the spike's proven [CodePrompt] hand-off (Mi 2FA/captcha
 * and Garmin MFA all suspend the login call until the dialog answers).
 * The captcha dialog is deliberately *not* the spike's debug version:
 * the image renders at full dialog width with a 280 dp minimum (both
 * axes), fixing the owner's "too small" complaint.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val miPrompt = remember { CodePrompt() }
    val garminPrompt = remember { CodePrompt() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("Set up Scale Sync")
                            Text(
                                "Step ${state.step.ordinal + 1} of ${OnboardingStep.entries.size} · " +
                                    STEP_TITLES.getValue(state.step),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { (state.step.ordinal + 1f) / OnboardingStep.entries.size },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            when (state.step) {
                OnboardingStep.MI_KEYS ->
                    MiKeysStep(
                        state,
                        viewModel,
                        scope,
                        onCaptcha = { image, url -> miPrompt.askCaptcha(image, url) },
                        onSecondFactor = { prompt -> miPrompt.ask(prompt) },
                    )

                OnboardingStep.PROFILE -> ProfileStep(state, viewModel)
                OnboardingStep.HISTORY -> HistoryStep(state, viewModel)

                OnboardingStep.GARMIN ->
                    GarminStep(state, viewModel, scope) { method ->
                        garminPrompt.ask("Garmin MFA via $method — enter the emailed code")
                            ?: throw GarminAuthError("Garmin MFA dialog cancelled")
                    }

                OnboardingStep.DONE -> DoneStep(state, viewModel, scope)
            }
        }
    }

    // Second-factor / captcha prompts (all four suspend the login call).
    miPrompt.prompt?.let { text -> CodeDialog(text) { code -> miPrompt.submit(code) } }
    miPrompt.captcha?.let { challenge ->
        CaptchaDialog(challenge.image) { code -> miPrompt.submit(code) }
    }
    garminPrompt.prompt?.let { text -> CodeDialog(text) { code -> garminPrompt.submit(code) } }
}

/** Short names shown under the wizard's title bar. */
private val STEP_TITLES = mapOf(
    OnboardingStep.MI_KEYS to "Scale keys",
    OnboardingStep.PROFILE to "Profile",
    OnboardingStep.HISTORY to "History",
    OnboardingStep.GARMIN to "Garmin",
    OnboardingStep.DONE to "Done",
)

// -------------------------------------------------------------- step 1

/**
 * Step 1 — Mi Account sign-in with the extracted MAC/TOKEN/BLE KEY shown
 * as three green checks, or the "I already have the keys" manual-paste
 * path. [onCaptcha] answers the in-app captcha dialog; 2FA and captcha
 * both suspend inside [OnboardingViewModel.extractKeys].
 */
@Composable
private fun MiKeysStep(
    state: OnboardingState,
    viewModel: OnboardingViewModel,
    scope: CoroutineScope,
    onCaptcha: suspend (ByteArray, String) -> String?,
    onSecondFactor: suspend (String) -> String?,
) {
    var user by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val enabled = !state.busy

    if (!state.manualMode) {
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Mi account email / phone") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Mi account password") },
            singleLine = true,
            enabled = enabled,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                scope.launch {
                    viewModel.extractKeys(
                        user,
                        pass,
                        onCaptcha = onCaptcha,
                        onSecondFactor = onSecondFactor,
                    )
                }
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Extract keys (Mi sign-in)")
        }
        TextButton(onClick = { viewModel.setManualMode(true) }, enabled = enabled) {
            Text("I already have the keys")
        }
    } else {
        OutlinedTextField(
            value = state.manualMac,
            onValueChange = viewModel::setManualMac,
            label = { Text("MAC (AA:BB:CC:DD:EE:FF)") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.manualToken,
            onValueChange = viewModel::setManualToken,
            label = { Text("TOKEN (24 hex chars)") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.manualBleKey,
            onValueChange = viewModel::setManualBleKey,
            label = { Text("BLE KEY (32 hex chars)") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { viewModel.submitManualKeys() },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Use these keys")
        }
        TextButton(onClick = { viewModel.setManualMode(false) }, enabled = enabled) {
            Text("Sign in with my Mi account instead")
        }
    }

    state.foundScale?.let { found ->
        Text(
            "Found: $found",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    KeyStatusRow(state)

    val allKeys = state.macOk && state.tokenOk && state.bleKeyOk
    if (!allKeys) {
        Text(
            "All three keys are needed to continue — extract them or paste them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(
        onClick = { viewModel.nextFromKeys() },
        enabled = enabled && allKeys,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Next")
    }
}

// -------------------------------------------------------------- step 2

/** Step 2 — profile: height cm, age, sex, unit (fixed kg v1), Member ID. */
@Composable
private fun ProfileStep(state: OnboardingState, viewModel: OnboardingViewModel) {
    OutlinedTextField(
        value = state.heightCm,
        onValueChange = viewModel::setHeightCm,
        label = { Text("Height (cm)") },
        singleLine = true,
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.age,
        onValueChange = viewModel::setAge,
        label = { Text("Age") },
        singleLine = true,
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    Text("Sex", style = MaterialTheme.typography.bodyLarge)
    SEX_OPTIONS.forEach { (value, label) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = state.sex == value,
                onClick = { viewModel.setSex(value) },
                enabled = !state.busy,
            )
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Unit", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(8.dp))
        Text("kg", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
    Text(
        "Fixed to kilograms in v1.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = state.mid,
        onValueChange = viewModel::setMid,
        label = { Text("Member ID") },
        singleLine = true,
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = {
            Text("Copy XIAOMI_PROFILE_MEMBER_ID from an existing setup. Default 1.")
        },
        modifier = Modifier.fillMaxWidth(),
    )

    StepFooter(
        backEnabled = !state.busy,
        back = viewModel::back,
        nextEnabled = !state.busy,
        next = viewModel::nextFromProfile,
    )
}

// -------------------------------------------------------------- step 3

/**
 * Step 3 — "Did a previous setup already sync your history to Garmin?"
 * (default off). Checked → `backfillCutoff = now` so pre-existing records
 * archive-only as SKIPPED_EXISTING; unchecked → `backfillCutoff = null`
 * (fresh start, everything pushable).
 */
@Composable
private fun HistoryStep(state: OnboardingState, viewModel: OnboardingViewModel) {
    Text(
        "Did a previous setup already sync your history to Garmin?",
        style = MaterialTheme.typography.titleMedium,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = state.historyAlreadySynced,
            onCheckedChange = viewModel::setHistoryAlreadySynced,
            enabled = !state.busy,
        )
        Text("Yes — my history is already in Garmin", style = MaterialTheme.typography.bodyMedium)
    }
    Text(
        if (state.historyAlreadySynced) {
            "Older records will be kept in the app archive but never pushed — Garmin already " +
                "has them (they show as \"Skipped (already in Garmin)\")."
        } else {
            "Everything captured will be pushable, including older records — a fresh start."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    StepFooter(
        backEnabled = !state.busy,
        back = viewModel::back,
        nextEnabled = !state.busy,
        next = viewModel::nextFromHistory,
    )
}

// -------------------------------------------------------------- step 4

/**
 * Step 4 — Garmin sign-in; [onMfa] suspends the login until the emailed
 * code dialog answers (the client invokes it when Garmin demands MFA).
 */
@Composable
private fun GarminStep(
    state: OnboardingState,
    viewModel: OnboardingViewModel,
    scope: CoroutineScope,
    onMfa: suspend (method: String) -> String?,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val enabled = !state.busy

    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("Garmin email") },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = pass,
        onValueChange = { pass = it },
        label = { Text("Garmin password") },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = {
            scope.launch {
                viewModel.garminSignIn(email, pass) { method ->
                    onMfa(method) ?: throw GarminAuthError("Garmin MFA dialog cancelled")
                }
            }
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Sign in to Garmin")
    }
    if (state.garminSignedIn) {
        Text(
            "✔ Signed in as ${state.garminUser}",
            color = Color(0xFF1B8E3D),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    StepFooter(
        backEnabled = enabled,
        back = viewModel::back,
        nextEnabled = enabled && state.garminSignedIn,
        next = viewModel::nextFromGarmin,
    )
}

// -------------------------------------------------------------- step 5

/** Step 5 — summary; Finish writes `mid`, the backfill cutoff, `onboarded = true`. */
@Composable
private fun DoneStep(
    state: OnboardingState,
    viewModel: OnboardingViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    Text("All set", style = MaterialTheme.typography.titleMedium)
    Text("✔ Scale keys stored (MAC, TOKEN, BLE KEY)")
    Text("✔ Profile: ${state.heightCm} cm, age ${state.age}, Member ID ${state.mid}")
    Text(
        if (state.historyAlreadySynced) {
            "✔ Existing history stays archive-only (skipped — already in Garmin)"
        } else {
            "✔ Fresh start — everything captured will be pushable"
        },
    )
    Text("✔ Garmin connected as ${state.garminUser}")
    Button(
        onClick = { scope.launch { viewModel.complete() } },
        enabled = !state.busy && !state.completed,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Finish")
    }
    TextButton(onClick = { viewModel.back() }, enabled = !state.busy) {
        Text("Back")
    }
}

// ---------------------------------------------------------------- pieces

private val SEX_OPTIONS = listOf(0 to "Not specified", 1 to "Male", 2 to "Female")

@Composable
private fun StepFooter(
    backEnabled: Boolean,
    back: () -> Unit,
    nextEnabled: Boolean,
    next: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = back, enabled = backEnabled) {
            Text("Back")
        }
        Button(onClick = next, enabled = nextEnabled, modifier = Modifier.weight(1f)) {
            Text("Next")
        }
    }
}

/** The three key indicators — green ✔ per stored key (values never shown). */
@Composable
private fun KeyStatusRow(state: OnboardingState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        KeyChip("MAC", state.macOk)
        KeyChip("TOKEN", state.tokenOk)
        KeyChip("BLE KEY", state.bleKeyOk)
    }
}

@Composable
private fun KeyChip(label: String, ok: Boolean) {
    Text(
        "${if (ok) "✔" else "○"} $label",
        color = if (ok) Color(0xFF1B8E3D) else Color(0xFF9E9E9E),
        fontWeight = if (ok) FontWeight.Bold else FontWeight.Normal,
        fontSize = 13.sp,
    )
}

/** Second-factor code dialog (Mi 2FA / Garmin MFA); dismiss = cancel. */
@Composable
internal fun CodeDialog(promptText: String, onSubmit: (String?) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onSubmit(null) },
        title = { Text("Verification code") },
        text = {
            Column {
                Text(promptText, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    singleLine = true,
                    label = { Text("Code") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(code) }) { Text("Submit") } },
        dismissButton = { TextButton(onClick = { onSubmit(null) }) { Text("Cancel") } },
    )
}

/**
 * In-app captcha dialog (Mi login) — the owner-required pop-out (no paste
 * fallback for the image itself). Renders at **full dialog width with a
 * 280 dp minimum on both axes** (`ContentScale.Fit`, no max-height cap),
 * fixing the spike's "too small" rendering. Dismiss = cancel.
 */
@Composable
internal fun CaptchaDialog(image: ByteArray, onSubmit: (String?) -> Unit) {
    var code by remember { mutableStateOf("") }
    val bitmap = remember(image) { BitmapFactory.decodeByteArray(image, 0, image.size) }
    AlertDialog(
        onDismissRequest = { onSubmit(null) },
        title = { Text("Captcha required") },
        text = {
            Column {
                Text(
                    "Xiaomi wants a captcha — type the characters shown in the image.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "captcha image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(min = 280.dp)
                            .heightIn(min = 280.dp),
                    )
                } else {
                    Text(
                        "captcha image could not be decoded",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    singleLine = true,
                    label = { Text("Captcha code") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(code) }) { Text("Submit") } },
        dismissButton = { TextButton(onClick = { onSubmit(null) }) { Text("Cancel") } },
    )
}
