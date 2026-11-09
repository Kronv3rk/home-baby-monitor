package com.homebabymonitor.core

/** Cumulative inbound RTP counters. They must be compared within the same connection. */
data class PlaybackTimingCounters(
    val jitterBufferSeconds: Double,
    val emittedFrames: Long,
    val decodeSeconds: Double,
    val decodedFrames: Long,
    val freezes: Long
) {
    val sane: Boolean get() = jitterBufferSeconds.isFinite() && decodeSeconds.isFinite() &&
        jitterBufferSeconds >= 0 && decodeSeconds >= 0 &&
        emittedFrames >= 0 && decodedFrames >= 0 && freezes >= 0
}

/**
 * Means are per sampling interval. These are receiver-side stages only:
 * they deliberately do NOT claim objective camera-to-display latency.
 */
data class PlaybackTimingInterval(
    val bufferMs: Double,
    val decodeMs: Double,
    val newFreezes: Long
)

class PlaybackTimingSampler {
    private var previous: PlaybackTimingCounters? = null

    fun reset() { previous = null }

    fun record(now: PlaybackTimingCounters): PlaybackTimingInterval? {
        if (!now.sane) { previous = null; return null }
        val prior = previous
        previous = now
        if (prior == null || now.emittedFrames < prior.emittedFrames ||
            now.decodedFrames < prior.decodedFrames ||
            now.jitterBufferSeconds < prior.jitterBufferSeconds ||
            now.decodeSeconds < prior.decodeSeconds ||
            now.freezes < prior.freezes) return null
        val newEmitted = now.emittedFrames - prior.emittedFrames
        val newDecoded = now.decodedFrames - prior.decodedFrames
        if (newEmitted <= 0 || newDecoded <= 0) return null
        val bufferMs = (now.jitterBufferSeconds - prior.jitterBufferSeconds) * 1000.0 / newEmitted
        val decodeMs = (now.decodeSeconds - prior.decodeSeconds) * 1000.0 / newDecoded
        if (!bufferMs.isFinite() || !decodeMs.isFinite() ||
            bufferMs !in 0.0..5_000.0 || decodeMs !in 0.0..5_000.0) return null
        return PlaybackTimingInterval(bufferMs, decodeMs, now.freezes - prior.freezes)
    }
}
