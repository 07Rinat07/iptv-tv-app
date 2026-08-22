package com.iptv.tv.feature.favorites

import com.iptv.tv.core.model.FavoritesPortableImportResult
import com.iptv.tv.core.model.FavoritesPortableImportStatus
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FavoritesImportHelpersTest {
    @Test
    fun boundedReaderReturnsContentWithinLimit() {
        val content = "{\"format\":\"rinat-iptv-favorites\"}"

        val actual = StringReader(content).readBoundedText(content.length)

        assertEquals(content, actual)
    }

    @Test
    fun boundedReaderRejectsOversizedContentBeforeUnboundedGrowth() {
        try {
            StringReader("12345").readBoundedText(maxChars = 4)
            fail("Oversized backup must be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("слишком большой"))
        }
    }

    @Test
    fun successFeedbackReportsMergeAndRedactionSummary() {
        val feedback = favoritesImportFeedback(
            FavoritesPortableImportResult(
                status = FavoritesPortableImportStatus.SUCCESS,
                importedFavorites = 2,
                mergedFavorites = 1,
                importedVariants = 4,
                redactedVariantsIgnored = 3,
                skippedUnrestorableFavorites = 1
            )
        )

        assertNull(feedback.error)
        val info = assertNotNull(feedback.info).let { feedback.info.orEmpty() }
        assertTrue(info.contains("добавлено: 2"))
        assertTrue(info.contains("объединено: 1"))
        assertTrue(info.contains("вариантов: 4"))
        assertTrue(info.contains("скрытых вариантов пропущено: 3"))
        assertTrue(info.contains("невосстановимых каналов пропущено: 1"))
    }

    @Test
    fun invalidBackupProducesErrorInsteadOfSuccessMessage() {
        val feedback = favoritesImportFeedback(
            FavoritesPortableImportResult(
                status = FavoritesPortableImportStatus.INVALID_FORMAT,
                message = "Not a Rinat IPTV Favorites backup"
            )
        )

        assertNull(feedback.info)
        assertTrue(feedback.error.orEmpty().contains("Неверный формат RIPTV backup"))
        assertTrue(feedback.error.orEmpty().contains("Not a Rinat IPTV Favorites backup"))
    }

    @Test
    fun unsupportedVersionProducesDedicatedError() {
        val feedback = favoritesImportFeedback(
            FavoritesPortableImportResult(
                status = FavoritesPortableImportStatus.UNSUPPORTED_VERSION,
                message = "Unsupported Favorites backup version 2"
            )
        )

        assertNull(feedback.info)
        assertTrue(feedback.error.orEmpty().contains("Версия RIPTV backup не поддерживается"))
    }
}
