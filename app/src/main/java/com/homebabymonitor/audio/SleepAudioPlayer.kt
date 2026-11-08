package com.homebabymonitor.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.homebabymonitor.core.SleepSound
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Locally synthesised audio: no recordings, downloads or third-party music files. */
class SleepAudioPlayer(private val onError: (String) -> Unit) {
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    @Volatile private var selected = SleepSound.OFF
    @Volatile private var endAtMs = 0L
    @Volatile private var volumePercent = 20
    private var worker: Thread? = null

    @Synchronized fun play(sound: SleepSound, minutes: Int, volume: Int) {
        stop()
        if (sound == SleepSound.OFF) return
        val sessionId = generation.incrementAndGet()
        selected = sound
        endAtMs = SystemClock.elapsedRealtime() + minutes.coerceIn(1, 60) * 60_000L
        volumePercent = volume.coerceIn(0, 100)
        running.set(true)
        worker = Thread({ render(sessionId) }, "sleep-audio").apply { isDaemon = true; start() }
    }

    @Synchronized fun stop() {
        generation.incrementAndGet()
        running.set(false)
        selected = SleepSound.OFF
        worker?.interrupt()
        worker = null
    }

    fun setVolume(percent: Int) { volumePercent = percent.coerceIn(0, 100) }

    fun volume(): Int = volumePercent

    fun currentSound(): SleepSound {
        if (selected != SleepSound.OFF && SystemClock.elapsedRealtime() >= endAtMs) stop()
        return selected
    }

    fun remainingSeconds(): Int = if (currentSound() == SleepSound.OFF) 0
        else ((endAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0) / 1000L).toInt()

    private fun render(sessionId: Long) {
        val rate = 22050
        val chunk = ShortArray(2205)
        val minBuffer = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            if (generation.compareAndSet(sessionId, sessionId + 1)) {
                running.set(false)
                selected = SleepSound.OFF
                onError("Не удалось открыть динамик")
            }
            return
        }
        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuffer, chunk.size * 2 * 3))
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) error("AudioTrack unavailable")
            track.play()
            val random = Random(1987)
            val lullaby = intArrayOf(60, 64, 67, 64, 62, 65, 69, 65, 60, 64, 67, 72, 69, 65, 64, 60)
            val box = intArrayOf(72, 69, 67, 64, 65, 67, 69, 60, 64, 67, 72, 67, 64, 62, 60, 60)
            val lullaby2 = intArrayOf(67, 65, 64, 62, 60, 62, 64, 65, 67, 69, 67, 64, 62, 60, 60, 60)
            val bells = intArrayOf(76, 72, 79, 74, 72, 76, 81, 79, 76, 74, 72, 67, 69, 72, 72, 72)
            var samplePosition = 0L
            var rain = 0.0
            while (running.get() && generation.get() == sessionId && SystemClock.elapsedRealtime() < endAtMs) {
                val kind = selected
                val gain = (volumePercent / 100.0) * 0.35
                for (i in chunk.indices) {
                    val t = samplePosition++.toDouble() / rate
                    val noise = random.nextDouble() * 2.0 - 1.0
                    rain = rain * 0.94 + noise * 0.06
                    val wave = when (kind) {
                        SleepSound.RAIN -> rain * 0.65
                        SleepSound.WHITE_NOISE -> noise * 0.22
                        SleepSound.SOFT_NOISE -> rain * 0.48
                        SleepSound.OCEAN -> {
                            val swell = 0.15 + 0.75 * (0.5 + 0.5 * sin(2 * PI * t / 7.0))
                            rain * swell * 0.64
                        }
                        SleepSound.WIND -> {
                            val gust = 0.35 + 0.25 * sin(2 * PI * t / 13.0)
                            rain * gust
                        }
                        SleepSound.HEARTBEAT -> {
                            val phase = t % 1.25
                            val first = if (phase < 0.13) exp(-phase * 34.0) else 0.0
                            val second = if (phase in 0.21..0.35) exp(-(phase - 0.21) * 38.0) * 0.7 else 0.0
                            (first + second) * sin(2 * PI * 86.0 * t) * 0.62
                        }
                        SleepSound.OFF -> 0.0
                        else -> {
                            val notes = when (kind) {
                                SleepSound.MUSIC_BOX -> box
                                SleepSound.LULLABY_2 -> lullaby2
                                SleepSound.BELLS -> bells
                                else -> lullaby
                            }
                            val secondsPerNote = when (kind) {
                                SleepSound.MUSIC_BOX -> 0.85
                                SleepSound.BELLS -> 1.25
                                else -> 1.1
                            }
                            val noteNumber = (t / secondsPerNote).toLong().toInt()
                            val note = notes[noteNumber % notes.size]
                            val frequency = 440.0 * 2.0.pow((note - 69) / 12.0)
                            val local = t % secondsPerNote
                            val decay = if (kind == SleepSound.MUSIC_BOX || kind == SleepSound.BELLS) 3.6 else 1.8
                            val envelope = (1.0 - exp(-local * 35.0)) * exp(-local * decay)
                            val fundamental = sin(2 * PI * frequency * t)
                            val harmonic = sin(2 * PI * frequency * 2.0 * t) * 0.16
                            (fundamental + harmonic) * envelope * 0.55
                        }
                    }
                    chunk[i] = (wave * gain * 32767).toInt().coerceIn(-32768, 32767).toShort()
                }
                var written = 0
                while (running.get() && generation.get() == sessionId && written < chunk.size) {
                    val count = track.write(chunk, written, chunk.size - written, AudioTrack.WRITE_BLOCKING)
                    if (count <= 0) error("Audio output stopped")
                    written += count
                }
            }
        } catch (_: InterruptedException) {
            // Stopped by user.
        } catch (e: Exception) {
            if (running.get() && generation.get() == sessionId)
                onError("Не удалось воспроизвести звук: ${e.message}")
        } finally {
            if (generation.compareAndSet(sessionId, sessionId + 1)) {
                running.set(false)
                selected = SleepSound.OFF
            }
            try { track?.pause() } catch (_: Exception) {}
            try { track?.flush() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
        }
    }
}
