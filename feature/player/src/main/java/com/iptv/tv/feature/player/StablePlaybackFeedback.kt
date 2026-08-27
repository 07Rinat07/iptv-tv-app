package com.iptv.tv.feature.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal const val STABLE_PLAYBACK_FEEDBACK_TOP_PADDING_DP = 68

internal data class StablePlaybackFeedback(
    val message: String,
    val isError: Boolean
)

internal fun stablePlaybackFeedback(
    lastError: String?,
    isStartingPlayback: Boolean
): StablePlaybackFeedback? {
    val error = lastError?.trim()?.takeIf { it.isNotEmpty() }
    if (error != null) {
        return StablePlaybackFeedback(message = error, isError = true)
    }

    return if (isStartingPlayback) {
        StablePlaybackFeedback(message = "Подключение к каналу…", isError = false)
    } else {
        null
    }
}

@Composable
internal fun StablePlaybackFeedbackBanner(
    lastError: String?,
    isStartingPlayback: Boolean,
    modifier: Modifier = Modifier
) {
    val feedback = stablePlaybackFeedback(
        lastError = lastError,
        isStartingPlayback = isStartingPlayback
    ) ?: return

    val colors = MaterialTheme.colorScheme
    val background = if (feedback.isError) {
        Color(0xE6511D1D)
    } else {
        Color.Black.copy(alpha = 0.80f)
    }
    val outline = if (feedback.isError) {
        colors.error.copy(alpha = 0.75f)
    } else {
        colors.primary.copy(alpha = 0.55f)
    }

    Surface(
        modifier = modifier
            .padding(top = STABLE_PLAYBACK_FEEDBACK_TOP_PADDING_DP.dp)
            .fillMaxWidth(0.56f)
            .widthIn(max = 620.dp)
            .semantics {
                liveRegion = if (feedback.isError) {
                    LiveRegionMode.Assertive
                } else {
                    LiveRegionMode.Polite
                }
            },
        color = background,
        contentColor = Color.White,
        border = BorderStroke(1.dp, outline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = feedback.message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
