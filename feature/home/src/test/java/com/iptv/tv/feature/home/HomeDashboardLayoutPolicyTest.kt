package com.iptv.tv.feature.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDashboardLayoutPolicyTest {
    @Test
    fun `wide dashboard includes exact TV boundary`() {
        assertTrue(
            shouldUseWideHomeDashboard(
                widthDp = HOME_DASHBOARD_MIN_WIDTH_DP,
                heightDp = HOME_DASHBOARD_MIN_HEIGHT_DP
            )
        )
    }

    @Test
    fun `width below boundary uses compact dashboard`() {
        assertFalse(
            shouldUseWideHomeDashboard(
                widthDp = HOME_DASHBOARD_MIN_WIDTH_DP - 1f,
                heightDp = HOME_DASHBOARD_MIN_HEIGHT_DP
            )
        )
    }

    @Test
    fun `height below boundary uses compact dashboard`() {
        assertFalse(
            shouldUseWideHomeDashboard(
                widthDp = HOME_DASHBOARD_MIN_WIDTH_DP,
                heightDp = HOME_DASHBOARD_MIN_HEIGHT_DP - 1f
            )
        )
    }
}
