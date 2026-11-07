import com.homebabymonitor.core.PairingCode
import com.homebabymonitor.core.SecureSignalCipher
import com.homebabymonitor.core.WireMessage
import com.homebabymonitor.core.WireProtocol
import com.homebabymonitor.net.SignalPipe
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

fun main() {
    val code = PairingCode.generate()
    check(PairingCode.normalize(code) != null)
    check(PairingCode.normalize("0123-4567-89AB-CDEF-GHJK") == "0123456789ABCDEFGHJK")
    check(PairingCode.normalize("0123-4567-89AB-CDEF-GHJ!") == null)
    check(WireProtocol.decode("HELLO 123456") == null) { "Old cleartext pairing must be rejected" }
    val clientNonce = PairingCode.newChallenge()
    val challenge = PairingCode.newChallenge()
    val session = "$clientNonce.$challenge"
    check(WireProtocol.decode(WireProtocol.encode(WireMessage.Challenge(challenge))) == WireMessage.Challenge(challenge))
    val baby = SecureSignalCipher(code, session, isServer = true)
    val parent = SecureSignalCipher(code, session, isServer = false)
    check(baby.open(parent.seal("AUTH")) == "AUTH")
    check(parent.open(baby.seal("OK")) == "OK")
    val enc = parent.seal("OFFER a\nb")
    check(baby.open(enc) == "OFFER a\nb")
    check(runCatching { baby.open(enc) }.isFailure) { "Encrypted message replay must fail" }
    val corrupt = parent.seal("PING").dropLast(2) + "AA"
    check(runCatching { baby.open(corrupt) }.isFailure) { "Corrupt ciphertext must fail" }
    val wrong = SecureSignalCipher(PairingCode.generate(), session, isServer = true)
    val hello = SecureSignalCipher(code, session, isServer = false).seal("AUTH")
    check(runCatching { wrong.open(hello) }.isFailure)
    val other = SecureSignalCipher(code, "${PairingCode.newChallenge()}.$challenge", isServer = true)
    check(runCatching { other.open(hello) }.isFailure) { "Challenge must bind the session" }
    ServerSocket(0).use { listener ->
        val worker = thread {
            listener.accept().use { sock ->
                val pipe = SignalPipe(sock)
                val clientNonce = (pipe.read() as WireMessage.Hello).value
                val serverNonce = PairingCode.newChallenge()
                check(pipe.send(WireMessage.Challenge(serverNonce)))
                pipe.enableSecure(code, "$clientNonce.$serverNonce", isServer = true)
                check(pipe.read() == WireMessage.Proof)
                check(pipe.send(WireMessage.Accept))
                check(pipe.read() == WireMessage.Ping)
            }
        }
        Socket("127.0.0.1", listener.localPort).use { sock ->
            val pipe = SignalPipe(sock)
            val clientNonce = PairingCode.newChallenge()
            check(pipe.send(WireMessage.Hello(clientNonce)))
            val serverNonce = (pipe.read() as WireMessage.Challenge).value
            pipe.enableSecure(code, "$clientNonce.$serverNonce", isServer = false)
            check(pipe.send(WireMessage.Proof))
            check(pipe.read() == WireMessage.Accept)
            check(pipe.send(WireMessage.Ping))
        }
        worker.join(5000)
        check(!worker.isAlive)
    }
    println("SecureChannelSmokeTest OK")
}
