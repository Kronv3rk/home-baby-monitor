package com.homebabymonitor.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.util.Log
import android.os.SystemClock
import com.homebabymonitor.core.PairingCode
import com.homebabymonitor.core.PinRateLimiter
import com.homebabymonitor.core.LocalAddress
import com.homebabymonitor.core.WireMessage
import com.homebabymonitor.core.WireProtocol
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

object LocalPorts {
    const val SIGNAL = 43182
    const val DISCOVERY = 43183
    const val BEACON = "HOME_BABY_MONITOR_V1"
}

object LocalIp {
    fun current(context: Context): String {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (network in connectivity.allNetworks) {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val properties: LinkProperties = connectivity.getLinkProperties(network) ?: continue
            for (address in properties.linkAddresses) {
                val ip = address.address
                if (ip is Inet4Address && !ip.isLoopbackAddress && !ip.isLinkLocalAddress) return ip.hostAddress.orEmpty()
            }
        }
        return "—"
    }
}

/** No mDNS, no cloud; UDP broadcasts are restricted to the local subnet. */
class BabyBeacon {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var socket: DatagramSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({
            try {
                DatagramSocket().use { sock ->
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
            } catch (_: Exception) { /* manual IP remains available */ }
        }, "baby-beacon").also { it.isDaemon = true; it.start() }
    }
    fun stop() { running.set(false); socket?.close(); thread?.interrupt() }
}

/** Returns the sender IP, ignoring any untrusted fields in beacon payload. */
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
        if (message == LocalPorts.BEACON && packet.address is Inet4Address && LocalAddress.isPrivateIpv4(packet.address.hostAddress.orEmpty())) packet.address.hostAddress else null
    } catch (_: Exception) { null }
    finally { socket.close() }
}

class BabySignalServer(
    private val bindIp: String,
    private val pin: String,
    private val onConnected: (SignalPipe, String) -> Unit,
    private val onMessage: (WireMessage) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val pinLimiter = PinRateLimiter()
    @Volatile private var activePipe: SignalPipe? = null
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({
            try {
                ServerSocket().use { listener ->
                    listener.reuseAddress = true
                    listener.bind(InetSocketAddress(bindIp, LocalPorts.SIGNAL))
                    server = listener
                    while (running.get()) {
                        val socket = try { listener.accept() } catch (_: SocketException) { break }
                        socket.tcpNoDelay = true
                        socket.soTimeout = 5000
                        val pipe = try { SignalPipe(socket) } catch (_: Exception) { socket.close(); continue }
                        var authorized = false
                        try {
                            if (!pinLimiter.mayTry(SystemClock.elapsedRealtime())) {
                                pipe.send(WireMessage.Reject)
                                continue
                            }
                            // Challenge is public but freshly random. Authentication proof and
                            // subsequent SDP/ICE are encrypted with the out-of-band secret.
                            val clientNonce = (pipe.read() as? WireMessage.Hello)?.value
                                ?: throw java.io.IOException("Client nonce required")
                            val challenge = PairingCode.newChallenge()
                            if (!pipe.send(WireMessage.Challenge(challenge))) continue
                            pipe.enableSecure(pin, "$clientNonce.$challenge", isServer = true)
                            val proof = pipe.read()
                            if (proof == WireMessage.Proof) {
                                if (!pipe.send(WireMessage.Accept)) continue
                                authorized = true
                                activePipe?.close()
                                activePipe = pipe
                                socket.soTimeout = 14000
                                onConnected(pipe, socket.inetAddress.hostAddress.orEmpty())
                                while (running.get()) {
                                    val message = pipe.read() ?: break
                                    if (message != WireMessage.Proof && message !is WireMessage.Challenge && message !is WireMessage.Hello) onMessage(message)
                                }
                            } else {
                                pinLimiter.failed(SystemClock.elapsedRealtime())
                                // Do not reveal a secret-validity oracle in a plaintext reply.
                            }
                        } catch (_: Exception) { // Bad code/ciphertext/timeout/Wi-Fi loss.
                            if (!authorized) pinLimiter.failed(SystemClock.elapsedRealtime())
                        }
                        finally {
                            pipe.close()
                            if (authorized && activePipe === pipe) {
                                activePipe = null
                                onDisconnected()
                            }
                        }
                    }
                }
            } catch (e: Exception) { if (running.get()) onError("Не удалось открыть порт локальной сети: ${e.message}") }
        }, "baby-signal-server").also { it.isDaemon = true; it.start() }
    }
    fun stop() { running.set(false); activePipe?.close(); try { server?.close() } catch (_: Exception) {} }
}

class ParentConnector(
    private val ipOverride: String,
    private val pin: String,
    private val onConnected: (SignalPipe, String) -> Unit,
    private val onMessage: (WireMessage) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    @Volatile private var activePipe: SignalPipe? = null
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({
            var lastIp = ipOverride.trim()
            while (running.get()) {
                try {
                    if (lastIp.isBlank()) {
                        onStatus("Ищем камеру в домашней сети…")
                        lastIp = discoverBaby() ?: ""
                        if (lastIp.isBlank()) continue
                    }
                    onStatus("Подключаемся к $lastIp…")
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(lastIp, LocalPorts.SIGNAL), 2700)
                        socket.tcpNoDelay = true
                        socket.soTimeout = 5000
                        val pipe = SignalPipe(socket)
                        activePipe = pipe
                        val clientNonce = PairingCode.newChallenge()
                        if (!pipe.send(WireMessage.Hello(clientNonce))) throw java.io.IOException("Не удалось отправить начальный запрос")
                        val challenge = (pipe.read() as? WireMessage.Challenge)?.value
                            ?: throw java.io.IOException("От камеры не получен ответ сопряжения")
                        pipe.enableSecure(pin, "$clientNonce.$challenge", isServer = false)
                        if (!pipe.send(WireMessage.Proof)) throw java.io.IOException("Не удалось передать подтверждение")
                        when (runCatching { pipe.read() }.getOrNull()) {
                            WireMessage.Accept -> {
                                socket.soTimeout = 14000
                                onConnected(pipe, lastIp)
                                while (running.get()) {
                                    val message = pipe.read() ?: break
                                    if (message != WireMessage.Proof && message !is WireMessage.Challenge && message !is WireMessage.Hello) onMessage(message)
                                }
                            }
                            else -> { onStatus("Неверный код сопряжения или ошибка защищённого канала"); running.set(false) }
                        }
                    }
                } catch (_: Exception) { if (running.get()) onStatus("Связь потеряна. Переподключаемся…") }
                finally { activePipe = null; onDisconnected() }
                if (ipOverride.isBlank()) lastIp = ""
                try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
            }
        }, "parent-connect-loop").also { it.isDaemon = true; it.start() }
    }
    fun forceReconnect() { activePipe?.close() }
    fun stop() { running.set(false); activePipe?.close(); thread?.interrupt() }
}
