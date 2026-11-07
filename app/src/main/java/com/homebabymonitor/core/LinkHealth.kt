package com.homebabymonitor.core

/** Heartbeat watchdog. No warning until the link actually connected once. */
class LinkHealth(private val heartbeatTimeoutMillis: Long = 8_000) {
    @Volatile private var lastSeen: Long? = null

    fun connectedAt(nowMillis: Long) { lastSeen = nowMillis }
    fun heartbeat(nowMillis: Long) { lastSeen = nowMillis }
    fun isStale(nowMillis: Long): Boolean = lastSeen?.let { nowMillis - it > heartbeatTimeoutMillis } ?: false
    fun reset() { lastSeen = null }
}
