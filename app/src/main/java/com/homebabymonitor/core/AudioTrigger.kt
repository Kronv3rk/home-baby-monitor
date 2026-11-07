package com.homebabymonitor.core

import kotlin.math.sqrt

/** Sound activity detector, NOT a clinical/ML cry classifier. Input is signed 16-bit little-endian PCM. */
class AudioTrigger(
    private val thresholdRms: Double = 2300.0,
    private val minimumLoudMillis: Long = 450,
    private val cooldownMillis: Long = 15_000
) {
    private var sustainedLoudMillis = 0L
    private var lastAlarmAt: Long? = null

    @Synchronized
    fun feed(pcm: ByteArray, sampleRate: Int, channels: Int, nowMillis: Long): Boolean {
        if (sampleRate <= 0 || channels <= 0 || pcm.size < 2) return false
        val sampleCount = pcm.size / 2
        var squares = 0.0
        var p = 0
        repeat(sampleCount) {
            val value = ((pcm[p].toInt() and 0xff) or (pcm[p + 1].toInt() shl 8)).toShort().toDouble()
            squares += value * value
            p += 2
        }
        val rms = sqrt(squares / sampleCount)
        val durationMillis = (1000L * sampleCount / (sampleRate * channels)).coerceAtLeast(1)
        if (rms >= thresholdRms) {
            sustainedLoudMillis = (sustainedLoudMillis + durationMillis).coerceAtMost(minimumLoudMillis + 100)
        } else {
            sustainedLoudMillis = 0
        }
        if (sustainedLoudMillis < minimumLoudMillis) return false
        val last = lastAlarmAt
        if (last != null && nowMillis - last < cooldownMillis) return false
        sustainedLoudMillis = 0
        lastAlarmAt = nowMillis
        return true
    }
}
