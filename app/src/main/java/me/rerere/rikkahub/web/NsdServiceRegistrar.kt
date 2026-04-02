package me.rerere.rikkahub.web

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.system.OsConstants
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

private const val TAG = "NsdServiceRegistrar"
private const val DEFAULT_SERVICE_TYPE = "_http._tcp.local."
const val DEFAULT_SERVICE_NAME = "lastchat"
private const val IPV6_ROUTE_PROBE_ADDRESS = "2001:4860:4860::8888"
private const val IPV6_ROUTE_PROBE_PORT = 53

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

        val ipv6Address = findBestIpv6Address()

        LanAddressInfo(
            ipv4Address = ipv4Address,
            ipv6Address = ipv6Address,
        )
    }

    /**
     * Returns the preferred IPv6 for the web server:
     * 1. HTTP GET to 6.ipw.cn — the public IPv6 seen by the internet.
     * 2. ConnectivityManager — fallback to system-reported IPv6.
     */
    suspend fun findBestIpv6Address(): Inet6Address? {
        return findHttpIpv6Address() ?: findSystemIpv6Address()
    }

    suspend fun findHttpIpv6Address(): Inet6Address? = withContext(Dispatchers.IO) {
        try {
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

    suspend fun findSystemIpv6Address(): Inet6Address? = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@withContext null
        val activeNetwork = cm.activeNetwork
        val networkCandidates = cm.allNetworks
            .asSequence()
            .mapNotNull { network -> network.toIpv6Candidate(cm, activeNetwork) }
            .sortedWith(
                compareByDescending<NetworkIpv6Candidate> { it.isActive }
                    .thenByDescending { it.isValidated }
                    .thenByDescending { it.hasIpv6DefaultRoute }
                    .thenByDescending { it.hasGlobalPreferredIpv6 }
            )
            .toList()

        networkCandidates.firstOrNull { it.hasIpv6DefaultRoute }
            ?.let { preferredNetwork ->
                resolveNetworkIpv6Address(preferredNetwork.network)?.let { return@withContext it }
            }

        networkCandidates.asSequence()
            .mapNotNull(NetworkIpv6Candidate::pickBestIpv6Address)
            .firstOrNull()
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

private data class NetworkIpv6Candidate(
    val network: Network,
    val isActive: Boolean,
    val isValidated: Boolean,
    val hasIpv6DefaultRoute: Boolean,
    val addresses: List<LinkAddress>,
) {
    val hasGlobalPreferredIpv6: Boolean
        get() = addresses.any(LinkAddress::isGlobalPreferredIpv6)

    fun pickBestIpv6Address(): Inet6Address? {
        return addresses.firstOrNull(LinkAddress::isGlobalPreferredIpv6)
            ?.address as? Inet6Address
            ?: addresses.firstOrNull { (it.address as? Inet6Address)?.isGlobalLanAddress() == true }
                ?.address as? Inet6Address
            ?: addresses.firstOrNull { (it.address as? Inet6Address)?.isUniqueLocalAddress() == true }
                ?.address as? Inet6Address
    }
}

private fun Network.toIpv6Candidate(
    cm: ConnectivityManager,
    activeNetwork: Network?,
): NetworkIpv6Candidate? {
    val caps = cm.getNetworkCapabilities(this) ?: return null
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    ) {
        return null
    }

    val linkProperties = cm.getLinkProperties(this) ?: return null
    val addresses = linkProperties.linkAddresses
        .asSequence()
        .filter(LinkAddress::isUsableIpv6Candidate)
        .toList()
    if (addresses.isEmpty()) {
        return null
    }

    return NetworkIpv6Candidate(
        network = this,
        isActive = this == activeNetwork,
        isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        hasIpv6DefaultRoute = linkProperties.routes.any(RouteInfo::isIpv6DefaultRoute),
        addresses = addresses,
    )
}

private fun resolveNetworkIpv6Address(network: Network): Inet6Address? {
    val remoteAddress = runCatching {
        InetAddress.getByName(IPV6_ROUTE_PROBE_ADDRESS) as? Inet6Address
    }.getOrNull() ?: return null

    return runCatching {
        DatagramSocket().use { socket ->
            network.bindSocket(socket)
            socket.connect(InetSocketAddress(remoteAddress, IPV6_ROUTE_PROBE_PORT))
            socket.localAddress as? Inet6Address
        }
    }.getOrNull()?.takeIf { address ->
        !address.isLoopbackAddress &&
            !address.isLinkLocalAddress &&
            !address.isAnyLocalAddress &&
            !address.isMulticastAddress
    }
}

private fun LinkAddress.isUsableIpv6Candidate(): Boolean {
    val address = this.address as? Inet6Address ?: return false
    return !address.isLoopbackAddress &&
        !address.isLinkLocalAddress &&
        !address.isAnyLocalAddress &&
        !address.isMulticastAddress
}

private fun LinkAddress.isGlobalPreferredIpv6(): Boolean {
    val address = this.address as? Inet6Address ?: return false
    val addressFlags = flags
    val isPreferred = (addressFlags and OsConstants.IFA_F_DEPRECATED) == 0 &&
        (addressFlags and OsConstants.IFA_F_TENTATIVE) == 0 &&
        (addressFlags and OsConstants.IFA_F_DADFAILED) == 0
    return address.isGlobalLanAddress() && isPreferred
}

private fun Inet6Address.isGlobalLanAddress(): Boolean {
    return !isUniqueLocalAddress() && !isSiteLocalAddress
}

private fun Inet6Address.isUniqueLocalAddress(): Boolean {
    val firstByte = address.firstOrNull()?.toInt() ?: return false
    return (firstByte and 0xFE) == 0xFC
}

private fun RouteInfo.isIpv6DefaultRoute(): Boolean {
    return isDefaultRoute && (destination?.address as? Inet6Address) != null
}
