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
import java.util.LinkedHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
    private val sessionLock = Any()
    private val openSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "baby-signal-client").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val rateLimiters = object : LinkedHashMap<String, PinRateLimiter>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PinRateLimiter>?): Boolean =
            size > 128
    }
    @Volatile private var server: ServerSocket? = null
    @Volatile private var activePipe: SignalPipe? = null
    private var activeClientAddress: String? = null

    private fun limiterFor(socket: Socket): PinRateLimiter {
        val address = socket.inetAddress.hostAddress.orEmpty()
        return synchronized(rateLimiters) {
            rateLimiters.getOrPut(address) { PinRateLimiter() }
        }
    }

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
                    openSockets.add(client)
                    try {
                        workers.execute {
                            try { handleClient(client) }
                            finally { openSockets.remove(client) }
                        }
                    } catch (_: RejectedExecutionException) {
                        openSockets.remove(client)
                        client.close()
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) onError("Не удалось открыть порт локальной сети: ${error.message}")
        } finally {
            server = null
        }
    }

    private fun handleClient(socket: Socket) {
        val limiter = limiterFor(socket)
        var pipe: SignalPipe? = null
        var authorized = false
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 5_000
            pipe = SignalPipe(socket)

            if (!limiter.mayTry(SystemClock.elapsedRealtime())) {
                pipe.send(WireMessage.Reject)
                return
            }

            val clientNonce = (pipe.read() as? WireMessage.Hello)?.value
                ?: throw IOException("Client nonce required")
            val serverNonce = PairingCode.newChallenge()
            if (!pipe.send(WireMessage.Challenge(serverNonce))) return
            pipe.enableSecure(pin, "$clientNonce.$serverNonce", isServer = true)

            if (pipe.read() != WireMessage.Proof) {
                limiter.failed(SystemClock.elapsedRealtime())
                return
            }
            limiter.succeeded()
            val clientAddress = socket.inetAddress.hostAddress.orEmpty()
            val accepted = synchronized(sessionLock) {
                if (!running.get()) false
                else if (activePipe != null && activeClientAddress != clientAddress) {
                    pipe.send(WireMessage.Busy)
                    false
                } else if (pipe.send(WireMessage.Accept)) {
                    val previous = activePipe
                    activePipe = pipe
                    activeClientAddress = clientAddress
                    authorized = true
                    previous?.close()
                    true
                } else false
            }
            if (!accepted) return
            socket.soTimeout = 14_000
            onConnected(pipe, clientAddress)
            while (running.get()) {
                val message = pipe.read() ?: break
                if (message != WireMessage.Proof &&
                    message !is WireMessage.Challenge &&
                    message !is WireMessage.Hello
                ) onMessage(message)
            }
        } catch (_: Exception) {
            if (!authorized && running.get()) limiter.failed(SystemClock.elapsedRealtime())
        } finally {
            pipe?.close()
            if (authorized) {
                val wasActive = synchronized(sessionLock) {
                    if (activePipe === pipe) {
                        activePipe = null
                        activeClientAddress = null
                        true
                    } else false
                }
                if (wasActive) onDisconnected()
            }
        }
    }

    fun stop() {
        running.set(false)
        server?.let { runCatching { it.close() } }
        synchronized(sessionLock) {
            activePipe?.close()
            activePipe = null
            activeClientAddress = null
        }
        openSockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }
}
