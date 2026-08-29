package com.iptv.tv.core.p2p

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import java.io.Closeable
import java.net.DatagramSocket
import java.net.Inet4Address

/** Installs only the context required for Android LAN selection and MulticastLock. */
internal class AceLiveLsdInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.let(AceLiveLsdRuntimeRegistry::install)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}

internal data class AceLiveLsdLanBinding(
    val localAddress: Inet4Address,
    val prefixLength: Int,
    val bindDatagramSocket: (DatagramSocket) -> Unit = {}
) {
    init {
        require(prefixLength in MIN_SAFE_IPV4_PREFIX..32)
    }

    private companion object {
        const val MIN_SAFE_IPV4_PREFIX = 8
    }
}

internal fun interface AceLiveLsdLanResolver {
    fun resolve(): AceLiveLsdLanBinding?
}

/** Uses physical Wi-Fi/Ethernet only; VPN/cellular routes are never joined. */
internal class AndroidAceLiveLsdLanResolver(context: Context) : AceLiveLsdLanResolver {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun resolve(): AceLiveLsdLanBinding? {
        val active = connectivityManager.activeNetwork
        val candidates = buildList {
            if (active != null) add(active)
            connectivityManager.allNetworks.forEach { network -> if (network != active) add(network) }
        }
        return candidates.firstNotNullOfOrNull(::resolveNetwork)
    }

    private fun resolveNetwork(network: Network): AceLiveLsdLanBinding? {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        if (
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) return null

        val properties = connectivityManager.getLinkProperties(network) ?: return null
        return properties.linkAddresses.firstNotNullOfOrNull { linkAddress ->
            val address = linkAddress.address as? Inet4Address ?: return@firstNotNullOfOrNull null
            if (!isAceLiveLsdLocalIpv4(address)) return@firstNotNullOfOrNull null
            if (linkAddress.prefixLength !in MIN_SAFE_IPV4_PREFIX..32) return@firstNotNullOfOrNull null
            AceLiveLsdLanBinding(
                localAddress = address,
                prefixLength = linkAddress.prefixLength,
                bindDatagramSocket = { socket -> network.bindSocket(socket) }
            )
        }
    }

    private companion object {
        const val MIN_SAFE_IPV4_PREFIX = 8
    }
}

private fun isAceLiveLsdLocalIpv4(address: Inet4Address): Boolean {
    if (
        address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isMulticastAddress
    ) return false
    if (address.isSiteLocalAddress) return true
    val bytes = address.address
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    return first == 100 && second in 64..127
}

internal fun interface AceLiveLsdMulticastLeaseFactory {
    fun acquire(): Closeable?
}

internal class AndroidAceLiveLsdMulticastLeaseFactory(context: Context) :
    AceLiveLsdMulticastLeaseFactory {
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    override fun acquire(): Closeable? {
        val lock = runCatching {
            wifiManager?.createMulticastLock(LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull() ?: return null
        return Closeable { runCatching { if (lock.isHeld) lock.release() } }
    }

    private companion object {
        const val LOCK_TAG = "iptv-tv-app:ace-live-lsd"
    }
}
