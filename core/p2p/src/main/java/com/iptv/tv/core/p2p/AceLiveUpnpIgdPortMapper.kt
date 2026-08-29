package com.iptv.tv.core.p2p

import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import org.w3c.dom.Node

internal data class AceLiveUpnpPolicy(
    val ssdpTimeoutMillis: Int = 800,
    val maxSsdpResponseBytes: Int = 8 * 1024,
    val maxDeviceDescriptionBytes: Int = 64 * 1024,
    val maxSoapResponseBytes: Int = 32 * 1024,
    val httpTimeoutMillis: Long = 900L
) {
    init {
        require(ssdpTimeoutMillis in 250..3_000)
        require(maxSsdpResponseBytes in 1_024..16 * 1024)
        require(maxDeviceDescriptionBytes in 4 * 1024..256 * 1024)
        require(maxSoapResponseBytes in 1_024..128 * 1024)
        require(httpTimeoutMillis in 250L..3_000L)
    }
}

internal data class AceLiveUpnpService(
    val serviceType: String,
    val controlUri: URI
)

/**
 * Conservative UPnP IGD control point for the active default gateway only.
 *
 * SSDP replies, LOCATION, device description and SOAP control URLs are all pinned to the selected
 * gateway IPv4 address. Redirects are disabled, payloads and wall-clock waits are bounded, XML DTD
 * and entity declarations are rejected, and only finite TCP AddPortMapping leases are accepted.
 */
