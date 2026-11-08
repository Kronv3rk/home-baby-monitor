package com.homebabymonitor.core

data class WifiCandidate(
    val isWifi: Boolean,
    val hasInternet: Boolean,
    val ipv4Addresses: List<String>
)

object WifiEndpointSelector {
    fun choose(candidates: Iterable<WifiCandidate>): String? = candidates.asSequence()
        .filter { it.isWifi }
        .flatMap { it.ipv4Addresses.asSequence() }
        .firstOrNull { LocalAddress.isPrivateIpv4(it) && !it.startsWith("169.254.") }
}
