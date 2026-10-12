package com.xiaomiscalesync.ui.theme

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xiaomiscalesync.ui.main.StatusChip

/** Colour roles for pills/banners — all taken from the Material scheme. */
enum class Tone { PRIMARY, NEUTRAL, WARNING, ERROR }

@Composable
fun Tone.container(): Color = when (this) {
    Tone.PRIMARY -> MaterialTheme.colorScheme.primaryContainer
    Tone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
    Tone.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
    Tone.ERROR -> MaterialTheme.colorScheme.errorContainer
}

@Composable
fun Tone.content(): Color = when (this) {
    Tone.PRIMARY -> MaterialTheme.colorScheme.onPrimaryContainer
    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
    Tone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
}

fun StatusChip.tone(): Tone = when (this) {
    StatusChip.PUSHED -> Tone.PRIMARY
    StatusChip.QUEUED, StatusChip.SKIPPED_EXISTING, StatusChip.GUEST -> Tone.NEUTRAL
    StatusChip.NEEDS_REVIEW -> Tone.WARNING
}

/** A non-interactive status label (assist chips look tappable; these are not). */
@Composable
fun Pill(text: String, tone: Tone, compact: Boolean = false) {
    Surface(color = tone.container(), contentColor = tone.content(), shape = RoundedCornerShape(50)) {
        Text(
            text,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(
                horizontal = if (compact) 8.dp else 12.dp,
                vertical = if (compact) 3.dp else 6.dp,
            ),
        )
    }
}
