package com.homebabymonitor.core

/**
 * Repeat persistent faults until acknowledgement; a new type or a resolved-and-returned
 * problem starts a new incident. Uses elapsedRealtime, never wall clock time.
 */
class IncidentAlarmController(private val reminderIntervalMillis: Long = 60_000) {
    private var active: String? = null
    private var acknowledged = false
    private var lastNotificationAt: Long? = null

    @Synchronized fun observe(problem: String?, nowMillis: Long): Boolean {
        if (problem == null) {
            active = null
            lastNotificationAt = null
            acknowledged = false
            return false
        }
        if (active != problem) {
            active = problem
            acknowledged = false
            lastNotificationAt = nowMillis
            return true
        }
        if (acknowledged) return false
        val last = lastNotificationAt ?: nowMillis
        if (nowMillis < last || nowMillis - last < reminderIntervalMillis) return false
        lastNotificationAt = nowMillis
        return true
    }

    @Synchronized fun acknowledge() { if (active != null) acknowledged = true }

    @Synchronized fun activeProblem(): String? = active
    @Synchronized fun isAcknowledged(): Boolean = acknowledged
}
