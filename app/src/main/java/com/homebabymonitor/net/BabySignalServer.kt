package com.homebabymonitor.net

import android.os.SystemClock
import com.homebabymonitor.core.PairingCode
import com.homebabymonitor.core.PinRateLimiter
import com.homebabymonitor.core.WireMessage
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

class BabySignalServer(
    private val bindIp: String,
    private val pin: String,
    private val onConnected: (SignalPipe, String) -> Unit,
    private val onMessage: (WireMessage) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val pinLimiter = PinRateLimiter()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var activePipe: SignalPipe? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread(::serve, "baby-signal-server").apply {
            isDaemon = true
            start()
        }
    }

    private fun serve() {
        try {
            ServerSocket().use { listener ->
                listener.reuseAddress = true
                listener.bind(InetSocketAddress(bindIp, LocalPorts.SIGNAL))
                server = listener
                while (running.get()) {
                    val client = try {
                        listener.accept()
                    } catch (_: SocketException) {
                        break
                    }
                    handleClient(client)
                }
            }
        } catch (error: Exception) {
            if (running.get()) onError("Не удалось открыть порт локальной сети: ${error.message}")
        } finally {
            server = null
        }
    }

    private fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = 5_000
        val pipe = try {
            SignalPipe(socket)
        } catch (_: Exception) {
            socket.close()
            return
        }
        var authorized = false
        try {
            if (!pinLimiter.mayTry(SystemClock.elapsedRealtime())) {
                pipe.send(WireMessage.Reject)
                return
            }
            val clientNonce = (pipe.read() as? WireMessage.Hello)?.value
                ?: throw IOException("Client nonce required")
            val serverNonce = PairingCode.newChallenge()
            if (!pipe.send(WireMessage.Challenge(serverNonce))) return
            pipe.enableSecure(pin, "$clientNonce.$serverNonce", isServer = true)

            if (pipe.read() != WireMessage.Proof) {
                pinLimiter.failed(SystemClock.elapsedRealtime())
                return
            }
            if (!pipe.send(WireMessage.Accept)) return

            authorized = true
            activePipe?.close()
            activePipe = pipe
            socket.soTimeout = 14_000
            onConnected(pipe, socket.inetAddress.hostAddress.orEmpty())
            while (running.get()) {
                val message = pipe.read() ?: break
                if (message != WireMessage.Proof &&
                    message !is WireMessage.Challenge &&
                    message !is WireMessage.Hello
                ) {
                    onMessage(message)
                }
            }
        } catch (_: Exception) {
            if (!authorized) pinLimiter.failed(SystemClock.elapsedRealtime())
        } finally {
            pipe.close()
            if (authorized && activePipe === pipe) {
                activePipe = null
                onDisconnected()
            }
        }
    }

    fun stop() {
        running.set(false)
        activePipe?.close()
        try {
            server?.close()
        } catch (_: Exception) {
            // Closing the listener is best-effort during shutdown.
        }
    }
}
