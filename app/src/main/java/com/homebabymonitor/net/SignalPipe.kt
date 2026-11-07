package com.homebabymonitor.net

import com.homebabymonitor.core.SecureSignalCipher
import com.homebabymonitor.core.WireMessage
import com.homebabymonitor.core.WireProtocol
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets

class SignalPipe(private val socket: Socket) : AutoCloseable {
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
    @Volatile private var secure: SecureSignalCipher? = null

    @Synchronized fun enableSecure(code: String, challenge: String, isServer: Boolean) {
        check(secure == null) { "Already secured" }
        secure = SecureSignalCipher(code, challenge, isServer)
    }

    @Synchronized fun send(message: WireMessage): Boolean = try {
        val plain = WireProtocol.encode(message)
        val text = secure?.seal(plain) ?: plain
        if (text.length > WireProtocol.MAX_LINE) false else {
            writer.write(text)
            writer.newLine()
            writer.flush()
            true
        }
    } catch (_: Exception) {
        false
    }

    fun read(): WireMessage? {
        val line = StringBuilder()
        while (line.length <= WireProtocol.MAX_LINE) {
            val ch = reader.read()
            if (ch < 0) return null
            if (ch == '\n'.code) {
                val text = secure?.open(line.toString()) ?: line.toString()
                return WireProtocol.decode(text)
            }
            if (ch != '\r'.code) line.append(ch.toChar())
        }
        return null
    }

    override fun close() { try { socket.close() } catch (_: Exception) {} }
}

