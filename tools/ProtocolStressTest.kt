import com.homebabymonitor.core.WireProtocol
import com.homebabymonitor.core.WireMessage
import com.homebabymonitor.net.SignalPipe
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.random.Random

fun main() {
    val rng = Random(20261107)
    repeat(2500) {
        val s = (1..rng.nextInt(1, 512)).map { (rng.nextInt(32, 127)).toChar() }.joinToString("")
        val message = when (it % 3) {
            0 -> WireMessage.Offer(s)
            1 -> WireMessage.Answer(s)
            else -> WireMessage.Ice("0", it % 3, s)
        }
        check(WireProtocol.decode(WireProtocol.encode(message)) == message)
    }
    check(WireProtocol.decode("OFFER " + "a".repeat(WireProtocol.MAX_LINE)) == null)
    check(WireProtocol.decode("ICE not-base64 0 Zg") == null)
    ServerSocket(0).use { server ->
        val worker = thread {
            server.accept().use { connection ->
                val pipe = SignalPipe(connection)
                repeat(500) { i ->
                    check(pipe.read() == WireMessage.Ping) { "Sequence lost at $i" }
                }
            }
        }
        Socket("127.0.0.1", server.localPort).use { socket ->
            val pipe = SignalPipe(socket)
            repeat(500) { check(pipe.send(WireMessage.Ping)) }
        }
        worker.join(4000)
        check(!worker.isAlive) { "Network loop did not drain" }
    }
    println("ProtocolStressTest: ALL PASSED")
}
