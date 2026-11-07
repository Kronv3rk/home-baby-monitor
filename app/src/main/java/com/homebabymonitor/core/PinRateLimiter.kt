package com.homebabymonitor.core

/** Limits failed authentication attempts to reduce local denial-of-service traffic. */
class PinRateLimiter(private val maxFailures: Int = 5, private val windowMillis: Long = 60_000) {
    private val failedAt = ArrayDeque<Long>()
    @Synchronized fun mayTry(nowMillis: Long): Boolean {
        while (failedAt.isNotEmpty() && nowMillis - failedAt.first() >= windowMillis) failedAt.removeFirst()
        return failedAt.size < maxFailures
    }
    @Synchronized fun failed(nowMillis: Long) {
        mayTry(nowMillis)
        failedAt.add(nowMillis)
    }
}
