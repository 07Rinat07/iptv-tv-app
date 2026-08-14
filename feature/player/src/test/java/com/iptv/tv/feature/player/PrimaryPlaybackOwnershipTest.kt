package com.iptv.tv.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimaryPlaybackOwnershipTest {
    @Test
    fun `session ids never repeat after sessions are cleared`() {
        val ownership = PrimaryPlaybackOwnership()
        val requestA = ownership.beginRequest()
        val sessionA = ownership.nextSessionId()

        ownership.invalidateRequest()
        val requestB = ownership.beginRequest()
        val sessionB = ownership.nextSessionId()

        assertNotEquals(requestA, requestB)
        assertNotEquals(sessionA, sessionB)
    }

    @Test
    fun `late retry A cannot own C after A B C zapping`() {
        val ownership = PrimaryPlaybackOwnership()

        val requestA = ownership.beginRequest()
        val sessionA = ownership.nextSessionId()

        val requestB = ownership.beginRequest()
        ownership.nextSessionId()

        val requestC = ownership.beginRequest()
        val sessionC = ownership.nextSessionId()

        assertFalse(
            ownership.ownsSession(
                expectedRequestId = requestA,
                expectedSessionId = sessionA,
                currentRequestId = requestC,
                currentSessionId = sessionC
            )
        )
        assertTrue(
            ownership.ownsSession(
                expectedRequestId = requestC,
                expectedSessionId = sessionC,
                currentRequestId = requestC,
                currentSessionId = sessionC
            )
        )
        assertFalse(ownership.isCurrentRequest(requestB))
    }

    @Test
    fun `retry session keeps request ownership but gets a fresh decoder id`() {
        val ownership = PrimaryPlaybackOwnership()
        val request = ownership.beginRequest()
        val originalSession = ownership.nextSessionId()
        val retrySession = ownership.nextSessionId()

        assertNotEquals(originalSession, retrySession)
        assertTrue(
            ownership.ownsSession(
                expectedRequestId = request,
                expectedSessionId = retrySession,
                currentRequestId = request,
                currentSessionId = retrySession
            )
        )
    }

    @Test
    fun `invalidation rejects callbacks even if numeric session is unchanged`() {
        val ownership = PrimaryPlaybackOwnership()
        val request = ownership.beginRequest()
        val session = ownership.nextSessionId()

        ownership.invalidateRequest()

        assertFalse(
            ownership.ownsSession(
                expectedRequestId = request,
                expectedSessionId = session,
                currentRequestId = request,
                currentSessionId = session
            )
        )
    }
}
