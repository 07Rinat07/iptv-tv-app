package com.iptv.tv.core.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgInputSafetyPolicyTest {
    @Test
    fun fieldXmlTvSourceFitsInsideBoundedEnvelope() {
        assertTrue(EpgInputSafetyPolicy.allowsReportedLength(88_578_547L))
    }

    @Test
    fun exact128MiBLimitIsAccepted() {
        assertTrue(EpgInputSafetyPolicy.allowsReportedLength(128L * 1024L * 1024L))
    }

    @Test
    fun bytePast128MiBLimitIsRejected() {
        assertFalse(EpgInputSafetyPolicy.allowsReportedLength((128L * 1024L * 1024L) + 1L))
    }

    @Test
    fun unknownLengthFallsThroughToStreamingByteGuard() {
        assertTrue(EpgInputSafetyPolicy.allowsReportedLength(-1L))
    }
}
