package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpByteRangeTest {
    @Test
    fun noRangeRequestsWholeRepresentation() {
        assertEquals(
            HttpRangeResolution.Full(1_000L),
            HttpByteRange.resolve(null, 1_000L)
        )
    }

    @Test
    fun resolvesClosedRange() {
        assertEquals(
            HttpRangeResolution.Partial(100L, 199L, 1_000L),
            HttpByteRange.resolve("bytes=100-199", 1_000L)
        )
    }

    @Test
    fun resolvesOpenEndedRangeToEof() {
        assertEquals(
            HttpRangeResolution.Partial(900L, 999L, 1_000L),
            HttpByteRange.resolve("bytes=900-", 1_000L)
        )
    }

    @Test
    fun resolvesSuffixRange() {
        assertEquals(
            HttpRangeResolution.Partial(750L, 999L, 1_000L),
            HttpByteRange.resolve("bytes=-250", 1_000L)
        )
    }

    @Test
    fun suffixLargerThanRepresentationReturnsEverythingAsPartial() {
        assertEquals(
            HttpRangeResolution.Partial(0L, 999L, 1_000L),
            HttpByteRange.resolve("bytes=-2000", 1_000L)
        )
    }

    @Test
    fun clampsRequestedEndToEof() {
        assertEquals(
            HttpRangeResolution.Partial(950L, 999L, 1_000L),
            HttpByteRange.resolve("bytes=950-5000", 1_000L)
        )
    }

    @Test
    fun rejectsRangeStartingAtEof() {
        assertEquals(
            HttpRangeResolution.Unsatisfiable(1_000L),
            HttpByteRange.resolve("bytes=1000-", 1_000L)
        )
    }

    @Test
    fun rejectsMalformedAndMultipleRanges() {
        assertTrue(HttpByteRange.resolve("bytes=200-100", 1_000L) is HttpRangeResolution.Unsatisfiable)
        assertTrue(HttpByteRange.resolve("bytes=0-10,20-30", 1_000L) is HttpRangeResolution.Unsatisfiable)
        assertTrue(HttpByteRange.resolve("items=0-10", 1_000L) is HttpRangeResolution.Unsatisfiable)
        assertTrue(HttpByteRange.resolve("bytes=-0", 1_000L) is HttpRangeResolution.Unsatisfiable)
    }

    @Test
    fun emptyRepresentationCannotSatisfyRange() {
        assertEquals(
            HttpRangeResolution.Unsatisfiable(0L),
            HttpByteRange.resolve("bytes=0-", 0L)
        )
    }
}
