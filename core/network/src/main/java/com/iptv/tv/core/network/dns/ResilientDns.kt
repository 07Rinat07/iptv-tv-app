package com.iptv.tv.core.network.dns

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class ResilientDns private constructor(
    private val systemDns: Dns,
    private val fallbackDns: List<Dns>
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val normalizedHost = hostname.trim()
        if (normalizedHost.isBlank()) {
            throw UnknownHostException("Hostname is blank")
        }

        val errors = mutableListOf<String>()

        runCatching { systemDns.lookup(normalizedHost) }
            .onSuccess { addresses ->
                if (addresses.isNotEmpty()) {
                    return addresses
                }
                errors += "system:empty"
            }
            .onFailure { throwable ->
                errors += "system:${throwable.javaClass.simpleName}:${throwable.message.orEmpty()}"
            }

        fallbackDns.forEachIndexed { index, dns ->
            runCatching { dns.lookup(normalizedHost) }
                .onSuccess { addresses ->
                    if (addresses.isNotEmpty()) {
                        return addresses
                    }
                    errors += "doh#$index:empty"
                }
                .onFailure { throwable ->
                    errors += "doh#$index:${throwable.javaClass.simpleName}:${throwable.message.orEmpty()}"
                }
        }

        val details = errors.joinToString(" | ").take(800)
        throw UnknownHostException("DNS lookup failed for $normalizedHost | $details")
    }

    companion object {
        fun create(): Dns {
            val bootstrapClient = OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

            val cloudflare = DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                .bootstrapDnsHosts(
                    InetAddress.getByName("1.1.1.1"),
                    InetAddress.getByName("1.0.0.1")
                )
                .build()

            val google = DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url("https://dns.google/dns-query".toHttpUrl())
                .bootstrapDnsHosts(
                    InetAddress.getByName("8.8.8.8"),
                    InetAddress.getByName("8.8.4.4")
                )
                .build()

            return ResilientDns(
                systemDns = Dns.SYSTEM,
                fallbackDns = listOf(cloudflare, google)
            )
        }
    }
}

