package com.homebabymonitor.net

import com.homebabymonitor.core.PairingCode
import com.homebabymonitor.core.WireMessage
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class ParentConnector(
    private val ipOverride: String,
    private val pin: String,
    private val onConnected: (SignalPipe, String) -> Unit,
    private val onMessage: (WireMessage) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onPairingFailed: () -> Unit = {},
    private val localBindIp: String? = null,
    private val onBusy: () -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    @Volatile private var activePipe: SignalPipe? = null
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread(::connectionLoop, "parent-connect-loop").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun connectionLoop() {
        var lastIp = ipOverride.trim()
        while (running.get()) {
            try {
                if (lastIp.isBlank()) {
                    onStatus("Ищем камеру в домашней сети…")
                    lastIp = discoverBaby().orEmpty()
                    if (lastIp.isBlank()) continue
                }
                connect(lastIp)
            } catch (_: Exception) {
                if (running.get()) onStatus("Связь потеряна. Переподключаемся…")
            } finally {
                activePipe = null
            }
            if (ipOverride.isBlank()) lastIp = ""
            try {
                Thread.sleep(1_500)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun connect(ip: String) {
        var connected = false
        try {
            onStatus("Подключаемся к $ip…")
            Socket().use { socket ->
                if (localBindIp != null) socket.bind(InetSocketAddress(localBindIp, 0))
                socket.connect(InetSocketAddress(ip, LocalPorts.SIGNAL), 2_700)
                socket.tcpNoDelay = true
                socket.soTimeout = 5_000
                val pipe = SignalPipe(socket)
                activePipe = pipe

                val clientNonce = PairingCode.newChallenge()
                if (!pipe.send(WireMessage.Hello(clientNonce)))
                    throw IOException("Не удалось отправить начальный запрос")
                val serverNonce = (pipe.read() as? WireMessage.Challenge)?.value
                    ?: throw IOException("Камера не ответила на запрос подключения")
                pipe.enableSecure(pin, "$clientNonce.$serverNonce", isServer = false)
                if (!pipe.send(WireMessage.Proof))
                    throw IOException("Не удалось подтвердить соединение")
                when (runCatching { pipe.read() }.getOrNull()) {
                    WireMessage.Accept -> Unit
                    WireMessage.Busy -> {
                        onStatus("Камера уже подключена к другому телефону")
                        onBusy()
                        running.set(false)
                        return
                    }
                    else -> {
                        onStatus("Код подключения не подошёл")
                        onPairingFailed()
                        running.set(false)
                        return
                    }
                }
                socket.soTimeout = 14_000
                connected = true
                onConnected(pipe, ip)
                while (running.get()) {
                    val message = pipe.read() ?: break
                    if (message != WireMessage.Proof &&
                        message !is WireMessage.Challenge &&
                        message !is WireMessage.Hello
                    ) onMessage(message)
                }
            }
        } finally {
            if (connected) onDisconnected()
        }
    }

    fun forceReconnect() {
        activePipe?.close()
    }

    fun stop() {
        running.set(false)
        activePipe?.close()
        thread?.interrupt()
    }
}
