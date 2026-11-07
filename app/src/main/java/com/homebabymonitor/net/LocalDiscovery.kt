package com.homebabymonitor.net

import com.homebabymonitor.core.LocalAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

fun discoverBaby(timeoutMillis: Int = 2400): String? {
    val socket = DatagramSocket(null)
    return try {
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(LocalPorts.DISCOVERY))
        socket.soTimeout = timeoutMillis
        val bytes = ByteArray(96)
        val packet = DatagramPacket(bytes, bytes.size)
        socket.receive(packet)
        val message = String(packet.data, 0, packet.length, StandardCharsets.US_ASCII)
        val sender = packet.address
        if (message == LocalPorts.BEACON && sender is Inet4Address &&
            LocalAddress.isPrivateIpv4(sender.hostAddress.orEmpty())
        ) sender.hostAddress else null
    } catch (_: Exception) { null }
    finally { socket.close() }
}
