package com.iptv.tv.feature.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
    Surface(
        modifier = modifier
            .fillMaxWidth(0.72f)
            .widthIn(max = 760.dp),
        color = if (feedback.isError) colors.errorContainer else colors.surfaceVariant,
        contentColor = if (feedback.isError) colors.onErrorContainer else colors.onSurfaceVariant,
        tonalElevation = 10.dp,
        shadowElevation = 10.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            text = feedback.message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
