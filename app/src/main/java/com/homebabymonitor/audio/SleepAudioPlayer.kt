package com.homebabymonitor.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.homebabymonitor.core.SleepSound
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Locally synthesised audio: no recordings, downloads or third-party music files. */
class SleepAudioPlayer(private val onError: (String) -> Unit) {
    private val running = AtomicBoolean(false)
    @Volatile private var selected = SleepSound.OFF
    @Volatile private var endAtMs = 0L
    @Volatile private var volumePercent = 20
    private var worker: Thread? = null

    fun play(sound: SleepSound, minutes: Int, volume: Int) {
        stop()
        if (sound == SleepSound.OFF) return
        selected = sound
        endAtMs = SystemClock.elapsedRealtime() + minutes.coerceIn(1, 60) * 60_000L
        volumePercent = volume.coerceIn(0, 100)
        running.set(true)
        worker = Thread({ render() }, "sleep-audio").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        selected = SleepSound.OFF
        worker?.interrupt()
        worker = null
    }

    fun currentSound(): SleepSound {
        if (selected != SleepSound.OFF && SystemClock.elapsedRealtime() >= endAtMs) stop()
        return selected
    }

    fun remainingSeconds(): Int = if (currentSound() == SleepSound.OFF) 0
        else ((endAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0) / 1000L).toInt()

    private fun render() {
        val rate = 22050
        val chunk = ShortArray(2205)
        val minBuffer = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            running.set(false)
            selected = SleepSound.OFF
            onError("Не удалось открыть динамик")
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
            var samplePosition = 0L
            var rain = 0.0
            while (running.get() && SystemClock.elapsedRealtime() < endAtMs) {
                val kind = selected
                val gain = (volumePercent / 100.0) * 0.35
                for (i in chunk.indices) {
                    val t = samplePosition++.toDouble() / rate
                    val wave = if (kind == SleepSound.RAIN) {
                        rain = rain * 0.94 + (random.nextDouble() * 2.0 - 1.0) * 0.06
                        rain * 0.65
                    } else {
                        val notes = if (kind == SleepSound.MUSIC_BOX) box else lullaby
                        val secondsPerNote = if (kind == SleepSound.MUSIC_BOX) 0.85 else 1.1
                        val noteNumber = (t / secondsPerNote).toLong().toInt()
                        val note = notes[noteNumber % notes.size]
                        val frequency = 440.0 * 2.0.pow((note - 69) / 12.0)
                        val local = t % secondsPerNote
                        val envelope = (1.0 - exp(-local * 35.0)) * exp(-local * if (kind == SleepSound.MUSIC_BOX) 3.6 else 1.8)
                        val fundamental = sin(2 * PI * frequency * t)
                        val harmonic = sin(2 * PI * frequency * 2.0 * t) * 0.16
                        (fundamental + harmonic) * envelope * 0.55
                    }
                    chunk[i] = (wave * gain * 32767).toInt().coerceIn(-32768, 32767).toShort()
                }
                var written = 0
                while (running.get() && written < chunk.size) {
                    val count = track.write(chunk, written, chunk.size - written, AudioTrack.WRITE_BLOCKING)
                    if (count <= 0) error("Audio output stopped")
                    written += count
                }
            }
        } catch (_: InterruptedException) {
            // Stopped by user.
        } catch (e: Exception) {
            if (running.get()) onError("Не удалось воспроизвести звук: ${e.message}")
        } finally {
            running.set(false)
            selected = SleepSound.OFF
            try { track?.pause() } catch (_: Exception) {}
            try { track?.flush() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
        }
    }
}
