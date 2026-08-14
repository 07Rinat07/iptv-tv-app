package com.iptv.tv.core.p2p

import java.io.IOException
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveMediaOutputTest {
    @Test
    fun retainedBytesTracksTheBoundedLiveWindow() {
        val buffer = AceLiveMediaBuffer(maxBufferedBytes = 188 * 64)

        assertEquals(188 * 40, buffer.append(ByteArray(188 * 40)))
        assertEquals(188 * 40, buffer.retainedBytes())

        assertEquals(188 * 40, buffer.append(ByteArray(188 * 40)))
        assertEquals(188 * 40, buffer.retainedBytes())
    }

    @Test
    fun closedMediaBufferRejectsOutputBytes() {
        val buffer = AceLiveMediaBuffer(maxBufferedBytes = 188 * 64)
        buffer.close()

        assertEquals(0, buffer.append(ByteArray(188 * 8)))
        assertEquals(0, buffer.retainedBytes())
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
    fun unconfirmedReadDoesNotConsumePlayableHeadroom() {
        val buffer = AceLiveMediaBuffer(maxBufferedBytes = 188 * 64)
        val data = ByteArray(188 * 16)
        buffer.append(data)
        val reader = buffer.openReader()

        val bytes = ByteArray(512)
        assertEquals(512, reader.read(bytes, 0, bytes.size))

        val pending = reader.snapshot()
        assertEquals(0L, pending.consumerOffset)
        assertEquals(data.size.toLong(), pending.liveEdgeOffset)
        assertEquals(data.size.toLong(), pending.playableBytes)
        assertEquals(0L, pending.totalDeliveredBytes)
        assertNull(pending.consumerBytesPerSecond)
        assertFalse(pending.fellBehind)
        buffer.close()
    }

    @Test
    fun confirmedDeliveryConsumesPlayableHeadroom() {
        val buffer = AceLiveMediaBuffer(maxBufferedBytes = 188 * 64)
        val data = ByteArray(188 * 16)
        buffer.append(data)
        val reader = buffer.openReader()

        val bytes = ByteArray(512)
        assertEquals(512, reader.read(bytes, 0, bytes.size))
        val delivered = reader.confirmDelivered(512, nowMillis = 1_000L)

        assertEquals(512L, delivered.consumerOffset)
        assertEquals(data.size.toLong(), delivered.liveEdgeOffset)
        assertEquals(data.size.toLong() - 512L, delivered.playableBytes)
        assertEquals(512L, delivered.totalDeliveredBytes)
        assertNull(delivered.consumerBytesPerSecond)
        assertFalse(delivered.fellBehind)
        buffer.close()
    }

    @Test
    fun consumerRateUsesConfirmedDeliveryAndAppearsAfterTimedSample() {
        val buffer = AceLiveMediaBuffer(maxBufferedBytes = 188 * 64)
        buffer.append(ByteArray(4_000))
        val reader = buffer.openReader()
        val bytes = ByteArray(1_000)

        assertEquals(1_000, reader.read(bytes, 0, bytes.size))
        assertNull(reader.confirmDelivered(1_000, nowMillis = 1_000L).consumerBytesPerSecond)

        assertEquals(1_000, reader.read(bytes, 0, bytes.size))
        val second = reader.confirmDelivered(1_000, nowMillis = 2_000L)

        assertEquals(1_000L, second.consumerBytesPerSecond)
        assertEquals(2_000L, second.totalDeliveredBytes)
        assertEquals(2_000L, second.playableBytes)
        buffer.close()
    }

    @Test
    fun trimMarksConfirmedReaderAsFallenBehindAndClampsToLiveFloor() {
        val maxBytes = 188 * 64
        val firstSegmentBytes = 188 * 32
        val buffer = AceLiveMediaBuffer(maxBufferedBytes = maxBytes)
        buffer.append(ByteArray(firstSegmentBytes))
        val reader = buffer.openReader()
        val bytes = ByteArray(188)

        assertEquals(188, reader.read(bytes, 0, bytes.size))
        reader.confirmDelivered(188, nowMillis = 1_000L)
        buffer.append(ByteArray(maxBytes))

        val snapshot = reader.snapshot()
        assertEquals(firstSegmentBytes.toLong(), snapshot.consumerOffset)
        assertEquals((firstSegmentBytes + maxBytes).toLong(), snapshot.liveEdgeOffset)
        assertEquals(maxBytes.toLong(), snapshot.playableBytes)
        assertEquals(188L, snapshot.totalDeliveredBytes)
        assertTrue(snapshot.fellBehind)
        buffer.close()
    }

    @Test
    fun mediaBufferKeepsIndependentConfirmedReaderCursors() {
        val buffer = AceLiveMediaBuffer(maxBufferedBytes = 188 * 64)
        val first = buffer.openReader()
        val second = buffer.openReader()
        val data = ByteArray(188 * 8) { index -> index.toByte() }
        buffer.append(data)

        val firstRead = ByteArray(256)
        val secondRead = ByteArray(128)
        assertEquals(256, first.read(firstRead, 0, firstRead.size))
        assertEquals(128, second.read(secondRead, 0, secondRead.size))
        assertArrayEquals(firstRead.copyOf(128), secondRead)

        val firstSnapshot = first.confirmDelivered(256, nowMillis = 1_000L)
        val secondSnapshot = second.confirmDelivered(128, nowMillis = 1_000L)

        assertTrue(firstSnapshot.readerId != secondSnapshot.readerId)
        assertEquals(256L, firstSnapshot.consumerOffset)
        assertEquals(128L, secondSnapshot.consumerOffset)
        assertEquals(data.size.toLong() - 256L, firstSnapshot.playableBytes)
        assertEquals(data.size.toLong() - 128L, secondSnapshot.playableBytes)
        buffer.close()
    }

    @Test(expected = IllegalArgumentException::class)
    fun confirmationMustMatchPendingReadExactly() {
        val buffer = AceLiveMediaBuffer(maxBufferedBytes = 188 * 64)
        buffer.append(ByteArray(188 * 8))
        val reader = buffer.openReader()
        val bytes = ByteArray(188)

        reader.read(bytes, 0, bytes.size)
        reader.confirmDelivered(187, nowMillis = 1_000L)
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
