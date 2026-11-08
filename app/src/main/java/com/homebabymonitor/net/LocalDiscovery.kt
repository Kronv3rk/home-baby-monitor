package com.homebabymonitor.net

import com.homebabymonitor.core.LocalAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

internal fun isCameraBeacon(message: String, sourceIp: String): Boolean =
    message == LocalPorts.BEACON && LocalAddress.isPrivateIpv4(sourceIp)

fun discoverBaby(timeoutMillis: Int = 2400): String? {
    if (timeoutMillis <= 0) return null
    val socket = DatagramSocket(null)
    try {
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(LocalPorts.DISCOVERY))
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return null
            socket.soTimeout = TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1).toInt()
            val packet = DatagramPacket(ByteArray(96), 96)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                return null
            }
            val sender = packet.address
            if (sender is Inet4Address &&
                isCameraBeacon(
                    String(packet.data, packet.offset, packet.length, StandardCharsets.US_ASCII),
                    sender.hostAddress.orEmpty()
                )
            ) return sender.hostAddress
        }
    } catch (_: Exception) {
        return null
    } finally {
        socket.close()
    }
}
