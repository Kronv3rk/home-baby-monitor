package com.homebabymonitor.core

/**
 * Distinguishes the signalling/media connection from actual frame delivery.
 * All clocks must be monotonic (Android SystemClock.elapsedRealtime).
 * This is a fault detector, not a medical/safety guarantee.
 */
class SessionWatchdog(
    private val startupTimeoutMillis: Long = 30_000,
    private val heartbeatTimeoutMillis: Long = 8_000,
    private val videoTimeoutMillis: Long = 12_000
) {
    enum class Problem { NEVER_CONNECTED, HEARTBEAT_MISSING, VIDEO_MISSING }

    @Volatile private var startedAt: Long? = null
    @Volatile private var connectedAt: Long? = null
    @Volatile private var lastHeartbeat: Long? = null
    @Volatile private var lastFrame: Long? = null

    @Synchronized fun started(nowMillis: Long) {
        startedAt = nowMillis
        connectedAt = null
        lastHeartbeat = null
        lastFrame = null
    }

    @Synchronized fun connected(nowMillis: Long) {
        connectedAt = nowMillis
        lastHeartbeat = nowMillis
        lastFrame = null // Never treat a previous session's frame as current video.
    }

    fun heartbeat(nowMillis: Long) { if (connectedAt != null) lastHeartbeat = nowMillis }
    fun videoFrame(nowMillis: Long) { if (connectedAt != null) lastFrame = nowMillis }

    fun problem(nowMillis: Long): Problem? {
        val session = connectedAt
        if (session == null) {
            val started = startedAt ?: return null
            return if (nowMillis - started >= startupTimeoutMillis) Problem.NEVER_CONNECTED else null
        }
        val beat = lastHeartbeat ?: session
        if (nowMillis - beat > heartbeatTimeoutMillis) return Problem.HEARTBEAT_MISSING
        val frame = lastFrame ?: session
        if (nowMillis - frame > videoTimeoutMillis) return Problem.VIDEO_MISSING
        return null
    }
}
