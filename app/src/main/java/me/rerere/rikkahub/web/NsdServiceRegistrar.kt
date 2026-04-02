package me.rerere.rikkahub.web

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

private const val TAG = "NsdServiceRegistrar"
private const val DEFAULT_SERVICE_TYPE = "_http._tcp.local."
const val DEFAULT_SERVICE_NAME = "lastchat"

data class RegisteredServiceInfo(
    val serviceName: String,
    val hostname: String,
    val port: Int,
    val address: InetAddress,
)

data class LanAddressInfo(
    val ipv4Address: Inet4Address? = null,
    val ipv6Address: Inet6Address? = null,
)

class NsdServiceRegistrar(
    private val context: Context,
) {
    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    suspend fun register(
        port: Int,
        serviceName: String = DEFAULT_SERVICE_NAME,
        serviceType: String = DEFAULT_SERVICE_TYPE,
        onRegistered: ((RegisteredServiceInfo) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        if (jmdns != null) {
            unregister()
        }

        try {
            val address = findLanAddress()
            if (address == null) {
                Log.w(TAG, "No LAN address available for mDNS registration")
                return@withContext
            }

            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("lastchat-jmdns")?.apply {
                setReferenceCounted(true)
                acquire()
            }

            val mdns = JmDNS.create(address, serviceName)
            val serviceInfo = ServiceInfo.create(
                serviceType,
                serviceName,
                port,
                "LastChat Web Server"
            )

            mdns.registerService(serviceInfo)
            jmdns = mdns

            onRegistered?.invoke(
                RegisteredServiceInfo(
                    serviceName = serviceName,
                    hostname = "$serviceName.local",
                    port = port,
                    address = address,
                )
            )
            Log.i(TAG, "Registered mDNS service $serviceName on $address:$port")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register mDNS service", e)
            cleanup()
        }
    }

    suspend fun unregister() = withContext(Dispatchers.IO) {
        cleanup()
    }

    suspend fun findLanAddress(): InetAddress? {
        return findLanAddresses().ipv4Address
    }

    suspend fun findLanAddresses(): LanAddressInfo = withContext(Dispatchers.IO) {
        val addresses = runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.asSequence()
                ?.filter { iface ->
                    iface.isUp &&
                        !iface.isLoopback &&
                        !iface.isVirtual
                }
                ?.flatMap { iface -> iface.inetAddresses.toList().asSequence() }
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())

        val ipv4Address = addresses
            .asSequence()
            .filterIsInstance<Inet4Address>()
            .firstOrNull { address ->
                !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress
            }

        val ipv6Address = findPublicIpv6Address()

        LanAddressInfo(
            ipv4Address = ipv4Address,
            ipv6Address = ipv6Address,
        )
    }

    /**
     * Returns the public IPv6 address via two strategies:
     * 1. ConnectivityManager, filtered to non-VPN networks — fast and offline.
     * 2. HTTP GET to 6.ipw.cn — returns the actual public IPv6 seen by the internet,
     *    handles cases where the local address from strategy 1 is not publicly reachable.
     *
     * Returns null if neither strategy succeeds (no IPv6 connectivity or all failed).
     */
    private suspend fun findPublicIpv6Address(): Inet6Address? {
        // Strategy 1: read non-VPN network link properties
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            val candidates = cm.allNetworks
                .asSequence()
                .filter { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@filter false
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
                .flatMap { network ->
                    cm.getLinkProperties(network)
                        ?.linkAddresses
                        ?.mapNotNull { it.address as? Inet6Address }
                        .orEmpty()
                        .asSequence()
                }
                .filter { addr ->
                    !addr.isLoopbackAddress &&
                        !addr.isLinkLocalAddress &&
                        !addr.isAnyLocalAddress &&
                        !addr.isMulticastAddress
                }
                .toList()

            val result = candidates.firstOrNull(Inet6Address::isGlobalLanAddress)
                ?: candidates.firstOrNull(Inet6Address::isUniqueLocalAddress)
            if (result != null) return result
        }

        // Strategy 2: ask 6.ipw.cn for the actual public IPv6
        return try {
            val connection = java.net.URL("http://6.ipw.cn").openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val ip = connection.inputStream.bufferedReader().use { it.readText() }.trim()
            connection.disconnect()
            InetAddress.getByName(ip) as? Inet6Address
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch public IPv6 via 6.ipw.cn: ${e.message}")
            null
        }
    }

    private fun cleanup() {
        runCatching {
            jmdns?.unregisterAllServices()
            jmdns?.close()
        }.onFailure {
            Log.w(TAG, "Failed to close JmDNS", it)
        }
        jmdns = null

        runCatching {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        }.onFailure {
            Log.w(TAG, "Failed to release multicast lock", it)
        }
        multicastLock = null
    }
}

private fun Inet6Address.isGlobalLanAddress(): Boolean {
    return !isUniqueLocalAddress() && !isSiteLocalAddress
}

private fun Inet6Address.isUniqueLocalAddress(): Boolean {
    val firstByte = address.firstOrNull()?.toInt() ?: return false
    return (firstByte and 0xFE) == 0xFC
}
