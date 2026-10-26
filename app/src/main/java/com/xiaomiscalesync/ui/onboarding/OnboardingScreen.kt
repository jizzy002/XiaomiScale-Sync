package com.xiaomiscalesync.ui.onboarding

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaomiscalesync.garmin.GarminAuthError
import com.xiaomiscalesync.ui.CodePrompt
import com.xiaomiscalesync.ui.SignInDisclosure
import com.xiaomiscalesync.ui.SignInProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The first-launch onboarding wizard (plan Task 14, spec "Onboarding
 * wizard"): Mi sign-in → profile → history question → Garmin sign-in →
 * done. Every decision lives in [OnboardingViewModel]; this file is the
 * Material 3 rendering plus the two suspend-prompt dialogs.
 *
 * Dialogs use the shared [CodePrompt] hand-off (Mi 2FA/captcha and
 * Garmin MFA all suspend the login call until the dialog answers). The
 * captcha image renders at full dialog width with a 280 dp minimum (both
 * axes) so the characters stay readable.
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
                            Text("Scale Sync")
                            Text(
                                "Step ${state.step.ordinal + 1} of ${OnboardingStep.entries.size} · " +
                                    STEP_TITLES.getValue(state.step),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OnboardingStep.entries.forEachIndexed { index, _ ->
                        val completed = index <= state.step.ordinal
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .background(
                                    if (completed) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(50),
                                ),
                        )
                    }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
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
    var passVisible by rememberSaveable { mutableStateOf(false) }
    var replacingSavedKeys by rememberSaveable { mutableStateOf(false) }
    val enabled = !state.busy

    val allKeys = state.macOk && state.tokenOk && state.bleKeyOk
    val hasManualDraft = listOf(state.manualMac, state.manualToken, state.manualBleKey)
        .any { it.isNotBlank() }
    val replacingKeys = state.manualMode && allKeys && (hasManualDraft || replacingSavedKeys)
    val showingSavedKeys = state.manualMode && allKeys && !replacingKeys
    StepPanel(
        title = when {
            showingSavedKeys -> "Scale keys are ready"
            replacingKeys -> "Replace your scale keys"
            state.manualMode -> "Enter your scale keys"
            else -> "Connect your Xiaomi scale"
        },
        supportingText = when {
            showingSavedKeys ->
                "Your keys are stored encrypted on this phone. Continue with them or replace them manually."
            replacingKeys ->
                "Enter all three new values. The saved keys stay in place until the new ones pass validation."
            state.manualMode ->
                "Enter the MAC address, TOKEN and BLE KEY to connect without signing in to Xiaomi."
            else -> "Sign in to find your scale and retrieve its Bluetooth keys."
        },
    ) {
        if (!state.manualMode) {
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Email or phone") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("Password") },
                singleLine = true,
                enabled = enabled,
                visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { passVisible = !passVisible }, enabled = enabled) {
                        Text(if (passVisible) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            SignInDisclosure(SignInProvider.XIAOMI)
            if (allKeys) {
                OutlinedButton(
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
                    Text("Refresh scale keys")
                }
                ManualKeysButton(enabled = enabled) {
                    viewModel.setManualMode(true)
                }
            } else {
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
                    Text("Connect Xiaomi")
                }
                ManualKeysButton(enabled = enabled) {
                    viewModel.setManualMode(true)
                }
            }
        } else {
            if (showingSavedKeys) {
                OutlinedButton(
                    onClick = { replacingSavedKeys = true },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Replace saved keys")
                }
            } else {
                OutlinedTextField(
                    value = state.manualMac,
                    onValueChange = {
                        viewModel.setManualMac(it)
                        if (allKeys) replacingSavedKeys = true
                    },
                    label = { Text("MAC address") },
                    supportingText = { Text("Example: AA:BB:CC:DD:EE:FF") },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.manualToken,
                    onValueChange = {
                        viewModel.setManualToken(it)
                        if (allKeys) replacingSavedKeys = true
                    },
                    label = { Text("TOKEN") },
                    supportingText = { Text("24 hexadecimal characters") },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.manualBleKey,
                    onValueChange = {
                        viewModel.setManualBleKey(it)
                        if (allKeys) replacingSavedKeys = true
                    },
                    label = { Text("BLE KEY") },
                    supportingText = { Text("32 hexadecimal characters") },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Keys are stored encrypted on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!allKeys && !showingSavedKeys) {
                Button(
                    onClick = { viewModel.submitManualKeys() },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save scale keys")
                }
            }
            if (!showingSavedKeys) {
                if (replacingKeys) {
                    TextButton(
                        onClick = {
                            viewModel.setManualMac("")
                            viewModel.setManualToken("")
                            viewModel.setManualBleKey("")
                            replacingSavedKeys = false
                        },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Cancel replacement")
                    }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.setManualMode(false) },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Sign in with Xiaomi instead")
                    }
                }
            }
        }

        state.foundScale?.let { found ->
            Text(
                "Scale found: $found",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!showingSavedKeys) KeyStatusRow(state)
        if (!allKeys) {
            Text(
                "All three keys are needed before continuing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (allKeys) {
        Button(
            onClick = {
                val continueAllowed = if (state.manualMode && (hasManualDraft || replacingSavedKeys)) {
                    viewModel.submitManualKeys()
                    viewModel.state.value.error == null
                } else {
                    true
                }
                if (continueAllowed) {
                    viewModel.nextFromKeys()
                }
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (replacingKeys) "Save keys and continue" else "Continue")
        }
    }
}

// -------------------------------------------------------------- step 2

/** Step 2 — profile: height, age and sex sent as scale metadata. */
@Composable
private fun ProfileStep(state: OnboardingState, viewModel: OnboardingViewModel) {
    StepPanel(
        title = "Your profile",
        supportingText = "Set up the profile details used with your scale.",
    ) {
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

        Text("Sex", style = MaterialTheme.typography.titleSmall)
        SEX_OPTIONS.forEach { (value, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = state.sex == value,
                    onClick = { viewModel.setSex(value) },
                    enabled = !state.busy,
                )
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Unit", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(8.dp))
            Text("kg", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "The scale reports weight in kilograms. You can choose kg or lb for display later.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

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
    StepPanel(
        title = "Garmin history",
        supportingText = "Did a previous setup already sync your weigh-ins to Garmin?",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.historyAlreadySynced,
                onCheckedChange = viewModel::setHistoryAlreadySynced,
                enabled = !state.busy,
            )
            Text("Yes, my history is already in Garmin", style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            if (state.historyAlreadySynced) {
                "Older records will stay in the app archive and won’t be uploaded again."
            } else {
                "A fresh start: captured weigh-ins, including older records, can be uploaded."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

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
    var passVisible by rememberSaveable { mutableStateOf(false) }
    val enabled = !state.busy

    if (!state.garminSignedIn) StepPanel(
        title = "Connect Garmin",
        supportingText = "Send your weigh-ins to Garmin Connect.",
    ) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Password") },
            singleLine = true,
            enabled = enabled,
            visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { passVisible = !passVisible }, enabled = enabled) {
                    Text(if (passVisible) "Hide" else "Show")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        SignInDisclosure(SignInProvider.GARMIN)
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
            Text("Connect Garmin")
        }
    }
    if (state.garminSignedIn) {
        StepPanel(
            title = "Garmin connected",
            supportingText = "Your account is ready to receive weigh-ins.",
        ) {
            Text(
                "Connected as ${state.garminUser}",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }

    if (state.garminSignedIn) {
        StepFooter(
            backEnabled = enabled,
            back = viewModel::back,
            nextEnabled = enabled,
            next = viewModel::nextFromGarmin,
        )
    } else {
        OutlinedButton(
            onClick = viewModel::back,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Back")
        }
    }
}

// -------------------------------------------------------------- step 5

/** Step 5 — summary; Finish writes `mid`, the backfill cutoff, `onboarded = true`. */
@Composable
private fun DoneStep(
    state: OnboardingState,
    viewModel: OnboardingViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    StepPanel(
        title = "Setup complete",
        supportingText = "Review your choices, then finish setting up Scale Sync.",
    ) {
        Text("Scale keys · MAC, TOKEN and BLE KEY stored")
        Text("Profile · ${state.heightCm} cm, age ${state.age}")
        Text(
            if (state.historyAlreadySynced) {
                "History · existing Garmin records stay archive-only"
            } else {
                "History · captured weigh-ins are ready to upload"
            },
        )
        Text("Garmin Connect · ${state.garminUser}")
    }
    Button(
        onClick = { scope.launch { viewModel.complete() } },
        enabled = !state.busy && !state.completed,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Finish")
    }
    OutlinedButton(
        onClick = { viewModel.back() },
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = next, enabled = nextEnabled, modifier = Modifier.fillMaxWidth()) {
            Text("Continue")
        }
        OutlinedButton(onClick = back, enabled = backEnabled, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}

/** Shared open layout for a step heading, short explanation and its content. */
@Composable
private fun StepPanel(
    title: String,
    supportingText: String,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                supportingText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}

/** A clear alternate route for people who already have their Bluetooth keys. */
@Composable
private fun ManualKeysButton(enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Enter keys manually", style = MaterialTheme.typography.bodyLarge)
            Text(
                "MAC address · TOKEN · BLE KEY",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The three key indicators show readiness without exposing key values. */
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
        "$label ${if (ok) "ready" else "needed"}",
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
 * so the characters stay readable. Dismiss = cancel.
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
