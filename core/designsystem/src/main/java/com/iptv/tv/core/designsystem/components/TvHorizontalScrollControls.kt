package com.iptv.tv.core.designsystem.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun TvHorizontalScrollControls(
    state: ScrollState,
    modifier: Modifier = Modifier,
    pageFraction: Float = 0.72f
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val pageSizePx = with(density) {
        (screenWidthDp.dp * pageFraction.coerceIn(0.35f, 0.95f)).roundToPx()
    }.coerceAtLeast(1)
    val maxValue by remember(state) { derivedStateOf { state.maxValue } }
    val value by remember(state) { derivedStateOf { state.value } }

    if (maxValue <= 0) return

    val progress = ((value.toFloat() / maxValue.toFloat()) * 100f)
        .toInt()
        .coerceIn(0, 100)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalScrollButton(
            symbol = "⇤",
            description = "Вернуться к началу временной шкалы",
            enabled = value > 0,
            onClick = { scope.launch { state.animateScrollTo(0) } }
        )
        HorizontalScrollButton(
            symbol = "⇠",
            description = "Прокрутить временную шкалу влево",
            enabled = value > 0,
            onClick = {
                scope.launch {
                    state.animateScrollTo((value - pageSizePx).coerceAtLeast(0))
                }
            }
        )
        Text(
            text = "$progress%",
            style = MaterialTheme.typography.labelLarge
        )
        HorizontalScrollButton(
            symbol = "⇢",
            description = "Прокрутить временную шкалу вправо",
            enabled = value < maxValue,
            onClick = {
                scope.launch {
                    state.animateScrollTo((value + pageSizePx).coerceAtMost(maxValue))
                }
            }
        )
    }
}

@Composable
private fun HorizontalScrollButton(
    symbol: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(text = symbol, style = MaterialTheme.typography.titleLarge)
    }
}
