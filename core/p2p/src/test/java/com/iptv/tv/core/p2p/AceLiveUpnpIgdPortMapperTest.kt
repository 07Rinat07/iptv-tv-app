package com.iptv.tv.core.p2p

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveUpnpIgdPortMapperTest {
    private val loopback = ipv4("127.0.0.1")

    @Test
    fun `UPnP discovers gateway service adds finite exact TCP mapping and deletes it`() = runBlocking {
        MockWebServer().use { http ->
            http.start()
            http.enqueue(MockResponse().setResponseCode(200).setBody(deviceDescription("/control")))
            http.enqueue(MockResponse().setResponseCode(200).setBody("<ok/>"))
            http.enqueue(MockResponse().setResponseCode(200).setBody("<ok/>"))
            http.enqueue(MockResponse().setResponseCode(200).setBody("<ok/>"))

            val location = "http://127.0.0.1:${http.port}/root.xml"
            SsdpHarness(loopback, location).use { ssdp ->
                val mapper = AceLiveUpnpIgdPortMapper(
                    okHttpClient = OkHttpClient(),
                    policy = AceLiveUpnpPolicy(
                        ssdpTimeoutMillis = 750,
                        httpTimeoutMillis = 1_000L
                    ),
                    ssdpEndpoint = InetSocketAddress(loopback, ssdp.port)
                )
                val request = AceLivePortMappingRequest(
                    gateway = AceLivePortMappingGateway(loopback, loopback),
                    internalPort = 46000,
                    requestedExternalPort = 46000,
                    lifetimeSeconds = 3600
                )

                val mapped = mapper.map(request)
                assertNotNull(mapped)
                mapped!!
                assertEquals(46000, mapped.externalPort)
                assertTrue(mapped.renew())
                mapped.unmap()

                val descriptionRequest = http.takeRequest(1, TimeUnit.SECONDS)
                val addRequest = http.takeRequest(1, TimeUnit.SECONDS)
                val renewRequest = http.takeRequest(1, TimeUnit.SECONDS)
                val deleteRequest = http.takeRequest(1, TimeUnit.SECONDS)
                assertEquals("/root.xml", descriptionRequest?.path)
                assertEquals("/control", addRequest?.path)
                assertTrue(addRequest?.getHeader("SOAPAction")?.contains("#AddPortMapping") == true)
                val addBody = addRequest?.body?.readUtf8().orEmpty()
                assertTrue(addBody.contains("<NewExternalPort>46000</NewExternalPort>"))
                assertTrue(addBody.contains("<NewInternalPort>46000</NewInternalPort>"))
                assertTrue(addBody.contains("<NewInternalClient>127.0.0.1</NewInternalClient>"))
                assertTrue(addBody.contains("<NewLeaseDuration>3600</NewLeaseDuration>"))
                assertTrue(renewRequest?.getHeader("SOAPAction")?.contains("#AddPortMapping") == true)
                assertTrue(deleteRequest?.getHeader("SOAPAction")?.contains("#DeletePortMapping") == true)
                assertTrue(ssdp.awaitSearch())
            }
        }
    }

    @Test
    fun `UPnP device description refuses off gateway control URL`() {
        val service = AceLiveUpnpDescriptionCodec.decodeService(
            bytes = deviceDescription("http://192.168.1.2/control").toByteArray(),
            descriptionUri = URI("http://192.168.1.1/root.xml"),
            expectedGateway = ipv4("192.168.1.1")
        )

        assertNull(service)
    }

    @Test
    fun `UPnP device description rejects DTD and entity declarations`() {
        val malicious = """
            <?xml version="1.0"?>
            <!DOCTYPE root [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <root><device><serviceList><service>
              <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>
              <controlURL>/control</controlURL>
            </service></serviceList></device></root>
        """.trimIndent().toByteArray()

        assertNull(
            AceLiveUpnpDescriptionCodec.decodeService(
                bytes = malicious,
                descriptionUri = URI("http://192.168.1.1/root.xml"),
                expectedGateway = ipv4("192.168.1.1")
            )
        )
    }

    @Test
    fun `SSDP parser accepts bounded HTTP response and rejects non response`() {
        val response = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("CACHE-CONTROL: max-age=120\r\n")
            append("LOCATION: http://192.168.1.1:5000/root.xml\r\n")
            append("ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n")
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        val location = AceLiveUpnpSsdpCodec.decodeLocation(response)
        assertEquals(URI("http://192.168.1.1:5000/root.xml"), location)
        assertNull(AceLiveUpnpSsdpCodec.decodeLocation("NOTIFY * HTTP/1.1\r\n\r\n".toByteArray()))
    }

    private fun deviceDescription(controlUrl: String): String = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:2</deviceType>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:WANIPConnection:2</serviceType>
                <serviceId>urn:upnp-org:serviceId:WANIPConn1</serviceId>
                <controlURL>$controlUrl</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    private fun ipv4(value: String): Inet4Address = InetAddress.getByName(value) as Inet4Address

    private class SsdpHarness(
        address: Inet4Address,
        private val location: String
    ) : AutoCloseable {
        private val socket = DatagramSocket(0, address).apply { soTimeout = 2_000 }
        private val received = CountDownLatch(1)
        val port: Int = socket.localPort
        private val thread = Thread({ runServer() }, "upnp-ssdp-test").apply {
            isDaemon = true
            start()
        }

        fun awaitSearch(): Boolean = received.await(1, TimeUnit.SECONDS)

        private fun runServer() {
            try {
                val buffer = ByteArray(4 * 1024)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val search = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    .toString(Charsets.US_ASCII)
                if (search.startsWith("M-SEARCH * HTTP/1.1") && search.contains("ssdp:discover")) {
                    received.countDown()
                }
                val response = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("CACHE-CONTROL: max-age=120\r\n")
                    append("LOCATION: ").append(location).append("\r\n")
                    append("ST: urn:schemas-upnp-org:device:InternetGatewayDevice:2\r\n")
                    append("USN: uuid:test::urn:schemas-upnp-org:device:InternetGatewayDevice:2\r\n")
                    append("\r\n")
                }.toByteArray(Charsets.US_ASCII)
                socket.send(DatagramPacket(response, response.size, packet.socketAddress))
            } catch (_: Exception) {
                // Assertions below expose a missing search/response.
            }
        }

        override fun close() {
            socket.close()
            thread.join(1_000)
        }
    }
}