internal class AceLiveUpnpIgdPortMapper(
    okHttpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val policy: AceLiveUpnpPolicy = AceLiveUpnpPolicy(),
    private val ssdpEndpoint: InetSocketAddress = DEFAULT_SSDP_ENDPOINT
) : AceLivePortMapper {
    override val protocol = AceLivePortMappingProtocol.UPNP_IGD

    private val baseHttpClient = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(policy.httpTimeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(policy.httpTimeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(policy.httpTimeoutMillis, TimeUnit.MILLISECONDS)
        .callTimeout(policy.httpTimeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun map(request: AceLivePortMappingRequest): AceLiveMappedPort? {
        val location = discoverDescriptionLocation(request.gateway) ?: return null
        val httpClient = httpClientFor(request.gateway)
        val description = getBounded(httpClient, location, policy.maxDeviceDescriptionBytes) ?: return null
        if (description.code !in 200..299) return null
        val service = AceLiveUpnpDescriptionCodec.decodeService(
            bytes = description.body,
            descriptionUri = location,
            expectedGateway = request.gateway.gatewayAddress
        ) ?: return null
        val lease = UpnpLease(request, service, httpClient, policy)
        return if (lease.addOrRenew()) lease else null
    }

    private suspend fun discoverDescriptionLocation(
        gateway: AceLivePortMappingGateway
    ): URI? = withContext(ioDispatcher) {
        var discovered: URI? = null
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            gateway.bindDatagramSocket(socket)
            socket.bind(InetSocketAddress(gateway.localAddress, 0))
            val searches = SSDP_SEARCH_TARGETS.map(AceLiveUpnpSsdpCodec::encodeSearch)
            searches.forEach { bytes ->
                socket.send(DatagramPacket(bytes, bytes.size, ssdpEndpoint))
            }
            val deadlineNanos = System.nanoTime() + policy.ssdpTimeoutMillis * NANOS_PER_MILLI
            while (discovered == null) {
                currentCoroutineContext().ensureActive()
                val remainingMillis = remainingMillis(deadlineNanos)
                if (remainingMillis <= 0L) break
                socket.soTimeout = remainingMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val buffer = ByteArray(policy.maxSsdpResponseBytes + 1)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    break
                }
                if (packet.length > policy.maxSsdpResponseBytes) continue
                if (packet.address != gateway.gatewayAddress) continue
                val response = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val location = AceLiveUpnpSsdpCodec.decodeLocation(response) ?: continue
                if (isGatewayHttpUri(location, gateway.gatewayAddress)) {
                    discovered = location
                }
            }
        }
        discovered
    }

    private fun httpClientFor(gateway: AceLivePortMappingGateway): OkHttpClient {
        val socketFactory = gateway.socketFactory ?: return baseHttpClient
        return baseHttpClient.newBuilder().socketFactory(socketFactory).build()
    }

    private suspend fun getBounded(
        httpClient: OkHttpClient,
        uri: URI,
        maxBytes: Int
    ): AceLiveUpnpHttpResponse? {
        val request = Request.Builder()
            .url(uri.toString())
            .header("Accept", "text/xml, application/xml")
            .header("Connection", "close")
            .get()
            .build()
        return httpClient.executeBounded(request, maxBytes)
    }

    private class UpnpLease(
        private val request: AceLivePortMappingRequest,
        private val service: AceLiveUpnpService,
        private val httpClient: OkHttpClient,
        private val policy: AceLiveUpnpPolicy
    ) : AceLiveMappedPort {
        private val unmapped = AtomicBoolean(false)

        override val protocol = AceLivePortMappingProtocol.UPNP_IGD
        override val internalPort = request.internalPort
        override val externalPort = request.requestedExternalPort
        override val lifetimeSeconds = request.lifetimeSeconds

        suspend fun addOrRenew(): Boolean {
            if (unmapped.get()) return false
            val body = AceLiveUpnpSoapCodec.addPortMappingBody(
                serviceType = service.serviceType,
                internalClient = request.gateway.localAddress.hostAddress,
                internalPort = internalPort,
                externalPort = externalPort,
                lifetimeSeconds = request.lifetimeSeconds
            )
            return executeSoap("AddPortMapping", body)
        }

        override suspend fun renew(): Boolean = addOrRenew()

        override suspend fun unmap() {
            if (!unmapped.compareAndSet(false, true)) return
            val body = AceLiveUpnpSoapCodec.deletePortMappingBody(
                serviceType = service.serviceType,
                externalPort = externalPort
            )
            runCatching { executeSoap("DeletePortMapping", body) }
        }

        private suspend fun executeSoap(action: String, body: String): Boolean {
            val httpRequest = Request.Builder()
                .url(service.controlUri.toString())
                .header("SOAPAction", "\"${service.serviceType}#$action\"")
                .header("Connection", "close")
                .post(body.toRequestBody(XML_MEDIA_TYPE))
                .build()
            val response = httpClient.executeBounded(httpRequest, policy.maxSoapResponseBytes)
                ?: return false
            return response.code in 200..299
        }
    }

    private fun remainingMillis(deadlineNanos: Long): Long =
        ((deadlineNanos - System.nanoTime()) / NANOS_PER_MILLI).coerceAtLeast(0L)

    private companion object {
        val DEFAULT_SSDP_ENDPOINT = InetSocketAddress("239.255.255.250", 1900)
        val SSDP_SEARCH_TARGETS = listOf(
            "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
            "urn:schemas-upnp-org:device:InternetGatewayDevice:1"
        )
        val XML_MEDIA_TYPE = "text/xml; charset=utf-8".toMediaType()
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

internal object AceLiveUpnpSsdpCodec {
    fun encodeSearch(searchTarget: String): ByteArray {
        require(searchTarget.isNotBlank() && searchTarget.length <= 256)
        return buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: 239.255.255.250:1900\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 1\r\n")
            append("ST: ").append(searchTarget).append("\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
    }

    fun decodeLocation(bytes: ByteArray): URI? {
        if (bytes.isEmpty() || bytes.size > 16 * 1024) return null
        val text = bytes.toString(Charsets.ISO_8859_1)
        val lines = text.split("\r\n")
        if (lines.isEmpty() || lines.size > 128) return null
        if (!lines.first().startsWith("HTTP/1.1 200", ignoreCase = true)) return null
        for (line in lines.drop(1)) {
            if (line.isEmpty()) break
            if (line.length > 2_048) return null
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim().lowercase(Locale.US)
            if (name != "location") continue
            val value = line.substring(separator + 1).trim()
            if (value.length !in 8..2_048) return null
            return runCatching { URI(value) }.getOrNull()
        }
        return null
    }
}

internal object AceLiveUpnpDescriptionCodec {
    private val supportedServiceTypes = listOf(
        "urn:schemas-upnp-org:service:WANIPConnection:2",
        "urn:schemas-upnp-org:service:WANIPConnection:1",
        "urn:schemas-upnp-org:service:WANPPPConnection:2",
        "urn:schemas-upnp-org:service:WANPPPConnection:1"
    )

    fun decodeService(
        bytes: ByteArray,
        descriptionUri: URI,
        expectedGateway: Inet4Address
    ): AceLiveUpnpService? {
        if (bytes.isEmpty() || bytes.size > 256 * 1024) return null
        val lowercaseXml = bytes.toString(Charsets.ISO_8859_1).lowercase(Locale.US)
        if ("<!doctype" in lowercaseXml || "<!entity" in lowercaseXml) return null

        val document = runCatching {
            val factory = secureDocumentBuilderFactory()
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        }.getOrNull() ?: return null

        val candidates = ArrayList<Pair<Int, AceLiveUpnpService>>()
        val services = document.getElementsByTagNameNS("*", "service")
        for (index in 0 until services.length) {
            val element = services.item(index) as? Element ?: continue
            val serviceType = childText(element, "serviceType") ?: continue
            val priority = supportedServiceTypes.indexOf(serviceType)
            if (priority < 0) continue
            val controlText = childText(element, "controlURL") ?: continue
            val controlUri = runCatching { descriptionUri.resolve(controlText.trim()) }.getOrNull()
                ?: continue
            if (!isGatewayHttpUri(controlUri, expectedGateway)) continue
            candidates += priority to AceLiveUpnpService(serviceType, controlUri)
        }
        return candidates.minByOrNull { it.first }?.second
    }

    private fun childText(element: Element, localName: String): String? {
        var child: Node? = element.firstChild
        while (child != null) {
            if (child is Element && (child.localName == localName || child.nodeName == localName)) {
                return child.textContent?.trim()?.takeIf(String::isNotEmpty)
            }
            child = child.nextSibling
        }
        return null
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { isXIncludeAware = false }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching {
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
        }
}

internal object AceLiveUpnpSoapCodec {
    fun addPortMappingBody(
        serviceType: String,
        internalClient: String,
        internalPort: Int,
        externalPort: Int,
        lifetimeSeconds: Int
    ): String {
        require(internalPort in 1..65535)
        require(externalPort in 1..65535)
        require(lifetimeSeconds > 0)
        return envelope(
            action = "AddPortMapping",
            serviceType = serviceType,
            arguments = """
                <NewRemoteHost></NewRemoteHost>
                <NewExternalPort>$externalPort</NewExternalPort>
                <NewProtocol>TCP</NewProtocol>
                <NewInternalPort>$internalPort</NewInternalPort>
                <NewInternalClient>${xmlEscape(internalClient)}</NewInternalClient>
                <NewEnabled>1</NewEnabled>
                <NewPortMappingDescription>IPTV TV Ace Live</NewPortMappingDescription>
                <NewLeaseDuration>$lifetimeSeconds</NewLeaseDuration>
            """.trimIndent()
        )
    }

    fun deletePortMappingBody(serviceType: String, externalPort: Int): String {
        require(externalPort in 1..65535)
        return envelope(
            action = "DeletePortMapping",
            serviceType = serviceType,
            arguments = """
                <NewRemoteHost></NewRemoteHost>
                <NewExternalPort>$externalPort</NewExternalPort>
                <NewProtocol>TCP</NewProtocol>
            """.trimIndent()
        )
    }

    private fun envelope(action: String, serviceType: String, arguments: String): String =
        """<?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:$action xmlns:u="${xmlEscape(serviceType)}">
                  $arguments
                </u:$action>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

    private fun xmlEscape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(char)
            }
        }
    }
}

internal data class AceLiveUpnpHttpResponse(
    val code: Int,
    val body: ByteArray
)

private suspend fun OkHttpClient.executeBounded(
    request: Request,
    maxBytes: Int
): AceLiveUpnpHttpResponse? = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: java.io.IOException) {
            if (continuation.isActive) continuation.resume(null)
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                val body = response.body
                if (body == null) {
                    if (continuation.isActive) {
                        continuation.resume(AceLiveUpnpHttpResponse(response.code, ByteArray(0)))
                    }
                } else {
                    val bytes = runCatching { readBounded(body.byteStream(), maxBytes) }.getOrNull()
                    if (continuation.isActive) {
                        continuation.resume(bytes?.let { AceLiveUpnpHttpResponse(response.code, it) })
                    }
                }
            }
        }
    })
}

private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray? {
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    val buffer = ByteArray(4 * 1024)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) return null
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun isGatewayHttpUri(uri: URI, expectedGateway: Inet4Address): Boolean {
    if (!uri.scheme.equals("http", ignoreCase = true)) return false
    if (uri.userInfo != null || uri.fragment != null) return false
    val host = uri.host?.takeIf(String::isNotBlank) ?: return false
    val port = if (uri.port == -1) 80 else uri.port
    if (port !in 1..65535) return false
    val literal = parseIpv4Literal(host) ?: return false
    return literal == expectedGateway
}

private fun parseIpv4Literal(host: String): Inet4Address? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    parts.forEachIndexed { index, part ->
        if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
        val value = part.toIntOrNull() ?: return null
        if (value !in 0..255) return null
        bytes[index] = value.toByte()
    }
    return InetAddress.getByAddress(bytes) as? Inet4Address
}
