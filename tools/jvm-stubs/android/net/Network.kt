package android.net
import java.net.InetAddress
class Network
class LinkAddress(val address: InetAddress)
class LinkProperties(val linkAddresses: List<LinkAddress> = emptyList())
class NetworkCapabilities {
    companion object { const val TRANSPORT_WIFI = 1; const val NET_CAPABILITY_INTERNET = 12 }
    fun hasTransport(transport: Int) = false
    fun hasCapability(capability: Int) = false
}
class ConnectivityManager {
    val allNetworks: Array<Network> = emptyArray()
    fun getNetworkCapabilities(network: Network): NetworkCapabilities? = null
    fun getLinkProperties(network: Network): LinkProperties? = null
}
