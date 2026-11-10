package com.homebabymonitor.core

/**
 * Keeps optional CPU night correction off the critical path of high-resolution capture.
 *
 * WebRTC camera textures remain untouched when night correction is disabled, the
 * capture profile is too large, or processing has exceeded its frame-time budget.
 * Uses System.nanoTime on Android; the class itself is independent of Android.
 */
class NightFrameBudget(
    private val maxPixels: Long = 1920L * 1080,
    private val maxFps: Int = 30,
    private val slowFrameNanos: Long = 12_000_000L,
    private val maxSlowFrames: Int = 3,
    private val coolDownNanos: Long = 30_000_000_000L
) {
    enum class Decision { DISABLED, SOFTWARE, TOO_EXPENSIVE, OVERLOADED }

    private var slowFrames = 0
    private var resumeAt = 0L
    @Volatile private var lastDurationNanos = 0L

    @Synchronized fun decide(
        enabled: Boolean, width: Int, height: Int, requestedFps: Int, nowNanos: Long
    ): Decision {
        if (!enabled) return Decision.DISABLED
        if (width <= 0 || height <= 0 || requestedFps !in 1..maxFps ||
            width.toLong() * height.toLong() > maxPixels) return Decision.TOO_EXPENSIVE
        if (nowNanos < resumeAt) return Decision.OVERLOADED
        return Decision.SOFTWARE
    }

    /** A short burst of high cost disables CPU correction for a bounded cooldown. */
    @Synchronized fun observeCost(durationNanos: Long, nowNanos: Long) {
        if (durationNanos < 0) return
        lastDurationNanos = durationNanos
        slowFrames = if (durationNanos > slowFrameNanos) slowFrames + 1 else 0
        if (slowFrames >= maxSlowFrames) {
            slowFrames = 0
            resumeAt = nowNanos + coolDownNanos
        }
    }

    fun lastProcessingMilliseconds(): Long = lastDurationNanos / 1_000_000
}
