package com.homebabymonitor

import kotlinx.coroutines.flow.MutableStateFlow
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** Live state for the activity and the local foreground service. */
enum class Role { BABY, PARENT }

data class MonitorState(
    val role: Role? = null,
    val status: String = "Выберите режим на этом телефоне",
    val connected: Boolean = false,
    val localIp: String = "—",
    val remoteIp: String = "",
    val pairingPin: String = "",
    val remoteBattery: Int? = null,
    val charging: Boolean = false,
    val lastSoundAt: Long = 0,
    val nightMode: Boolean = false,
    val torchOn: Boolean = false,
    val videoQuality: String = "FHD_30",
    val actualVideo: String = "",
    val networkVideo: String = "",
    val musicSound: String = "OFF",
    val musicRemainingSeconds: Int = 0,
    val musicVolume: Int = 20,
    val cameraMicEnabled: Boolean = true,
    val speakerEnabled: Boolean = true,
    val playbackVolume: Int = 100,
    val talking: Boolean = false,
    val error: String = ""
)

object MonitorBus {
    val state = MutableStateFlow(MonitorState())
    val eglBase: EglBase by lazy { EglBase.create() }

    private var remoteTrack: VideoTrack? = null
    private var cameraTrack: VideoTrack? = null
    private val remoteRenderers = LinkedHashSet<SurfaceViewRenderer>()
    private val cameraRenderers = LinkedHashSet<SurfaceViewRenderer>()

    @Synchronized fun setVideoTrack(track: VideoTrack?) {
        if (remoteTrack === track) return
        remoteRenderers.forEach { renderer -> remoteTrack?.removeSink(renderer) }
        remoteTrack = track
        remoteRenderers.forEach { renderer -> track?.addSink(renderer) }
    }

    @Synchronized fun setCameraVideoTrack(track: VideoTrack?) {
        if (cameraTrack === track) return
        cameraRenderers.forEach { renderer -> cameraTrack?.removeSink(renderer) }
        cameraTrack = track
        cameraRenderers.forEach { renderer -> track?.addSink(renderer) }
    }

    @Synchronized fun attachRenderer(view: SurfaceViewRenderer, cameraPreview: Boolean) {
        val renderers = if (cameraPreview) cameraRenderers else remoteRenderers
        val track = if (cameraPreview) cameraTrack else remoteTrack
        if (renderers.add(view)) track?.addSink(view)
    }

    @Synchronized fun detachRenderer(view: SurfaceViewRenderer, cameraPreview: Boolean) {
        val renderers = if (cameraPreview) cameraRenderers else remoteRenderers
        val track = if (cameraPreview) cameraTrack else remoteTrack
        if (renderers.remove(view)) track?.removeSink(view)
    }

    fun mutate(transform: (MonitorState) -> MonitorState) {
        synchronized(this) { state.value = transform(state.value) }
    }
}
