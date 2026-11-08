package com.homebabymonitor.core

/** Reject public/hostname targets: this app is intentionally LAN-only. */
object LocalAddress {
    fun isPrivateIpv4(value: String): Boolean {
        val octets = value.split('.')
        if (octets.size != 4) return false
        val bytes = octets.map { part ->
            if (part.length !in 1..3 || (part.length > 1 && part.startsWith('0')) || !part.all(Char::isDigit)) null
            else part.toIntOrNull()?.takeIf { it in 0..255 }
        }
        if (bytes.any { it == null }) return false
        val a = bytes[0]!!
        val b = bytes[1]!!
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }
}
