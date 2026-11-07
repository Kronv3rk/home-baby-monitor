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
    private val onStatus: (String) -> Unit
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
                onDisconnected()
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
        onStatus("Подключаемся к $ip…")
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, LocalPorts.SIGNAL), 2_700)
            socket.tcpNoDelay = true
            socket.soTimeout = 5_000
            val pipe = SignalPipe(socket)
            activePipe = pipe

            val clientNonce = PairingCode.newChallenge()
            if (!pipe.send(WireMessage.Hello(clientNonce))) {
                throw IOException("Не удалось отправить начальный запрос")
            }
            val serverNonce = (pipe.read() as? WireMessage.Challenge)?.value
                ?: throw IOException("От камеры не получен ответ сопряжения")
            pipe.enableSecure(pin, "$clientNonce.$serverNonce", isServer = false)
            if (!pipe.send(WireMessage.Proof)) {
                throw IOException("Не удалось передать подтверждение")
            }

            if (runCatching { pipe.read() }.getOrNull() != WireMessage.Accept) {
                onStatus("Неверный код сопряжения или ошибка защищённого канала")
                running.set(false)
                return
            }
            socket.soTimeout = 14_000
            onConnected(pipe, ip)
            while (running.get()) {
                val message = pipe.read() ?: break
                if (message != WireMessage.Proof &&
                    message !is WireMessage.Challenge &&
                    message !is WireMessage.Hello
                ) {
                    onMessage(message)
                }
            }
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
