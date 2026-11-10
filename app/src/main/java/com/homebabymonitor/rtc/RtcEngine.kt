package com.homebabymonitor.rtc

import android.content.Context
import android.media.AudioFormat
import android.util.Log
import com.homebabymonitor.MonitorBus
import com.homebabymonitor.Role
import com.homebabymonitor.core.AudioTrigger
import com.homebabymonitor.core.VideoQuality
import com.homebabymonitor.core.CaptureOption
import com.homebabymonitor.core.AdaptiveVideoPolicy
import com.homebabymonitor.core.VideoEndpointLimit
import com.homebabymonitor.core.ReceptionHealth
import com.homebabymonitor.core.VideoCodecBudgets
import com.homebabymonitor.core.VideoCodecPreference
import com.homebabymonitor.core.PlaybackTimingSampler
import com.homebabymonitor.core.PlaybackTimingCounters
import com.homebabymonitor.core.CaptureRecoveryGate
import com.homebabymonitor.core.InitializationGate
import com.homebabymonitor.core.WireMessage
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class RtcEngine(
    private val context: Context,
    private val role: Role,
    private val emitSignaling: (WireMessage) -> Unit,
    private val onConnection: (Boolean) -> Unit,
    private val onControl: (String) -> Unit,
    private val onVideoFrame: () -> Unit,
    private val onCaptureMetrics: (String) -> Unit,
    private val onError: (String) -> Unit,
    initialQuality: VideoQuality = VideoQuality.FHD_30
) {
    private val audioDetector = AudioTrigger()
    private var sampleCount = 0
    private var lastReportAt = android.os.SystemClock.elapsedRealtime()
    private val recovery = CaptureRecoveryGate()
    private val night = NightFrameProcessor { width, height ->
        recovery.frame(android.os.SystemClock.elapsedRealtime())
        sampleCount++
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastReportAt >= 1500) {
            val measuredFps = (sampleCount * 1000L / (now - lastReportAt)).toInt()
            sampleCount = 0
            lastReportAt = now
            onCaptureMetrics("${width}×${height} · ${measuredFps} к/с")
        }
    }
    private val egl = MonitorBus.eglBase
    private var capturer: CameraVideoCapturer? = null
    private var cameraEnumerator: CameraEnumerator? = null
    private var cameraName: String? = null
    private var videoSender: RtpSender? = null
    private var requestedQuality = initialQuality
    private var qualityProfile = VideoQuality.FHD_30
    private var currentCapture = CaptureOption(1280, 720, 30)
    private var cameraFormats: List<CaptureOption> = emptyList()
    private var hardwareLimit = VideoEndpointLimit.UNKNOWN
    private var receiverLimit = VideoEndpointLimit.UNKNOWN
    private var localCodecBudgets = VideoCodecBudgets(emptyMap())
    private var receiverCodecBudgets = VideoCodecBudgets(emptyMap())
    private var negotiatedVideoCodec: String? = null
    private var thermalSevere = false
    private val adaptive = AdaptiveVideoPolicy()
    private var supportedCodecNames = ""
    @Volatile private var measuredReceiveFps = 0.0
    private var previousReceivedPackets = -1L
    private var previousLostPackets = -1L
    private var previousDecodedFrames = -1L
    private var previousDroppedFrames = -1L
    private val timingSampler = PlaybackTimingSampler()
    @Volatile private var cameraMicEnabled = true
    @Volatile private var playbackEnabled = true
    @Volatile private var playbackVolume = 100
    private var inboundAudioTrack: AudioTrack? = null
    private var previousBytes = -1L
    private var previousStatsTime = 0L
    private var texture: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var factory: PeerConnectionFactory? = null
    @Volatile private var peer: PeerConnection? = null
    @Volatile private var channel: DataChannel? = null
    private var observedVideoTrack: VideoTrack? = null
    private var receivedFrames = 0
    private var lastReceivedReport = android.os.SystemClock.elapsedRealtime()
    private val frameWatcher = VideoSink { frame ->
        onVideoFrame()
        receivedFrames++
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastReceivedReport >= 1500) {
            val fps = (receivedFrames * 1000L / (now - lastReceivedReport)).toInt()
            measuredReceiveFps = receivedFrames * 1000.0 / (now - lastReceivedReport)
            receivedFrames = 0
            lastReceivedReport = now
            onControl("LOCAL_RECEIVED:${frame.buffer.width}×${frame.buffer.height} · ${fps} к/с")
        }
    }
    private var remoteSdpApplied = false
    private val queuedIce = mutableListOf<IceCandidate>()
    private val closing = AtomicBoolean(false)
    @Volatile private var peerGeneration = 0L

    companion object {
        private val initialization = InitializationGate()
        private fun initialize(context: Context) {
            initialization.ensure {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
                )
            }
        }
    }

    fun start() {
        initialize(context)
        val module = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setSamplesReadyCallback { samples ->
                if (role == Role.BABY && cameraMicEnabled && samples.audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
                    if (audioDetector.feed(samples.data, samples.sampleRate, samples.channelCount, android.os.SystemClock.elapsedRealtime())) {
                        sendControl("SOUND")
                        onControl("LOCAL_SOUND")
                    }
                }
            }
            .createAudioDeviceModule()
        try {
            val encoderFactory = DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(egl.eglBaseContext)
            val platformCodecs = if (role == Role.BABY)
                encoderFactory.supportedCodecs.map { it.name }.toSet()
                else decoderFactory.supportedCodecs.map { it.name }.toSet()
            val capabilities = DeviceVideoCapabilities.probe(role == Role.BABY, platformCodecs)
            hardwareLimit = capabilities.limit
            localCodecBudgets = capabilities.budgets
            supportedCodecNames = capabilities.codecs
            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(module)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
        } finally {
            module.release()
        }
        audioSource = factory?.createAudioSource(MediaConstraints())
        audioTrack = factory?.createAudioTrack("microphone", audioSource)
        audioTrack?.setEnabled(role == Role.BABY && cameraMicEnabled)
        if (role == Role.BABY) openCamera()
    }

    fun localEndpointLimit(): VideoEndpointLimit = hardwareLimit
    fun localCodecBudgets(): VideoCodecBudgets = localCodecBudgets
    fun localCodecDescription(): String = supportedCodecNames
    fun negotiatedCodec(): String = negotiatedVideoCodec ?: "не определён"

    @Synchronized private fun eligibleProfiles(): List<VideoQuality> {
        // Codec throughput must refer to the negotiated RTP codec on *both* endpoints.
        // The fastest unrelated decoder must never unlock a 4K H264 stream.
        val codec = negotiatedVideoCodec
        val encoder = if (codec != null) localCodecBudgets.rateFor(codec)
            else VideoEndpointLimit.UNKNOWN.maxPixelsPerSecond
        val decoder = if (codec != null) receiverCodecBudgets.rateFor(codec)
            else VideoEndpointLimit.UNKNOWN.maxPixelsPerSecond
        val thermal = if (thermalSevere) 1280L * 720 * 30 else Long.MAX_VALUE
        return AdaptiveVideoPolicy.supported(cameraFormats,
            VideoEndpointLimit(minOf(encoder, thermal)), VideoEndpointLimit(decoder))
    }

    @Synchronized private fun initialCameraQuality(): VideoQuality {
        val allowed = eligibleProfiles()
        return if (requestedQuality == VideoQuality.AUTO) adaptive.reset(allowed, android.os.SystemClock.elapsedRealtime())
            else AdaptiveVideoPolicy.limitManual(requestedQuality, allowed)
    }

    private fun openCamera() {
        val cameraEnum: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(false)
        val back = cameraEnum.deviceNames.firstOrNull { cameraEnum.isBackFacing(it) }
        val choice = back ?: cameraEnum.deviceNames.firstOrNull()
        if (choice == null) { onError("На телефоне не найдена камера"); return }
        cameraEnumerator = cameraEnum
        cameraName = choice
        cameraFormats = cameraEnum.getSupportedFormats(choice).map {
            val raw = it.framerate.max
            CaptureOption(it.width, it.height, if (raw > 1000) raw / 1000 else raw)
        }
        capturer = cameraEnum.createCapturer(choice, null)
        if (capturer == null) { onError("Не удалось открыть камеру"); return }
        videoSource = factory?.createVideoSource(false)
        videoSource?.setVideoProcessor(night)
        texture = SurfaceTextureHelper.create("camera-capture", egl.eglBaseContext)
        capturer?.initialize(texture, context, videoSource?.capturerObserver)
        try {
            videoTrack = factory?.createVideoTrack("camera", videoSource)
            MonitorBus.setCameraVideoTrack(videoTrack)
            qualityProfile = initialCameraQuality()
            currentCapture = chooseCapture(qualityProfile)
            recovery.start(android.os.SystemClock.elapsedRealtime())
            capturer?.startCapture(currentCapture.width, currentCapture.height, currentCapture.fps)
        } catch (e: Exception) { onError("Ошибка запуска камеры: ${e.message}") }
    }

    @Synchronized fun preparePeer(initiator: Boolean) {
        closePeer()
        closing.set(false)
        val myGeneration = peerGeneration
        remoteSdpApplied = false
        queuedIce.clear()
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                if (!closing.get() && myGeneration == peerGeneration)
                    emitSignaling(WireMessage.Ice(candidate.sdpMid ?: "", candidate.sdpMLineIndex, candidate.sdp))
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dataChannel: DataChannel) {
                if (myGeneration == peerGeneration && !closing.get()) installChannel(dataChannel)
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {
                if (myGeneration == peerGeneration && !closing.get()) attachIncomingTrack(receiver.track())
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                if (myGeneration == peerGeneration && !closing.get()) attachIncomingTrack(transceiver.receiver.track())
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (myGeneration != peerGeneration || closing.get()) return
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> onConnection(true)
                    PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.CLOSED -> onConnection(false)
                    else -> Unit
                }
            }
        }
        val connection = factory?.createPeerConnection(config, observer)
        if (connection == null) { onError("Не удалось создать WebRTC-соединение"); return }
        peer = connection
        audioTrack?.let { connection.addTrack(it, listOf("local")) }
        if (role == Role.BABY) videoTrack?.let {
            videoSender = connection.addTrack(it, listOf("local"))
            updateEncoderBitrate()
            // Let WebRTC's congestion controller adapt below this ceiling on weak Wi-Fi.
            try { connection.setBitrate(null, minOf(qualityProfile.maxBitrateBps / 2, 3_000_000), qualityProfile.maxBitrateBps) }
            catch (e: Exception) { Log.w("RtcEngine", "Initial video bitrate unavailable", e) }
        }
        if (role == Role.PARENT) {
            connection.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
            installChannel(connection.createDataChannel("controls", DataChannel.Init()))
        }
        // Apply codec order before SDP exchange; never exclude RTX/RED/FEC or fallback codecs.
        // Each end ranks its own hardware; normal offer/answer intersects compatible codecs.
        try {
            val videoType = MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
            val supported = if (role == Role.BABY)
                factory?.getRtpSenderCapabilities(videoType)?.codecs.orEmpty()
            else factory?.getRtpReceiverCapabilities(videoType)?.codecs.orEmpty()
            if (supported.isNotEmpty()) {
                val preference = supported.sortedWith(
                    compareBy<RtpCapabilities.CodecCapability> {
                        VideoCodecPreference.rank(it.name, localCodecBudgets)
                    }
                )
                connection.transceivers.filter { it.mediaType == videoType }
                    .forEach { it.setCodecPreferences(preference) }
            }
        } catch (e: Exception) {
            Log.w("RtcEngine", "Hardware codec preference failed; WebRTC default will be used", e)
        }
        if (initiator) createOffer()
    }

    private fun attachIncomingTrack(track: MediaStreamTrack?) {
        when (track) {
            is VideoTrack -> if (role == Role.PARENT) attachVideoTrack(track)
            is AudioTrack -> {
                inboundAudioTrack = track
                applyPlaybackVolume()
            }
        }
    }

    private fun applyPlaybackVolume() {
        try { inboundAudioTrack?.setVolume(if (playbackEnabled) playbackVolume / 100.0 else 0.0) }
        catch (e: Exception) { Log.w("RtcEngine", "Incoming audio volume unavailable", e) }
    }

    fun setPlayback(enabled: Boolean, volume: Int) {
        playbackEnabled = enabled
        playbackVolume = volume.coerceIn(0, 100)
        applyPlaybackVolume()
    }

    fun setCameraMicrophone(enabled: Boolean) {
        if (role != Role.BABY) return
        cameraMicEnabled = enabled
        audioTrack?.setEnabled(enabled)
    }

    /** Report actual received video bandwidth; ICE and encoder control remain adaptive. */
    fun pollVideoStats(
        onResult: (String) -> Unit,
        onFeedback: (ReceptionHealth) -> Unit = {},
        onTiming: (String) -> Unit = {}
    ) {
        if (role != Role.PARENT || closing.get()) return
        val connection = peer ?: return
        val generation = peerGeneration
        try {
            connection.getStats { report ->
                if (closing.get() || generation != peerGeneration) return@getStats
                val video = report.statsMap.values.firstOrNull { stat ->
                    stat.type == "inbound-rtp" &&
                        (stat.members["kind"] == "video" || stat.members["mediaType"] == "video")
                }
                if (video != null) {
                    val bytes = (video.members["bytesReceived"] as? Number)?.toLong()
                    val packetsLost = (video.members["packetsLost"] as? Number)?.toLong()
                    val packetsReceived = (video.members["packetsReceived"] as? Number)?.toLong()
                    val decoded = (video.members["framesDecoded"] as? Number)?.toLong()
                    val dropped = (video.members["framesDropped"] as? Number)?.toLong()
                    val jitterSum = (video.members["jitterBufferDelay"] as? Number)?.toDouble()
                    val bufferCount = (video.members["jitterBufferEmittedCount"] as? Number)?.toLong()
                    val decoderSum = (video.members["totalDecodeTime"] as? Number)?.toDouble()
                    val freezeCount = (video.members["freezeCount"] as? Number)?.toLong()
                    if (jitterSum != null && bufferCount != null && decoderSum != null &&
                        decoded != null && freezeCount != null) {
                        timingSampler.record(PlaybackTimingCounters(jitterSum, bufferCount,
                            decoderSum, decoded, freezeCount))?.let { delta ->
                            val line = String.format(java.util.Locale.US,
                                "Буфер %.0f мс · декодирование %.0f мс",
                                delta.bufferMs, delta.decodeMs)
                            onTiming(line + if (delta.newFreezes > 0) " · замирания +${delta.newFreezes}" else "")
                        }
                    }
                    val codecId = video.members["codecId"] as? String
                    val mime = codecId?.let { report.statsMap[it]?.members?.get("mimeType") as? String }
                        ?.substringAfter('/', "")
                    val rtt = report.statsMap.values.firstOrNull { stat ->
                        stat.type == "candidate-pair" && stat.members["state"] == "succeeded" &&
                            stat.members["currentRoundTripTime"] is Number
                    }?.members?.get("currentRoundTripTime") as? Number
                    val fps = (video.members["framesPerSecond"] as? Number)?.toDouble()
                        ?: measuredReceiveFps
                    if (bytes != null) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (previousStatsTime > 0 && now > previousStatsTime && bytes >= previousBytes) {
                            val mbps = (bytes - previousBytes) * 8.0 / (now - previousStatsTime) / 1000.0
                            val label = String.format(java.util.Locale.US, "%.2f Мбит/с", mbps)
                            onResult(label + (if (mime.isNullOrBlank()) "" else " · $mime") +
                                (if (packetsLost != null && packetsLost > 0) " · потери: $packetsLost" else ""))
                        }
                        previousBytes = bytes
                        previousStatsTime = now
                    }
                    if (packetsReceived != null && packetsLost != null) {
                        val gained = if (previousReceivedPackets < 0L) 0
                            else (packetsReceived - previousReceivedPackets).coerceAtLeast(0L)
                        val lost = if (previousLostPackets < 0L) 0
                            else (packetsLost - previousLostPackets).coerceAtLeast(0L)
                        previousReceivedPackets = packetsReceived
                        previousLostPackets = packetsLost
                        val freshDecoded = if (decoded != null && previousDecodedFrames >= 0L)
                            (decoded - previousDecodedFrames).coerceAtLeast(0) else 0
                        val freshDropped = if (dropped != null && previousDroppedFrames >= 0L)
                            (dropped - previousDroppedFrames).coerceAtLeast(0) else 0
                        if (decoded != null) previousDecodedFrames = decoded
                        if (dropped != null) previousDroppedFrames = dropped
                        if (gained + lost > 0 && fps > 0.0) {
                            val lossPct = 100.0 * lost / (gained + lost)
                            val droppedPct = if (freshDecoded + freshDropped > 0)
                                100.0 * freshDropped / (freshDecoded + freshDropped) else 0.0
                            val health = ReceptionHealth(fps.coerceIn(0.0, 240.0), lossPct,
                                ((rtt?.toDouble() ?: 0.0) * 1000).coerceIn(0.0, 30000.0),
                                droppedPct)
                            if (health.valid) onFeedback(health)
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.w("RtcEngine", "Video transport stats unavailable", e) }
    }

    @Synchronized private fun attachVideoTrack(track: VideoTrack) {
        if (observedVideoTrack !== track) {
            observedVideoTrack?.removeSink(frameWatcher)
            observedVideoTrack = track
            track.addSink(frameWatcher)
        }
        MonitorBus.setVideoTrack(track)
    }

    private fun installChannel(newChannel: DataChannel?) {
        if (newChannel == null) return
        channel = newChannel
        val generation = peerGeneration
        newChannel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (closing.get() || generation != peerGeneration || buffer.binary) return
                val data = buffer.data.slice()
                if (data.remaining() > 2048) return
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                onControl(String(bytes, StandardCharsets.UTF_8))
            }
        })
    }

    fun sendControl(message: String): Boolean {
        val dc = channel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        return try { dc.send(DataChannel.Buffer(ByteBuffer.wrap(message.toByteArray(StandardCharsets.UTF_8)), false)) }
        catch (_: Exception) { false }
    }

    fun onSignal(message: WireMessage) {
        when (message) {
            is WireMessage.Offer -> {
                setRemote(SessionDescription.Type.OFFER, message.sdp) { createAnswer() }
            }
            is WireMessage.Answer -> {
                setRemote(SessionDescription.Type.ANSWER, message.sdp) {}
            }
            is WireMessage.Ice -> {
                val candidate = IceCandidate(message.mid, message.index, message.candidate)
                synchronized(queuedIce) {
                    if (remoteSdpApplied) peer?.addIceCandidate(candidate) else queuedIce.add(candidate)
                }
            }
            else -> Unit
        }
    }

    private fun setRemote(type: SessionDescription.Type, sdp: String, after: () -> Unit) {
        val connection = peer ?: return
        val generation = peerGeneration
        connection.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                if (generation != peerGeneration || closing.get()) return
                synchronized(queuedIce) {
                    remoteSdpApplied = true
                    queuedIce.forEach { connection.addIceCandidate(it) }
                    queuedIce.clear()
                }
                after()
            }
            override fun onSetFailure(error: String?) { onError("Не удалось принять параметры видео: $error") }
        }, SessionDescription(type, sdp))
    }

    private fun createOffer() {
        val connection = peer ?: return
        val generation = peerGeneration
        connection.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                if (generation == peerGeneration && !closing.get()) setLocal(description, WireMessage.Offer(description.description))
            }
            override fun onCreateFailure(error: String?) { onError("Ошибка создания предложения: $error") }
        }, MediaConstraints())
    }

    private fun createAnswer() {
        val connection = peer ?: return
        val generation = peerGeneration
        connection.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                if (generation == peerGeneration && !closing.get()) setLocal(description, WireMessage.Answer(description.description))
            }
            override fun onCreateFailure(error: String?) { onError("Ошибка ответа камеры: $error") }
        }, MediaConstraints())
    }

    private fun setLocal(description: SessionDescription, signal: WireMessage) {
        val connection = peer ?: return
        val generation = peerGeneration
        connection.setLocalDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() { if (generation == peerGeneration && !closing.get()) emitSignaling(signal) }
            override fun onSetFailure(error: String?) { onError("Ошибка настройки соединения: $error") }
        }, description)
    }

    private fun chooseCapture(profile: VideoQuality): CaptureOption {
        val formats = cameraName?.let { cameraEnumerator?.getSupportedFormats(it) }.orEmpty()
        val options = formats.map {
            val rawFps = it.framerate.max
            CaptureOption(it.width, it.height, if (rawFps > 1000) rawFps / 1000 else rawFps)
        }
        return profile.chooseFormat(options)
    }

    private fun updateEncoderBitrate() {
        val sender = videoSender ?: return
        try {
            val parameters = sender.parameters
            if (parameters.encodings.isEmpty()) return
            parameters.encodings.forEach {
                it.maxBitrateBps = qualityProfile.maxBitrateBps
                it.maxFramerate = qualityProfile.fps
            }
            sender.setParameters(parameters)
        } catch (e: Exception) {
            Log.w("RtcEngine", "Video bitrate cannot be changed", e)
        }
    }

    /** Restart the same capturer without tearing down the audio/peer tracks.
     * Must be called on a service worker, never on the main/UI or capture thread.
     */
    @Synchronized fun recoverFrozenCapture(): CaptureRecoveryGate.Action {
        if (role != Role.BABY || capturer == null || closing.get()) return CaptureRecoveryGate.Action.NONE
        val action = recovery.next(android.os.SystemClock.elapsedRealtime())
        if (action != CaptureRecoveryGate.Action.RESTART) return action
        val current = capturer ?: return CaptureRecoveryGate.Action.NONE
        try {
            current.stopCapture()
            // The first recovery uses a low-cost fallback if even the selected
            // capture mode is too expensive for sustained operation.
            val request = if (requestedQuality == VideoQuality.AUTO) VideoQuality.HD else qualityProfile
            val selected = chooseCapture(request)
            current.startCapture(selected.width, selected.height, selected.fps)
            currentCapture = selected
            Log.w("RtcEngine", "Restarted camera capturer after no frames")
        } catch (e: Exception) {
            Log.e("RtcEngine", "Camera capture recovery failed", e)
            onError("Не удалось восстановить видеосъёмку: ${e.message}")
        }
        return action
    }

    @Synchronized private fun applyCameraQuality(next: VideoQuality): CaptureOption {
        if (role != Role.BABY || capturer == null || qualityProfile == next) return currentCapture
        val requested = chooseCapture(next)
        try {
            capturer?.changeCaptureFormat(requested.width, requested.height, requested.fps)
            currentCapture = requested
            qualityProfile = next
            updateEncoderBitrate()
            peer?.let { connection ->
                try { connection.setBitrate(null, minOf(next.maxBitrateBps / 2, 3_000_000), next.maxBitrateBps) }
                catch (e: Exception) { Log.w("RtcEngine", "Video bitrate request rejected", e) }
            }
        } catch (e: Exception) {
            onError("Камера не сменила режим: ${e.message}")
        }
        return currentCapture
    }

    @Synchronized fun setQuality(value: VideoQuality): CaptureOption {
        requestedQuality = value
        return applyCameraQuality(initialCameraQuality())
    }

    @Synchronized fun setReceiverLimit(limit: VideoEndpointLimit): VideoQuality? {
        if (role != Role.BABY || limit == receiverLimit) return null
        receiverLimit = limit // Legacy clients; does not unlock 4K without per-codec evidence.
        val previous = qualityProfile
        applyCameraQuality(initialCameraQuality())
        return if (previous != qualityProfile) qualityProfile else null
    }

    @Synchronized fun setReceiverCodecBudgets(budgets: VideoCodecBudgets): VideoQuality? {
        if (role != Role.BABY || budgets == receiverCodecBudgets) return null
        receiverCodecBudgets = budgets
        val previous = qualityProfile
        applyCameraQuality(initialCameraQuality())
        return if (previous != qualityProfile) qualityProfile else null
    }

    /** Must only be called for codec reported by a live RTP sender, not an SDP preference. */
    @Synchronized fun setNegotiatedVideoCodec(codecName: String): VideoQuality? {
        val normalized = codecName.uppercase()
        if (role != Role.BABY || normalized !in VideoCodecBudgets.KNOWN ||
            normalized == negotiatedVideoCodec) return null
        negotiatedVideoCodec = normalized
        val previous = qualityProfile
        applyCameraQuality(initialCameraQuality())
        return if (previous != qualityProfile) qualityProfile else null
    }

    fun pollSenderCodec(onSelected: (String) -> Unit) {
        if (role != Role.BABY || closing.get()) return
        val connection = peer ?: return
        val generation = peerGeneration
        try {
            connection.getStats { report ->
                if (closing.get() || generation != peerGeneration) return@getStats
                val video = report.statsMap.values.firstOrNull { stat ->
                    stat.type == "outbound-rtp" &&
                        (stat.members["kind"] == "video" || stat.members["mediaType"] == "video")
                } ?: return@getStats
                val codecId = video.members["codecId"] as? String ?: return@getStats
                val mime = report.statsMap[codecId]?.members?.get("mimeType") as? String ?: return@getStats
                val codec = mime.substringAfter('/').uppercase()
                if (codec in VideoCodecBudgets.KNOWN) onSelected(codec)
            }
        } catch (e: Exception) { Log.w("RtcEngine", "Sender codec statistics unavailable", e) }
    }

    @Synchronized fun setThermalSevere(severe: Boolean): VideoQuality? {
        if (role != Role.BABY || severe == thermalSevere) return null
        thermalSevere = severe
        val previous = qualityProfile
        applyCameraQuality(initialCameraQuality())
        return if (previous != qualityProfile) qualityProfile else null
    }

    @Synchronized fun receiveHealth(health: ReceptionHealth): VideoQuality? {
        if (role != Role.BABY || requestedQuality != VideoQuality.AUTO || thermalSevere ||
            !health.valid || peer == null) return null
        val next = adaptive.accept(health, eligibleProfiles(), android.os.SystemClock.elapsedRealtime())
            ?: return null
        val previous = qualityProfile
        applyCameraQuality(next)
        return if (qualityProfile != previous) qualityProfile else null
    }

    fun activeQuality(): VideoQuality = qualityProfile

    fun setTalking(value: Boolean) {
        if (role == Role.PARENT) audioTrack?.setEnabled(value)
    }

    fun setNight(value: Boolean) {
        night.enabled = value
    }

    @Synchronized fun closePeer() {
        closing.set(true)
        peerGeneration++
        MonitorBus.setVideoTrack(null)
        observedVideoTrack?.removeSink(frameWatcher)
        observedVideoTrack = null
        try { channel?.unregisterObserver() } catch (_: Exception) {}
        try { channel?.close() } catch (_: Exception) {}
        channel = null
        inboundAudioTrack = null
        previousBytes = -1L
        previousStatsTime = 0L
        previousReceivedPackets = -1L
        previousLostPackets = -1L
        previousDecodedFrames = -1L
        previousDroppedFrames = -1L
        measuredReceiveFps = 0.0
        timingSampler.reset()
        if (role == Role.BABY) {
            receiverLimit = VideoEndpointLimit.UNKNOWN
            receiverCodecBudgets = VideoCodecBudgets(emptyMap())
            negotiatedVideoCodec = null
        }
        videoSender = null
        try { peer?.close() } catch (_: Exception) {}
        try { peer?.dispose() } catch (_: Exception) {}
        peer = null
        // A reconnected monitor may support different codecs; restore the safe capture mode.
        if (role == Role.BABY && capturer != null) {
            applyCameraQuality(initialCameraQuality())
        }
    }

    @Synchronized fun stop() {
        closePeer()
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose(); capturer = null
        texture?.dispose(); texture = null
        MonitorBus.setCameraVideoTrack(null)
        videoTrack?.dispose(); videoTrack = null
        videoSource?.dispose(); videoSource = null
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
        factory?.dispose(); factory = null
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
