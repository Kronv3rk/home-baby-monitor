package com.homebabymonitor.rtc

import android.content.Context
import android.media.AudioFormat
import android.util.Log
import com.homebabymonitor.MonitorBus
import com.homebabymonitor.Role
import com.homebabymonitor.core.AudioTrigger
import com.homebabymonitor.core.VideoQuality
import com.homebabymonitor.core.CaptureOption
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
    private val night = NightFrameProcessor { width, height ->
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
    private var qualityProfile = initialQuality
    private var currentCapture = CaptureOption(initialQuality.width, initialQuality.height, initialQuality.fps)
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
            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(module)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
        } finally {
            module.release()
        }
        audioSource = factory?.createAudioSource(MediaConstraints())
        audioTrack = factory?.createAudioTrack("microphone", audioSource)
        audioTrack?.setEnabled(role == Role.BABY && cameraMicEnabled)
        if (role == Role.BABY) openCamera()
    }

    private fun openCamera() {
        val cameraEnum: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(false)
        val back = cameraEnum.deviceNames.firstOrNull { cameraEnum.isBackFacing(it) }
        val choice = back ?: cameraEnum.deviceNames.firstOrNull()
        if (choice == null) { onError("На телефоне не найдена камера"); return }
        cameraEnumerator = cameraEnum
        cameraName = choice
        capturer = cameraEnum.createCapturer(choice, null)
        if (capturer == null) { onError("Не удалось открыть камеру"); return }
        videoSource = factory?.createVideoSource(false)
        videoSource?.setVideoProcessor(night)
        texture = SurfaceTextureHelper.create("camera-capture", egl.eglBaseContext)
        capturer?.initialize(texture, context, videoSource?.capturerObserver)
        try {
            videoTrack = factory?.createVideoTrack("camera", videoSource)
            MonitorBus.setCameraVideoTrack(videoTrack)
            currentCapture = chooseCapture(qualityProfile)
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
    fun pollVideoStats(onResult: (String) -> Unit) {
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
                    val loss = (video.members["packetsLost"] as? Number)?.toLong()
                    if (bytes != null) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (previousStatsTime > 0 && now > previousStatsTime && bytes >= previousBytes) {
                            val mbps = (bytes - previousBytes) * 8.0 / (now - previousStatsTime) / 1000.0
                            onResult(String.format(java.util.Locale.US, "%.2f Мбит/с", mbps) +
                                (if (loss != null && loss > 0) " · потеряно пакетов: $loss" else ""))
                        }
                        previousBytes = bytes
                        previousStatsTime = now
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

    @Synchronized fun setQuality(value: VideoQuality): CaptureOption {
        qualityProfile = value
        val requested = chooseCapture(value)
        try {
            capturer?.changeCaptureFormat(requested.width, requested.height, requested.fps)
            currentCapture = requested
        } catch (e: Exception) {
            onError("Камера не сменила режим: ${e.message}")
        }
        updateEncoderBitrate()
        if (role == Role.BABY) {
            try { peer?.setBitrate(null, minOf(value.maxBitrateBps / 2, 3_000_000), value.maxBitrateBps) }
            catch (e: Exception) { Log.w("RtcEngine", "Video bitrate request rejected", e) }
        }
        return currentCapture
    }

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
        videoSender = null
        try { peer?.close() } catch (_: Exception) {}
        try { peer?.dispose() } catch (_: Exception) {}
        peer = null
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
