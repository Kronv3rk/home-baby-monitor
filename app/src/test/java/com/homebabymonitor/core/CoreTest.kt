package com.homebabymonitor.core

import org.junit.Assert.*
import org.junit.Test
import com.homebabymonitor.net.SignalPipe
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class CoreTest {
    @Test fun localSocketExchangesSignalling() {
        val code = PairingCode.generate()
        ServerSocket(0).use { server ->
            val worker = thread {
                server.accept().use { client ->
                    val pipe = SignalPipe(client)
                    val hello = (pipe.read() as WireMessage.Hello).value
                    val challenge = PairingCode.newChallenge()
                    assertTrue(pipe.send(WireMessage.Challenge(challenge)))
                    pipe.enableSecure(code, "$hello.$challenge", true)
                    assertEquals(WireMessage.Proof, pipe.read())
                    assertTrue(pipe.send(WireMessage.Accept))
                }
            }
            Socket("127.0.0.1", server.localPort).use { client ->
                val pipe = SignalPipe(client)
                val hello = PairingCode.newChallenge()
                assertTrue(pipe.send(WireMessage.Hello(hello)))
                val challenge = (pipe.read() as WireMessage.Challenge).value
                pipe.enableSecure(code, "$hello.$challenge", false)
                assertTrue(pipe.send(WireMessage.Proof))
                assertEquals(WireMessage.Accept, pipe.read())
            }
            worker.join(2000)
            assertFalse(worker.isAlive)
        }
    }

    @Test fun roundTripSignalling() {
        for (message in listOf(
            WireMessage.Challenge(PairingCode.newChallenge()), WireMessage.Proof, WireMessage.Accept, WireMessage.Reject, WireMessage.Ping,
            WireMessage.Offer("v=0\r\na=group:BUNDLE 0\r\n"),
            WireMessage.Answer("v=0\r\n"),
            WireMessage.Ice("0", 0, "candidate:1 1 udp 123 192.168.1.2 5000 typ host")
        )) assertEquals(message, WireProtocol.decode(WireProtocol.encode(message)))
    }

    @Test fun rejectMalformedMessages() {
        for (line in listOf("HELLO 123", "HELLO 12345x", "ICE YQ notAnIndex Zg", "\nNO", "", "ICE YQ 99 Zg", "ICE not-base64 0 Zg"))
            assertNull(WireProtocol.decode(line))
    }

    @Test fun soundAlarmRequiresSustainedEnergyAndCooldown() {
        val detector = AudioTrigger(thresholdRms = 1500.0, minimumLoudMillis = 300, cooldownMillis = 4000)
        val loud = ByteArray(960) { if (it % 2 == 0) 0x40 else 0x1f }
        assertFalse(detector.feed(ByteArray(960), 48000, 1, 0))
        var alarms = 0
        for (t in 1..50) if (detector.feed(loud, 48000, 1, t * 10L)) alarms++
        assertEquals(1, alarms)
    }

    @Test fun onlyLocalIpv4TargetsAreAccepted() {
        for (ip in listOf("192.168.1.6", "10.2.0.45", "172.16.5.4")) assertTrue(LocalAddress.isPrivateIpv4(ip))
        for (ip in listOf("8.8.8.8", "172.32.0.1", "example.com", "127.0.0.1", "192.168.001.2")) assertFalse(LocalAddress.isPrivateIpv4(ip))
    }

    @Test fun pinBruteForceGetsTemporaryBlock() {
        val guard = PinRateLimiter(maxFailures = 5, windowMillis = 60000)
        for (attempt in 0..4) { assertTrue(guard.mayTry(attempt.toLong())); guard.failed(attempt.toLong()) }
        assertFalse(guard.mayTry(100))
        assertTrue(guard.mayTry(60100))
    }

    @Test fun linkTimeoutIsNotRaisedBeforeInitialConnection() {
        val health = LinkHealth(7000)
        assertFalse(health.isStale(123456))
        health.connectedAt(100000)
        assertFalse(health.isStale(107000))
        assertTrue(health.isStale(107001))
        health.heartbeat(107002)
        assertFalse(health.isStale(107003))
    }
    @Test fun initialConnectionTimeoutAndFrozenVideoAreIndependentOfHeartbeat() {
        val watchdog = SessionWatchdog(startupTimeoutMillis = 30000, heartbeatTimeoutMillis = 8000, videoTimeoutMillis = 12000)
        watchdog.started(1000)
        assertNull(watchdog.problem(30999))
        assertEquals(SessionWatchdog.Problem.NEVER_CONNECTED, watchdog.problem(31000))
        watchdog.connected(32000)
        watchdog.heartbeat(42000)
        assertEquals(SessionWatchdog.Problem.VIDEO_MISSING, watchdog.problem(44001))
        watchdog.videoFrame(45000)
        watchdog.heartbeat(45000)
        assertNull(watchdog.problem(45001))
        watchdog.videoFrame(54000)
        assertEquals(SessionWatchdog.Problem.HEARTBEAT_MISSING, watchdog.problem(54001))
        watchdog.connected(55000)
        assertNull(watchdog.problem(56000))
    }

}
