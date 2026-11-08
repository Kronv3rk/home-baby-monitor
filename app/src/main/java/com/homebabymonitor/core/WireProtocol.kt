package com.homebabymonitor.core

import java.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** One bounded ASCII control line per message. SDP and ICE are base64url-encoded UTF-8. */
sealed class WireMessage {
    data class Hello(val value: String) : WireMessage()
    data class Challenge(val value: String) : WireMessage()
    data object Proof : WireMessage()
    data object Accept : WireMessage()
    data object Reject : WireMessage()
    data object Ping : WireMessage()
    data class Offer(val sdp: String) : WireMessage()
    data class Answer(val sdp: String) : WireMessage()
    data class Ice(val mid: String, val index: Int, val candidate: String) : WireMessage()
}

object WireProtocol {
    const val MAX_LINE = 131_072
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private fun b64(value: String): String = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun midCode(value: String): String = if (value.isEmpty()) "~" else b64(value)
    private fun midText(value: String): String = if (value == "~") "" else unb64(value)
    private fun unb64(value: String): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(decoder.decode(value))).toString()

    fun encode(message: WireMessage): String = when (message) {
        is WireMessage.Hello -> "HELLO ${message.value}"
        is WireMessage.Challenge -> "CHALLENGE ${message.value}"
        WireMessage.Proof -> "AUTH"
        WireMessage.Accept -> "OK"
        WireMessage.Reject -> "NO"
        WireMessage.Ping -> "PING"
        is WireMessage.Offer -> "OFFER ${b64(message.sdp)}"
        is WireMessage.Answer -> "ANSWER ${b64(message.sdp)}"
        is WireMessage.Ice -> "ICE ${midCode(message.mid)} ${message.index} ${b64(message.candidate)}"
    }

    fun decode(line: String): WireMessage? {
        if (line.isEmpty() || line.length > MAX_LINE || '\n' in line || '\r' in line) return null
        return try {
            val parts = line.split(' ')
            when {
                parts.size == 2 && parts[0] == "HELLO" && PairingCode.validChallenge(parts[1]) -> WireMessage.Hello(parts[1])
                parts.size == 2 && parts[0] == "CHALLENGE" && PairingCode.validChallenge(parts[1]) -> WireMessage.Challenge(parts[1])
                parts.size == 1 && line == "AUTH" -> WireMessage.Proof
                parts.size == 1 && line == "OK" -> WireMessage.Accept
                parts.size == 1 && line == "NO" -> WireMessage.Reject
                parts.size == 1 && line == "PING" -> WireMessage.Ping
                parts.size == 2 && parts[0] == "OFFER" -> WireMessage.Offer(unb64(parts[1]))
                parts.size == 2 && parts[0] == "ANSWER" -> WireMessage.Answer(unb64(parts[1]))
                parts.size == 4 && parts[0] == "ICE" && parts[2].toIntOrNull() in 0..10 ->
                    WireMessage.Ice(midText(parts[1]), parts[2].toInt(), unb64(parts[3]))
                else -> null
            }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
