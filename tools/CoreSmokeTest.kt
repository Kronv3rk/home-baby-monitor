import com.homebabymonitor.core.PairingCode
import com.homebabymonitor.core.AudioTrigger
import com.homebabymonitor.core.PinRateLimiter
import com.homebabymonitor.core.LocalAddress
import com.homebabymonitor.core.LinkHealth
import com.homebabymonitor.core.WireMessage
import com.homebabymonitor.core.WireProtocol

fun main() {
    val samples = ByteArray(960) { i -> if (i % 2 == 0) 0x40 else 0x1f }
    val quiet = ByteArray(960)
    val detector = AudioTrigger(thresholdRms = 1700.0, minimumLoudMillis = 300, cooldownMillis = 4000)
    check(!detector.feed(quiet, 48000, 1, 0L))
    var raised = false
    for (i in 0..40) raised = detector.feed(samples, 48000, 1, i * 10L) || raised
    check(raised) { "Persistent loud sound must trigger" }
    check(!detector.feed(samples, 48000, 1, 500L)) { "Alarm must cool down" }
    val messages = listOf(
        WireMessage.Hello(PairingCode.newChallenge()), WireMessage.Challenge(PairingCode.newChallenge()), WireMessage.Proof, WireMessage.Accept, WireMessage.Ping,
        WireMessage.Reject, WireMessage.Offer("v=0\\r\\n"),
        WireMessage.Answer("a=candidate\\r\\n"), WireMessage.Ice("audio", 0, "candidate 0"))
    for (message in messages) {
        check(WireProtocol.decode(WireProtocol.encode(message)) == message) { "Wire round trip: $message" }
    }
    check(WireProtocol.decode("HELLO abcd") == null)
    check(WireProtocol.decode("ICE badIndex broken nope") == null)
    check(LocalAddress.isPrivateIpv4("192.168.1.2"))
    check(LocalAddress.isPrivateIpv4("10.0.0.12"))
    check(LocalAddress.isPrivateIpv4("172.31.200.2"))
    check(!LocalAddress.isPrivateIpv4("172.32.0.1"))
    check(!LocalAddress.isPrivateIpv4("8.8.8.8"))
    check(!LocalAddress.isPrivateIpv4("localhost"))
    val limiter = PinRateLimiter(maxFailures = 5, windowMillis = 60_000)
    for (n in 1..5) { check(limiter.mayTry(n.toLong())); limiter.failed(n.toLong()) }
    check(!limiter.mayTry(100L))
    check(limiter.mayTry(60_100L))
    val health = LinkHealth(heartbeatTimeoutMillis = 7000)
    check(!health.isStale(100000L))
    health.connectedAt(100000L)
    check(!health.isStale(106999L))
    check(health.isStale(107001L))
    health.heartbeat(107010L)
    check(!health.isStale(107020L))
    println("CoreSmokeTest: ALL PASSED")
}
