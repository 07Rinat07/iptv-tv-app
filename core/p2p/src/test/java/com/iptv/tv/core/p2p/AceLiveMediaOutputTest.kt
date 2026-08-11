package com.iptv.tv.core.p2p

import java.io.IOException
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveMediaOutputTest {
    @Test
    fun retainedBytesTracksTheBoundedLiveWindow() {
        val buffer = AceLiveMediaBuffer(maxBufferedBytes = 188 * 64)

        buffer.append(ByteArray(188 * 40))
        assertEquals(188 * 40, buffer.retainedBytes())

        buffer.append(ByteArray(188 * 40))
        assertEquals(188 * 40, buffer.retainedBytes())
    }

    @Test
    fun directInfoHashStripsStandardSignatureTailWithoutTransportKey() {
        val payload = ByteArray(188 * 8) { index -> (index and 0xff).toByte() }
        val result = AceLiveMediaAuthenticator(publicKeyDer = null)
            .verifyAndStrip(payload + ByteArray(96) { 7 })

        assertTrue(result is P2pResult.Success)
        assertArrayEquals(payload, (result as P2pResult.Success).data)
    }

    @Test
    fun authenticatorVerifiesAndStripsRsaSignatureTail() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val payload = ByteArray(32 * 1024) { index -> (index % 251).toByte() }
        val signer = Signature.getInstance("SHA1withRSA")
        signer.initSign(keyPair.private)
        signer.update(payload)
        val signedPiece = payload + signer.sign()

        val result = AceLiveMediaAuthenticator(keyPair.public.encoded).verifyAndStrip(signedPiece)

        assertTrue(result is P2pResult.Success)
        assertArrayEquals(payload, (result as P2pResult.Success).data)
    }

    @Test
    fun mediaBufferKeepsIndependentReaderCursors() {
        val buffer = AceLiveMediaBuffer(maxBufferedBytes = 188 * 64)
        val first = buffer.openReader()
        val second = buffer.openReader()
        val data = ByteArray(188 * 8) { index -> index.toByte() }
        buffer.append(data)

        val firstRead = ByteArray(256)
        val secondRead = ByteArray(256)
        assertEquals(256, first.read(firstRead, 0, firstRead.size))
        assertEquals(256, second.read(secondRead, 0, secondRead.size))
        assertArrayEquals(firstRead, secondRead)
        buffer.close()
    }

    @Test
    fun interruptedMediaReaderEndsAsHandledIoFailure() {
        val buffer = AceLiveMediaBuffer(maxBufferedBytes = 188 * 64)
        val reader = buffer.openReader()
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            started.countDown()
            try {
                reader.read(ByteArray(188), 0, 188)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                completed.countDown()
            }
        }

        thread.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        thread.interrupt()

        assertTrue(completed.await(1, TimeUnit.SECONDS))
        assertTrue(failure.get() is IOException)
        buffer.close()
    }

    @Test
    fun mpegTsResynchronizerDropsGarbageBeforeStableSyncRun() {
        val packets = ByteArray(188 * 6) { index ->
            if (index % 188 == 0) 0x47 else (index % 127).toByte()
        }
        val output = AceLiveMpegTsResynchronizer().consume(byteArrayOf(1, 2, 3, 4) + packets)

        assertEquals(packets.size, output.size)
        assertEquals(0x47, output[0].toInt() and 0xff)
    }
}
