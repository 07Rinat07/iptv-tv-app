package com.iptv.tv.core.p2p

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveNodeIdentityTest {
    @Test
    fun extendedHandshakeCarriesVerifiableNodeSignature() {
        val frame = AceLiveNodeIdentity.generate().signedExtendedHandshake(
            minPiece = 100,
            maxPiece = 108,
            timestamp = 5_000
        )

        assertEquals(frame.size - 4, ByteBuffer.wrap(frame, 0, 4).order(ByteOrder.BIG_ENDIAN).int)
        assertEquals(20, frame[4].toInt() and 0xff)
        assertEquals(0, frame[5].toInt() and 0xff)
        val parsed = AceBoundedBencodeParser(frame.copyOfRange(6, frame.size)).parseRootDictionary()
        val nodeId = (parsed.values.getValue("node_id") as AceBencodeValue.Bytes).value
        val signature = (parsed.values.getValue("signature") as AceBencodeValue.Bytes).value
        val messageMap = parsed.values.getValue("m") as AceBencodeValue.Dictionary
        assertEquals(
            ACE_LIVE_LOCAL_UT_PEX_MESSAGE_ID.toLong(),
            (messageMap.values.getValue("ut_pex") as AceBencodeValue.Integer).value
        )
        assertEquals(32, nodeId.size)
        assertEquals(64, signature.size)

        val unsignedFields = LinkedHashMap(parsed.values)
        unsignedFields["signature"] = AceBencodeValue.Bytes(ByteArray(64))
        val digest = MessageDigest.getInstance("SHA-256").digest(
            AceBencodeEncoder.encode(AceBencodeValue.Dictionary(unsignedFields))
        )
        val publicKey = EdDSAPublicKey(
            EdDSAPublicKeySpec(nodeId, EdDSANamedCurveTable.ED_25519_CURVE_SPEC)
        )
        val verifier = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
        verifier.initVerify(publicKey)
        verifier.update(digest)
        assertTrue(verifier.verify(signature))
    }
    @Test
    fun metadataHandshakeDoesNotAdvertisePeerExchange() {
        val frame = AceLiveNodeIdentity.generate().signedMetadataExtendedHandshake(timestamp = 5_000)
        val parsed = AceBoundedBencodeParser(frame.copyOfRange(6, frame.size)).parseRootDictionary()
        val messageMap = parsed.values.getValue("m") as AceBencodeValue.Dictionary

        assertTrue("ut_metadata" in messageMap.values)
        assertTrue("ut_pex" !in messageMap.values)
    }

}
