import com.homebabymonitor.core.PairingCode
import com.homebabymonitor.core.WireMessage
import com.homebabymonitor.net.BabySignalServer
import com.homebabymonitor.net.ParentConnector
import com.homebabymonitor.net.SignalPipe
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private fun connect(): Socket {
    repeat(100) {
        try {
            return Socket().apply { connect(InetSocketAddress("127.0.0.1", 43182), 150) ; soTimeout = 2500 }
        } catch (_: Exception) { Thread.sleep(15) }
    }
    error("BabySignalServer did not bind")
}

fun main() {
    val code = PairingCode.generate()
    val accepted = CountDownLatch(1)
    val forwarded = CountDownLatch(1)
    val disconnected = CountDownLatch(1)
    val errors = mutableListOf<String>()
    val baby = BabySignalServer("127.0.0.1", code, { _, _ -> accepted.countDown() }, {
        if (it == WireMessage.Ping) forwarded.countDown()
    }, { disconnected.countDown() }, { synchronized(errors) { errors.add(it) } })
    baby.start()
    try {
        connect().use { socket ->
            val pipe = SignalPipe(socket)
            val hello = PairingCode.newChallenge()
            check(pipe.send(WireMessage.Hello(hello)))
            val challenge = (pipe.read() as WireMessage.Challenge).value
            pipe.enableSecure(PairingCode.generate(), "$hello.$challenge", false)
            check(pipe.send(WireMessage.Proof))
            check(runCatching { pipe.read() }.getOrNull() != WireMessage.Accept) { "Wrong secret must be rejected" }
        }
        connect().use { socket ->
            val pipe = SignalPipe(socket)
            val hello = PairingCode.newChallenge()
            check(pipe.send(WireMessage.Hello(hello)))
            val challenge = (pipe.read() as WireMessage.Challenge).value
            pipe.enableSecure(code, "$hello.$challenge", false)
            check(pipe.send(WireMessage.Proof))
            check(pipe.read() == WireMessage.Accept) { "Correct PIN must be accepted" }
            check(accepted.await(2, TimeUnit.SECONDS))
            check(pipe.send(WireMessage.Ping))
            check(forwarded.await(2, TimeUnit.SECONDS))
        }
        check(disconnected.await(2, TimeUnit.SECONDS))
        check(errors.isEmpty()) { "Signal server crashed: $errors" }

        val parentAccepted = CountDownLatch(1)
        val parent = ParentConnector("127.0.0.1", code,
            { _, _ -> parentAccepted.countDown() }, {}, {}, {})
        parent.start()
        check(parentAccepted.await(4, TimeUnit.SECONDS)) { "Parent connector did not authenticate" }
        parent.stop()
    } finally { baby.stop() }
    println("ServerIntegrationSmokeTest: ALL PASSED")
}
