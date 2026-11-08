package com.homebabymonitor.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class BabyBeacon(private val bindIp: String) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var socket: DatagramSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({
            try {
                DatagramSocket(null).use { sock ->
                    sock.reuseAddress = true
                    sock.bind(InetSocketAddress(bindIp, 0))
                    socket = sock
                    sock.broadcast = true
                    val content = LocalPorts.BEACON.toByteArray(StandardCharsets.US_ASCII)
                    val destination = InetSocketAddress("255.255.255.255", LocalPorts.DISCOVERY)
                    while (running.get()) {
                        try { sock.send(DatagramPacket(content, content.size, destination)) }
                        catch (e: Exception) { Log.w("BabyBeacon", "Broadcast unavailable", e) }
                        Thread.sleep(1300)
                    }
                }
            } catch (e: Exception) {
                if (running.get()) Log.w("BabyBeacon", "Discovery stopped", e)
            }
        }, "baby-beacon").also { it.isDaemon = true; it.start() }
    }
    fun stop() { running.set(false); socket?.close(); thread?.interrupt() }
}
