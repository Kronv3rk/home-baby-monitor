package com.homebabymonitor.core

import org.junit.Assert.*
import org.junit.Test
import com.homebabymonitor.net.SignalPipe
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class CoreTest {
    @Test fun failedNativeInitializationMayBeRetried() {
        val gate = InitializationGate()
        var attempts = 0
        assertThrows(IllegalStateException::class.java) {
            gate.ensure { attempts++; throw IllegalStateException("native library unavailable") }
        }
        gate.ensure { attempts++ }
        gate.ensure { attempts++ }
        assertEquals(2, attempts)
    }

    @Test fun nativeCrashCanBeIdentifiedInSharedReport() {
        assertEquals("Сбой нативного компонента", CrashReportFormatter.label(5))
        assertEquals("Необработанная ошибка Android", CrashReportFormatter.label(4))
        assertEquals("Приложение не отвечало", CrashReportFormatter.label(6))
        val result = CrashReportFormatter.summary(5, 11, 1710000000000, "0.4.5")
        assertTrue(result.contains("сигнал: 11"))
        assertTrue(result.contains("0.4.5"))
    }

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

    @Test fun iceCandidateWithEmptyMediaIdMustRoundTrip() {
        val ice = WireMessage.Ice("", 0, "candidate:1 1 udp 123 192.168.1.2 5000 typ host")
        assertEquals(ice, WireProtocol.decode(WireProtocol.encode(ice)))
    }

    @Test fun roundTripSignalling() {
        for (message in listOf(
            WireMessage.Challenge(PairingCode.newChallenge()), WireMessage.Proof, WireMessage.Accept, WireMessage.Busy, WireMessage.Reject, WireMessage.Ping,
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

    @Test fun localIpRejectsLinkLocalAddresses() {
        assertFalse(LocalAddress.isPrivateIpv4("169.254.10.3"))
        assertFalse(LocalAddress.isPrivateIpv4("192.168.1.256"))
    }

    @Test fun videoSettingsUseExistingCaptureFormatWhenNoSmallerOneExists() {
        val onlyFormat = CaptureOption(3840, 2160, 30)
        assertEquals(onlyFormat, VideoQuality.QHD_60.chooseFormat(listOf(onlyFormat)))
    }

    @Test fun discoveryRejectsUnknownPacketsAndPublicSources() {
        assertFalse(com.homebabymonitor.net.isCameraBeacon("wrong", "192.168.2.3"))
        assertFalse(com.homebabymonitor.net.isCameraBeacon(
            com.homebabymonitor.net.LocalPorts.BEACON, "8.8.8.8"))
        assertTrue(com.homebabymonitor.net.isCameraBeacon(
            com.homebabymonitor.net.LocalPorts.BEACON, "192.168.2.3"))
    }

    @Test fun onlyLocalIpv4TargetsAreAccepted() {
        for (ip in listOf("192.168.1.6", "10.2.0.45", "172.16.5.4")) assertTrue(LocalAddress.isPrivateIpv4(ip))
        for (ip in listOf("8.8.8.8", "172.32.0.1", "example.com", "127.0.0.1", "192.168.001.2")) assertFalse(LocalAddress.isPrivateIpv4(ip))
    }

    @Test fun successfulAuthenticationClearsPriorFailures() {
        val limiter = PinRateLimiter(maxFailures = 3, windowMillis = 60000)
        limiter.failed(100L)
        limiter.failed(200L)
        limiter.succeeded()
        assertTrue(limiter.mayTry(300L))
        limiter.failed(301L)
        assertTrue(limiter.mayTry(302L))
    }

    @Test fun pinBruteForceGetsTemporaryBlock() {
        val guard = PinRateLimiter(maxFailures = 5, windowMillis = 60000)
        for (attempt in 0..4) { assertTrue(guard.mayTry(attempt.toLong())); guard.failed(attempt.toLong()) }
        assertFalse(guard.mayTry(100))
        assertTrue(guard.mayTry(60100))
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

    @Test fun offlineWifiWorksEvenWhenMobileInternetIsDefault() {
        val candidates = listOf(
            WifiCandidate(false, true, listOf("10.123.0.8")),
            WifiCandidate(true, false, listOf("192.168.22.3"))
        )
        assertEquals("192.168.22.3", WifiEndpointSelector.choose(candidates))
        assertNull(WifiEndpointSelector.choose(listOf(WifiCandidate(false, true, listOf("10.1.2.3")))))
    }

    @Test fun wifiSelectionIgnoresLinkLocalAndPublicAddresses() {
        assertEquals("192.168.7.12", WifiEndpointSelector.choose(
            listOf(WifiCandidate(true, false, listOf("169.254.1.4", "192.168.7.12")))
        ))
        assertNull(WifiEndpointSelector.choose(listOf(WifiCandidate(true, true, listOf("8.8.8.8")))))
    }

    @Test fun hidePairingSecretWhenCameraIsConnected() {
        assertTrue(PairingVisibility.show(false, false))
        assertFalse(PairingVisibility.show(true, false))
        assertTrue(PairingVisibility.show(true, true))
    }

    @Test fun qrPairingAcceptsOnlyPrivateAddress() {
        val secret = PairingCode.generate()
        val link = PairingLink.create("192.168.1.25", secret)
        assertEquals("192.168.1.25", PairingLink.parse(link)?.ip)
        assertEquals(PairingCode.normalize(secret), PairingLink.parse(link)?.code)
        assertNull(PairingLink.parse("hbm://pair?v=1&ip=8.8.8.8&key=0123456789ABCDEFGHJK"))
    }

    @Test fun twoK60PrioritizesFrameRateWhenCameraCannotDoBoth() {
        val formats = listOf(CaptureOption(2560, 1440, 30), CaptureOption(1920, 1080, 60))
        assertEquals(CaptureOption(1920, 1080, 60), VideoQuality.QHD_60.chooseFormat(formats))
        assertEquals(60, VideoQuality.QHD_60.fps)
    }

    @Test fun fullHd30KeepsNativeResolutionRatherThanCroppingForHigherFps() {
        val formats = listOf(CaptureOption(1280, 720, 60), CaptureOption(1920, 1080, 30))
        assertEquals(CaptureOption(1920, 1080, 30), VideoQuality.FHD_30.chooseFormat(formats))
        assertEquals(CaptureOption(1280, 720, 30), VideoQuality.HD.chooseFormat(formats))
    }

    @Test fun sensorFrameRateIsCappedToRequestedProfile() {
        assertEquals(CaptureOption(1920, 1080, 30),
            VideoQuality.FHD_30.chooseFormat(listOf(CaptureOption(1920, 1080, 60))))
    }

    @Test fun newSleepSoundsRoundTripInSecureControlMessages() {
        for (sound in listOf(SleepSound.OCEAN, SleepSound.WIND, SleepSound.WHITE_NOISE,
            SleepSound.SOFT_NOISE, SleepSound.HEARTBEAT, SleepSound.LULLABY_2, SleepSound.BELLS)) {
            val request = SleepCommand(sound, 30, 25)
            assertEquals(request, SleepCommand.parse(request.encode()))
        }
    }

    @Test fun autoVideoNeverAssumesUnadvertisedFourKCapability() {
        val formats = listOf(CaptureOption(1920, 1080, 30), CaptureOption(3840, 2160, 60))
        val limited = AdaptiveVideoPolicy.supported(
            formats, VideoEndpointLimit(3840L * 2160 * 60), VideoEndpointLimit.UNKNOWN
        )
        assertTrue(VideoQuality.FHD_30 in limited)
        assertFalse(VideoQuality.UHD_60 in limited)
        val full = AdaptiveVideoPolicy.supported(
            formats, VideoEndpointLimit(3840L * 2160 * 60),
            VideoEndpointLimit(3840L * 2160 * 60)
        )
        assertTrue(VideoQuality.UHD_60 in full)
        assertEquals(VideoQuality.FHD_30, AdaptiveVideoPolicy.safeInitial(full))
    }

    @Test fun autoVideoNeedsRepeatedGoodFramesToUpgradeAndDowngradesOnLoss() {
        val policy = AdaptiveVideoPolicy()
        val profiles = AdaptiveVideoPolicy.ordered
        assertEquals(VideoQuality.FHD_30, policy.reset(profiles))
        val good = ReceptionHealth(30.0, 0.1, 40.0)
        repeat(7) { assertNull(policy.accept(good, profiles, 32000L + it * 2000)) }
        assertEquals(VideoQuality.FHD_60, policy.accept(good, profiles, 46000L))
        val bad = ReceptionHealth(10.0, 10.0, 450.0)
        assertNull(policy.accept(bad, profiles, 50000L))
        assertEquals(VideoQuality.FHD_30, policy.accept(bad, profiles, 56000L))
    }

    @Test fun videoFeedbackRejectsUntrustedValuesAndPreservesPrecision() {
        val health = ReceptionHealth(28.5, 1.2, 53.0, 0.5)
        assertEquals(health, ReceptionHealth.parse(health.encode()))
        for (input in listOf("RX_HEALTH:99999:0:0:0", "RX_HEALTH:-1:0:0:0",
            "RX_HEALTH:20:1010:0:0", "RX_HEALTH:20:0:0",
            "RX_HEALTH:20:0:0:0:1", "RX_HEALTH:20:x:0:0")) {
            assertNull(ReceptionHealth.parse(input))
        }
    }

    @Test fun unsupportedManualFourKIsClampedToActualEndpointCapability() {
        val allowed = listOf(VideoQuality.ECONOMY, VideoQuality.HD, VideoQuality.FHD_30)
        assertEquals(VideoQuality.FHD_30, AdaptiveVideoPolicy.limitManual(VideoQuality.UHD_60, allowed))
    }

    @Test fun receiverCapabilityPayloadIsStrictlyBounded() {
        val capability = VideoEndpointLimit(3840L * 2160 * 60)
        assertEquals(capability, VideoEndpointLimit.parse(capability.encode()))
        for (payload in listOf("RX_CAPS:-1", "RX_CAPS:0", "RX_CAPS:999999999999",
            "RX_CAPS:abc", "RX_CAPS:100:extra")) {
            assertNull(VideoEndpointLimit.parse(payload))
        }
    }

    @Test fun noActualCameraModeMeansNoInventedFourK() {
        val formats = listOf(CaptureOption(1920, 1080, 30))
        val maxHardware = VideoEndpointLimit(3840L * 2160 * 60)
        val options = AdaptiveVideoPolicy.supported(formats, maxHardware, maxHardware)
        assertFalse(VideoQuality.UHD_60 in options)
        assertFalse(VideoQuality.UHD_30 in options)
        assertEquals(VideoQuality.FHD_30, AdaptiveVideoPolicy.safeInitial(options))
    }

    @Test fun codecBudgetsRejectDuplicatesOrBogusClaims() {
        val caps = VideoCodecBudgets(mapOf("H264" to 1920L * 1080 * 30, "H265" to 3840L * 2160 * 60))
        assertEquals(caps, VideoCodecBudgets.parse(caps.encode()))
        for (input in listOf("RX_CODECS:H264=100,H264=101", "RX_CODECS:H264=-1",
             "RX_CODECS:H265=99999999999999", "RX_CODECS:BAD=100",
             "RX_CODECS:H264=0", "RX_CODECS:H264=abc")) {
            assertNull(VideoCodecBudgets.parse(input))
        }
    }

    @Test fun receiverCodecPerformanceMustBeRespectedNotFastestOtherCodec() {
        val camera = listOf(CaptureOption(1920,1080,30), CaptureOption(3840,2160,60))
        val sender = VideoCodecBudgets(mapOf("H265" to 3840L * 2160 * 60))
        val receiver = VideoCodecBudgets(mapOf("H265" to 1920L * 1080 * 30, "H264" to 3840L * 2160 * 60))
        val supported = AdaptiveVideoPolicy.supported(camera,
            VideoEndpointLimit(sender.rateFor("H265")), VideoEndpointLimit(receiver.rateFor("H265")))
        assertFalse(VideoQuality.UHD_60 in supported)
    }

    @Test fun hardwareCodecPreferenceKeepsRecoveryFormats() {
        val options = listOf("VP8","H264","RTX","H265","AV1","RED")
        val ranked = VideoCodecPreference.ordered(options,
            VideoCodecBudgets(mapOf("H264" to 1920L*1080*30, "H265" to 3840L*2160*60)))
        assertEquals("H265", ranked.first())
        assertEquals(listOf("RTX", "RED"), ranked.takeLast(2))
        assertEquals(options.toSet(), ranked.toSet())
    }

    @Test fun musicCommandRejectsInvalidValues() {
        val request = SleepCommand(SleepSound.MUSIC_BOX, 30, 20)
        assertEquals(request, SleepCommand.parse(request.encode()))
        assertNull(SleepCommand.parse("MUSIC:RAIN:999:99"))
    }
}
