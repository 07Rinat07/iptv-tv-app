package com.iptv.tv.core.p2p

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.decodeBase64

/** Resolves a 40-hex Ace content ID through the signed public transport catalog. */
class AceContentCatalogResolver(
    client: OkHttpClient,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val nextRequestRandom: () -> Long = ::securePositiveLong
) {
    private val httpClient = client.newBuilder()
        .connectTimeout(HOST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(HOST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(HOST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun resolve(contentId: String): P2pResult<AceResolvedLiveTransport> {
        val normalized = contentId.trim().lowercase(Locale.ROOT)
        if (!CONTENT_ID_PATTERN.matches(normalized)) {
            return P2pResult.Error("Ace content ID must contain exactly 40 hexadecimal characters")
        }

        cache[normalized]
            ?.takeIf { entry -> clockMillis() - entry.resolvedAtMillis < CACHE_TTL_MILLIS }
            ?.let { entry -> return P2pResult.Success(entry.transport) }

        var lastError: Throwable? = null
        for (host in CATALOG_HOSTS) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val transportBytes = try {
                fetch(host, normalized)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                continue
            }
            when (val decoded = AceTransportDescriptorDecoder.decodeLive(transportBytes)) {
                is P2pResult.Success -> {
                    if (cache.size >= MAX_CACHE_ENTRIES) {
                        cache.entries.minByOrNull { it.value.resolvedAtMillis }?.let { oldest ->
                            cache.remove(oldest.key, oldest.value)
                        }
                    }
                    cache[normalized] = CacheEntry(decoded.data, clockMillis())
                    return decoded
                }

                is P2pResult.Error -> {
                    lastError = decoded.cause ?: IllegalArgumentException(decoded.message)
                }
            }
        }

        return P2pResult.Error(
            message = "Ace transport catalog did not return valid live metadata",
            cause = lastError
        )
    }

    internal fun signature(contentId: String, requestRandom: Long): String {
        require(CONTENT_ID_PATTERN.matches(contentId)) { "contentId must be normalized 40-hex" }
        require(requestRandom > 0) { "requestRandom must be positive" }
        val signedFields = "_n=$CLIENT_VERSION#_p=$CLIENT_PLATFORM#_r=$requestRandom" +
            "#_v=$CLIENT_VERSION_CODE#pid=$contentId"
        val digest = MessageDigest.getInstance("SHA-1").apply {
            update(signedFields.toByteArray(Charsets.US_ASCII))
            update(CATALOG_SIGNING_SECRET)
        }.digest()
        return digest.toHex()
    }

    private suspend fun fetch(host: String, contentId: String): ByteArray = withContext(Dispatchers.IO) {
        val requestRandom = nextRequestRandom()
        val requestSignature = signature(contentId, requestRandom)
        val url = "http://$host:$CATALOG_PORT/gettorrent" +
            "?_n=$CLIENT_VERSION&_p=$CLIENT_PLATFORM&_r=$requestRandom" +
            "&_v=$CLIENT_VERSION_CODE&pid=$contentId&_s=$requestSignature"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/xml,text/xml,*/*")
            .header("User-Agent", "myscanerIPTV/1")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Ace catalog HTTP ${response.code}")
            val body = response.body ?: throw IOException("Ace catalog response is empty")
            val declaredLength = body.contentLength()
            if (declaredLength > MAX_RESPONSE_BYTES) {
                throw IOException("Ace catalog response exceeds the size limit")
            }
            val bytes = body.source().readByteArray(MAX_RESPONSE_BYTES.toLong() + 1L)
            if (bytes.size > MAX_RESPONSE_BYTES) {
                throw IOException("Ace catalog response exceeds the size limit")
            }
            parseResponse(bytes.toString(Charsets.UTF_8))
        }
    }

    internal fun parseResponse(xml: String): ByteArray {
        require(xml.length <= MAX_RESPONSE_BYTES) { "Ace catalog response exceeds the size limit" }
        val encoded = extractTag(xml, "torrent")
        val expectedChecksum = extractTag(xml, "checksum").lowercase(Locale.ROOT)
        require(CONTENT_ID_PATTERN.matches(expectedChecksum)) { "Ace catalog checksum is invalid" }
        require(encoded.length <= MAX_BASE64_CHARS) { "Ace catalog transport payload is too large" }

        val transport = encoded.filterNot(Char::isWhitespace).decodeBase64()?.toByteArray()
            ?: error("Ace catalog transport payload is not valid base64")
        require(transport.size <= MAX_TRANSPORT_BYTES) { "Ace catalog transport payload is too large" }
        val actualChecksum = MessageDigest.getInstance("SHA-1").digest(transport).toHex()
        require(actualChecksum == expectedChecksum) { "Ace catalog transport checksum mismatch" }
        return transport
    }

    private fun extractTag(xml: String, tag: String): String {
        val opening = "<$tag>"
        val closing = "</$tag>"
        val start = xml.indexOf(opening).takeIf { it >= 0 }
            ?: error("Ace catalog response is missing $tag")
        val contentStart = start + opening.length
        val end = xml.indexOf(closing, contentStart).takeIf { it >= contentStart }
            ?: error("Ace catalog response is missing $tag")
        return xml.substring(contentStart, end).trim()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private data class CacheEntry(
        val transport: AceResolvedLiveTransport,
        val resolvedAtMillis: Long
    )

    companion object {
        private val CONTENT_ID_PATTERN = Regex("^[0-9a-f]{40}$")
        private val CATALOG_HOSTS = listOf(
            "5.252.161.191",
            "77.120.105.88",
            "64.227.119.64",
            "163.172.187.185"
        )
        private val RANDOM = SecureRandom()
        private val CATALOG_SIGNING_SECRET =
            "H!+:H1NnvvX\\x0bS'(;0/A\\nR{\${\\n/3%1\\x0b*[r0o>QzNGKkXT@v\\x0b3DN;gx_66L2 {`F0,\\tKm>XoG~iY(\\x0bu]6E}\\t~07&H;9qE1d?d-A7S("
                .toByteArray(Charsets.US_ASCII)

        private const val CATALOG_PORT = 8081
        private const val CLIENT_VERSION = "3.2.11"
        private const val CLIENT_PLATFORM = "linux"
        private const val CLIENT_VERSION_CODE = "3021100"
        private const val HOST_TIMEOUT_SECONDS = 4L
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
        private const val MAX_TRANSPORT_BYTES = 1024 * 1024
        private const val MAX_BASE64_CHARS = 1_400_000
        private const val MAX_CACHE_ENTRIES = 64
        private const val CACHE_TTL_MILLIS = 10L * 60L * 1000L

        private fun securePositiveLong(): Long = (RANDOM.nextLong() and Long.MAX_VALUE).coerceAtLeast(1L)
    }
}
