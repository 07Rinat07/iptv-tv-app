package com.iptv.tv.core.p2p

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pPreparationGenerationTest {
    @Test
    fun newerPreparationSupersedesOlderToken() {
        val generation = P2pPreparationGeneration()
        val first = generation.begin()
        val second = generation.begin()

        assertFalse(generation.isCurrent(first))
        assertTrue(generation.isCurrent(second))
    }

    @Test
    fun stopInvalidationSupersedesCurrentPreparation() {
        val generation = P2pPreparationGeneration()
        val preparation = generation.begin()

        generation.invalidate()

        assertFalse(generation.isCurrent(preparation))
    }

    @Test
    fun tokenRemainsCurrentUntilAnotherActionBegins() {
        val generation = P2pPreparationGeneration()
        val preparation = generation.begin()

        assertTrue(generation.isCurrent(preparation))
        assertTrue(generation.isCurrent(preparation))
    }
}
