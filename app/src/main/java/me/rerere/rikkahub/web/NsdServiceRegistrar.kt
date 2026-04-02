package me.rerere.rikkahub.web

import android.content.Context
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

    fun findLanAddress(): InetAddress? {
        return findLanAddresses().ipv4Address
    }

    fun findLanAddresses(): LanAddressInfo {
        return runCatching {
            val addresses = NetworkInterface.getNetworkInterfaces()
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

            val ipv4Address = addresses
                .asSequence()
                .filterIsInstance<Inet4Address>()
                .firstOrNull { address ->
                    !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress
                }

            val ipv6Candidates = addresses
                .asSequence()
                .filterIsInstance<Inet6Address>()
                .filter { address ->
                    !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress &&
                        !address.isAnyLocalAddress &&
                        !address.isMulticastAddress
                }
                .toList()

            // Prefer the address the OS would actually use for outgoing traffic
            // (matches what IPv6 detection sites see), then fall back to list-based selection.
            val ipv6Address = findRoutingPreferredIpv6Address()
                ?: ipv6Candidates.firstOrNull(Inet6Address::isGlobalLanAddress)
                ?: ipv6Candidates.firstOrNull(Inet6Address::isUniqueLocalAddress)

            LanAddressInfo(
                ipv4Address = ipv4Address,
                ipv6Address = ipv6Address,
            )
        }.getOrDefault(LanAddressInfo())
    }

    /**
     * Determines the preferred outbound IPv6 address by performing a route lookup via a
     * no-op UDP "connect". No packet is actually sent; the OS just picks the correct
     * source address according to its routing table and RFC 6724 address selection.
     */
    private fun findRoutingPreferredIpv6Address(): Inet6Address? {
        return try {
            java.net.DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName("2001:4860:4860::8888"), 53)
                socket.localAddress as? Inet6Address
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine preferred IPv6 via routing: ${e.message}")
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
