package com.iptv.tv.feature.settings

import com.iptv.tv.core.common.AppResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsViewModelTvHomeTest {

    @Test
    fun formatTvHomePublishSummary_formatsCountsAndErrors() {
        val summary = formatTvHomePublishSummary(
            listOf(
                "Недавние" to AppResult.Success(3),
                "Избранное" to AppResult.Success(0),
                "Watch Next" to AppResult.Error("unsupported")
            )
        )

        assertEquals("Недавние=3, Избранное=0, Watch Next=ошибка", summary)
    }
}
