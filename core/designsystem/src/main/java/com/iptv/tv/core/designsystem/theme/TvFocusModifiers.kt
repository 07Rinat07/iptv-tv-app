package com.iptv.tv.core.designsystem.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp

fun Modifier.tvFocusOutline(): Modifier = composed {
    val shape = RoundedCornerShape(12.dp)
    val defaultColor = MaterialTheme.colorScheme.outlineVariant
    val focusedColor = MaterialTheme.colorScheme.primary
    val focused = remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (focused.value) focusedColor else defaultColor,
        label = "tvFocusOutline"
    )

    this
        .onFocusChanged { state -> focused.value = state.hasFocus }
        .border(width = 2.dp, color = borderColor, shape = shape)
}
