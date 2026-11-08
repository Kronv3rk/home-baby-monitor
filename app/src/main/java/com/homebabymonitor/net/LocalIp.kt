package com.homebabymonitor.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.homebabymonitor.core.WifiCandidate
import com.homebabymonitor.core.WifiEndpointSelector
import java.net.Inet4Address
import java.net.NetworkInterface

data class WifiRoute(val address: String, val network: Network?)

object LocalIp {
    fun current(context: Context): String = route(context)?.address ?: "—"

    fun route(context: Context): WifiRoute? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            for (network in manager.allNetworks) {
                val capabilities = manager.getNetworkCapabilities(network) ?: continue
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val addresses = manager.getLinkProperties(network)?.linkAddresses.orEmpty()
                    .mapNotNull { it.address as? Inet4Address }
                    .filterNot { it.isLinkLocalAddress || it.isLoopbackAddress }
                    .map { it.hostAddress.orEmpty() }
                val address = WifiEndpointSelector.choose(listOf(WifiCandidate(
                    isWifi = true,
                    hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    ipv4Addresses = addresses
                )))
                if (address != null) return WifiRoute(address, network)
            }
        } catch (_: RuntimeException) {
            // Some Android variants do not expose the Wi-Fi network immediately.
        }

        // NetworkInterface is a fallback for local-only Wi-Fi omitted from allNetworks.
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val item = interfaces.nextElement()
                val name = item.name.lowercase()
                if (!item.isUp || item.isLoopback ||
                    !(name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("wifi"))) continue
                val addresses = mutableListOf<String>()
                val ips = item.inetAddresses
                while (ips.hasMoreElements()) {
                    val ip = ips.nextElement()
                    if (ip is Inet4Address && !ip.isLinkLocalAddress && !ip.isLoopbackAddress)
                        addresses.add(ip.hostAddress.orEmpty())
                }
                val address = WifiEndpointSelector.choose(listOf(WifiCandidate(true, false, addresses)))
                if (address != null) return WifiRoute(address, null)
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
