import com.homebabymonitor.core.PairingCode
import com.homebabymonitor.core.WireMessage
import com.homebabymonitor.net.SignalPipe
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

fun main() {
    val code = PairingCode.generate()
    ServerSocket(0).use { server ->
        val backend = thread {
            server.accept().use { client ->
                val pipe = SignalPipe(client)
                val hello = (pipe.read() as WireMessage.Hello).value
                val challenge = PairingCode.newChallenge()
                check(pipe.send(WireMessage.Challenge(challenge)))
                pipe.enableSecure(code, "$hello.$challenge", true)
                check(pipe.read() == WireMessage.Proof)
                check(pipe.send(WireMessage.Accept))
                check(pipe.read() == WireMessage.Offer("sdp\r\nvalue"))
            }
        }
        Socket("127.0.0.1", server.localPort).use { client ->
            val pipe = SignalPipe(client)
            val hello = PairingCode.newChallenge()
            check(pipe.send(WireMessage.Hello(hello)))
            val challenge = (pipe.read() as WireMessage.Challenge).value
            pipe.enableSecure(code, "$hello.$challenge", false)
            check(pipe.send(WireMessage.Proof))
            check(pipe.read() == WireMessage.Accept)
            check(pipe.send(WireMessage.Offer("sdp\r\nvalue")))
        }
        backend.join(2000)
        check(!backend.isAlive)
    }
    println("LocalSocketSmokeTest: ALL PASSED")
}
