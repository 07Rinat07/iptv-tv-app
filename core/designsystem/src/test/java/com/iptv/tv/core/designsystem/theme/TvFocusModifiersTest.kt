package com.iptv.tv.core.designsystem.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvFocusModifiersTest {

    @Test
    fun requestsBringIntoViewOnlyWhenFocusIsGained() {
        assertTrue(shouldRequestBringIntoView(wasFocused = false, hasFocus = true))
        assertFalse(shouldRequestBringIntoView(wasFocused = true, hasFocus = true))
        assertFalse(shouldRequestBringIntoView(wasFocused = true, hasFocus = false))
        assertFalse(shouldRequestBringIntoView(wasFocused = false, hasFocus = false))
    }
}
