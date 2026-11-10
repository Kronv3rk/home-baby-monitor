package com.homebabymonitor.core

/**
 * Monotonic frame watchdog. The caller owns the camera restart and invokes next() on a
 * periodic worker, not from a frame callback. A bounded attempt window avoids infinite
 * restart loops on camera hardware that will not recover.
 */
class CaptureRecoveryGate(
    private val startupGraceMillis: Long = 15_000,
    private val stallMillis: Long = 10_000,
    private val cooldownMillis: Long = 20_000,
    private val maxRestarts: Int = 3,
    private val windowMillis: Long = 300_000
) {
    enum class Action { NONE, RESTART, EXHAUSTED }

    private var startedAt: Long? = null
    private var lastFrameAt: Long? = null
    private var lastAttemptAt: Long? = null
    private var exhaustedReported = false
    private val attempts = ArrayDeque<Long>()

    @Synchronized fun start(nowMillis: Long) {
        startedAt = nowMillis
        lastFrameAt = null
        lastAttemptAt = null
        exhaustedReported = false
        attempts.clear()
    }

    @Synchronized fun frame(nowMillis: Long) {
        if (startedAt != null) {
            lastFrameAt = nowMillis
            exhaustedReported = false
        }
    }

    @Synchronized fun next(nowMillis: Long): Action {
        val began = startedAt ?: return Action.NONE
        if (nowMillis < began) return Action.NONE
        val mostRecent = lastFrameAt
        val stalled = if (mostRecent == null) nowMillis - began >= startupGraceMillis
            else nowMillis >= mostRecent && nowMillis - mostRecent > stallMillis
        if (!stalled) return Action.NONE
        if (lastAttemptAt != null && nowMillis - lastAttemptAt!! < cooldownMillis)
            return Action.NONE
        while (attempts.isNotEmpty() && nowMillis - attempts.first() >= windowMillis)
            attempts.removeFirst()
        if (attempts.size >= maxRestarts) {
            if (exhaustedReported) return Action.NONE
            exhaustedReported = true
            return Action.EXHAUSTED
        }
        attempts.addLast(nowMillis)
        lastAttemptAt = nowMillis
        return Action.RESTART
    }
}
