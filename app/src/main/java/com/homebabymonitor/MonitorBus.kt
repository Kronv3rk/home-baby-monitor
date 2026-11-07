package com.homebabymonitor

import kotlinx.coroutines.flow.MutableStateFlow
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** In-process state used by the activity and the long-running local service. */
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
    val talking: Boolean = false,
    val error: String = ""
)

object MonitorBus {
    val state = MutableStateFlow(MonitorState())
    val eglBase: EglBase by lazy { EglBase.create() }

    private var renderer: SurfaceViewRenderer? = null
    private var videoTrack: VideoTrack? = null

    @Synchronized fun setVideoTrack(track: VideoTrack?) {
        val sink = renderer
        if (sink != null) videoTrack?.removeSink(sink)
        videoTrack = track
        if (sink != null) track?.addSink(sink)
    }

    @Synchronized fun bindRenderer(view: SurfaceViewRenderer?) {
        val old = renderer
        if (old != null) videoTrack?.removeSink(old)
        renderer = view
        if (view != null) videoTrack?.addSink(view)
    }

    fun mutate(transform: (MonitorState) -> MonitorState) {
        synchronized(this) { state.value = transform(state.value) }
    }
}
