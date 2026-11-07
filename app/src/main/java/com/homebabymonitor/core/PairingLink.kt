package com.homebabymonitor.core

/** Data shown on the child's phone; the pairing secret never crosses the network as plaintext. */
data class PairingTarget(val ip: String, val code: String)

object PairingLink {
    private const val PREFIX = "hbm://pair?"
    fun create(ip: String, code: String): String {
        require(LocalAddress.isPrivateIpv4(ip))
        val normalized = requireNotNull(PairingCode.normalize(code))
        return "${PREFIX}v=1&ip=$ip&key=$normalized"
    }

    fun parse(text: String?): PairingTarget? {
        if (text == null || text.length > 160 || !text.startsWith(PREFIX)) return null
        val pairs = text.removePrefix(PREFIX).split('&')
        if (pairs.size != 3) return null
        val fields = pairs.map { it.split('=', limit = 2) }
        if (fields.any { it.size != 2 } || fields.map { it[0] }.toSet() != setOf("v", "ip", "key")) return null
        val params = fields.associate { it[0] to it[1] }
        if (params["v"] != "1") return null
        val ip = params["ip"]?.takeIf(LocalAddress::isPrivateIpv4) ?: return null
        val code = params["key"]?.let(PairingCode::normalize) ?: return null
        return PairingTarget(ip, code)
    }
}
