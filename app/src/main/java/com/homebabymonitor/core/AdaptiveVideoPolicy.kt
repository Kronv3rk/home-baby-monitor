package com.homebabymonitor.core

/** A decoding/encoding budget, measured in pixels per second, not a device-model allowlist. */
data class VideoEndpointLimit(val maxPixelsPerSecond: Long) {
    companion object {
        // No capability advertisement must never be interpreted as 4K support.
        val UNKNOWN = VideoEndpointLimit(1920L * 1080 * 30)

        fun parse(wire: String): VideoEndpointLimit? {
            if (!wire.startsWith("RX_CAPS:") || wire.length > 32) return null
            val rate = wire.removePrefix("RX_CAPS:").toLongOrNull() ?: return null
            return if (rate in (640L * 480 * 15)..(7680L * 4320 * 120))
                VideoEndpointLimit(rate) else null
        }
    }
    fun encode(): String = "RX_CAPS:$maxPixelsPerSecond"
}

data class ReceptionHealth(
    val fps: Double,
    val lossPercent: Double,
    val roundTripMs: Double,
    val framesDroppedPercent: Double = 0.0
) {
    val valid: Boolean get() = fps in 0.0..240.0 && lossPercent in 0.0..100.0 &&
        roundTripMs in 0.0..30_000.0 && framesDroppedPercent in 0.0..100.0

    /** Strict, bounded payload for the authenticated control channel. */
    fun encode(): String = "RX_HEALTH:${(fps * 10).toInt()}:${(lossPercent * 10).toInt()}:" +
        "${roundTripMs.toInt()}:${(framesDroppedPercent * 10).toInt()}"

    companion object {
        fun parse(wire: String): ReceptionHealth? {
            if (wire.length > 64) return null
            val data = wire.split(':')
            if (data.size != 5 || data[0] != "RX_HEALTH") return null
            val fps = data[1].toIntOrNull()?.takeIf { it in 0..2400 } ?: return null
            val loss = data[2].toIntOrNull()?.takeIf { it in 0..1000 } ?: return null
            val rtt = data[3].toIntOrNull()?.takeIf { it in 0..30000 } ?: return null
            val dropped = data[4].toIntOrNull()?.takeIf { it in 0..1000 } ?: return null
            return ReceptionHealth(fps / 10.0, loss / 10.0, rtt.toDouble(), dropped / 10.0)
        }
    }
}

/** Camera and both codec endpoints must all support a requested preset. */
class AdaptiveVideoPolicy {
    companion object {
        val ordered = listOf(
            VideoQuality.ECONOMY, VideoQuality.HD, VideoQuality.FHD_30,
            VideoQuality.FHD_60, VideoQuality.QHD_30, VideoQuality.QHD_60,
            VideoQuality.UHD_30, VideoQuality.UHD_60
        )

        fun supported(
            cameraFormats: List<CaptureOption>,
            sender: VideoEndpointLimit,
            receiver: VideoEndpointLimit
        ): List<VideoQuality> = ordered.filter { profile ->
            profile.width.toLong() * profile.height * profile.fps <= sender.maxPixelsPerSecond &&
                profile.width.toLong() * profile.height * profile.fps <= receiver.maxPixelsPerSecond &&
                cameraFormats.any { format ->
                    format.width >= profile.width && format.height >= profile.height && format.fps >= profile.fps &&
                        kotlin.math.abs(format.width.toDouble() / format.height -
                            profile.width.toDouble() / profile.height) < 0.05
                }
        }.ifEmpty { listOf(VideoQuality.ECONOMY) }

        fun safeInitial(supported: List<VideoQuality>): VideoQuality =
            if (VideoQuality.FHD_30 in supported) VideoQuality.FHD_30
            else supported.lastOrNull { it.width <= 1920 && it.fps <= 30 }
                ?: supported.firstOrNull() ?: VideoQuality.ECONOMY

        fun limitManual(chosen: VideoQuality, supported: List<VideoQuality>): VideoQuality {
            require(chosen != VideoQuality.AUTO)
            if (chosen in supported) return chosen
            val constrained = supported.filter {
                it.width <= chosen.width && it.height <= chosen.height && it.fps <= chosen.fps
            }
            return constrained.maxByOrNull { it.width.toLong() * it.height * it.fps }
                ?: supported.firstOrNull() ?: VideoQuality.ECONOMY
        }
    }

    var active: VideoQuality = VideoQuality.HD
        private set
    private var good = 0
    private var bad = 0
    private var lastSwitchMs = 0L

    fun reset(profiles: List<VideoQuality>, nowMs: Long = 0): VideoQuality {
        good = 0
        bad = 0
        lastSwitchMs = nowMs
        active = safeInitial(profiles)
        return active
    }

    /** WebRTC adapts bitrate continuously; camera capture changes only after sustained evidence. */
    fun accept(health: ReceptionHealth, profiles: List<VideoQuality>, nowMs: Long): VideoQuality? {
        if (!health.valid || profiles.isEmpty()) return null
        val choices = profiles.distinct().sortedBy { ordered.indexOf(it) }
        if (active !in choices) {
            active = safeInitial(choices)
            good = 0; bad = 0; lastSwitchMs = nowMs
            return active
        }
        val isBad = health.lossPercent >= 5 || health.roundTripMs >= 400 ||
            health.framesDroppedPercent >= 12 ||
            (health.fps > 0.0 && health.fps < active.fps * 0.70)
        val isGood = health.lossPercent <= 1.5 && health.roundTripMs <= 200 &&
            health.framesDroppedPercent <= 5 && health.fps >= active.fps * 0.87
        if (isBad) { bad++; good = 0 }
        else if (isGood) { good++; bad = 0 }
        else { good = 0; bad = 0 }
        val pos = choices.indexOf(active)
        if (bad >= 2 && pos > 0 && nowMs - lastSwitchMs >= 8_000) {
            active = choices[pos - 1]
            bad = 0; good = 0; lastSwitchMs = nowMs
            return active
        }
        if (good >= 8 && pos < choices.lastIndex && nowMs - lastSwitchMs >= 32_000) {
            active = choices[pos + 1]
            bad = 0; good = 0; lastSwitchMs = nowMs
            return active
        }
        return null
    }
}
