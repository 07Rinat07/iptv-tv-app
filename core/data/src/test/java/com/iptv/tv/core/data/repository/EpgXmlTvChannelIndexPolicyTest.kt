package com.iptv.tv.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class EpgXmlTvChannelIndexPolicyTest {
    @Test
    fun declaredChannelWithoutProgramsRemainsMatchable() {
        val result = EpgXmlTvChannelIndexPolicy.knownChannelIds(
            declaredChannelIds = listOf("declared-only", "with-program"),
            programmedChannelIds = listOf("with-program")
        )

        assertEquals(listOf("declared-only", "with-program"), result)
    }

    @Test
    fun programmeOnlyChannelIsRetainedForFeedsWithoutDeclarations() {
        val result = EpgXmlTvChannelIndexPolicy.knownChannelIds(
            declaredChannelIds = listOf("declared"),
            programmedChannelIds = listOf("programme-only")
        )

        assertEquals(listOf("declared", "programme-only"), result)
    }

    @Test
    fun duplicateAndBlankIdsDoNotDistortStableOrder() {
        val result = EpgXmlTvChannelIndexPolicy.knownChannelIds(
            declaredChannelIds = listOf("first", "", "second", "first"),
            programmedChannelIds = listOf("second", "third", " ")
        )

        assertEquals(listOf("first", "second", "third"), result)
    }
}
