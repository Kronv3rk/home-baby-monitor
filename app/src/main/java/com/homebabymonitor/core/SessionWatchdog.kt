package com.homebabymonitor.core

/**
 * Independent heartbeat and decoded-frame checks using a monotonic clock.
 * Reconnects must start a fresh epoch; an old session cannot provide fresh heartbeats.
 */
class SessionWatchdog(
    private val startupTimeoutMillis: Long = 30_000,
    private val heartbeatTimeoutMillis: Long = 8_000,
    private val videoTimeoutMillis: Long = 12_000,
    private val reconnectTimeoutMillis: Long = 8_000
) {
    enum class Problem { NEVER_CONNECTED, CONNECTION_LOST, HEARTBEAT_MISSING, VIDEO_MISSING }

    private var startedAt: Long? = null
    private var connectedAt: Long? = null
    private var disconnectedAt: Long? = null
    private var lastHeartbeat: Long? = null
    private var lastFrame: Long? = null
    private var wasConnected = false

    @Synchronized fun started(nowMillis: Long) {
        startedAt = nowMillis
        connectedAt = null
        disconnectedAt = null
        lastHeartbeat = null
        lastFrame = null
        wasConnected = false
    }

    @Synchronized fun connected(nowMillis: Long) {
        connectedAt = nowMillis
        disconnectedAt = null
        lastHeartbeat = nowMillis
        lastFrame = null
        wasConnected = true
    }

    @Synchronized fun disconnected(nowMillis: Long) {
        if (startedAt == null) return
        connectedAt = null
        lastFrame = null
        lastHeartbeat = null
        if (wasConnected && disconnectedAt == null) disconnectedAt = nowMillis
    }

    @Synchronized fun heartbeat(nowMillis: Long) {
        if (connectedAt != null) lastHeartbeat = nowMillis
    }

    @Synchronized fun videoFrame(nowMillis: Long) {
        if (connectedAt != null) lastFrame = nowMillis
    }

    @Synchronized fun problem(nowMillis: Long): Problem? {
        val connected = connectedAt
        if (connected == null) {
            val disconnected = disconnectedAt
            if (disconnected != null) {
                return if (nowMillis >= disconnected &&
                    nowMillis - disconnected >= reconnectTimeoutMillis)
                    Problem.CONNECTION_LOST else null
            }
            val started = startedAt ?: return null
            return if (nowMillis >= started && nowMillis - started >= startupTimeoutMillis)
                Problem.NEVER_CONNECTED else null
        }
        val beat = lastHeartbeat ?: connected
        if (nowMillis >= beat && nowMillis - beat > heartbeatTimeoutMillis)
            return Problem.HEARTBEAT_MISSING
        val frame = lastFrame ?: connected
        if (nowMillis >= frame && nowMillis - frame > videoTimeoutMillis)
            return Problem.VIDEO_MISSING
        return null
    }
}
