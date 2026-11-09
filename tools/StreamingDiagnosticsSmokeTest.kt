import com.homebabymonitor.core.*

fun main() {
    val timing = PlaybackTimingSampler()
    check(timing.record(PlaybackTimingCounters(0.4, 20, 0.3, 18, 0)) == null)
    val result = timing.record(PlaybackTimingCounters(1.0, 50, 0.48, 36, 1))
    check(result != null)
    check(kotlin.math.abs(result!!.bufferMs - 20.0) < 0.001)
    check(kotlin.math.abs(result.decodeMs - 10.0) < 0.001)
    check(result.newFreezes == 1L)
    // Reset counters (new peer/track) must not create negative or impossible measurements.
    check(timing.record(PlaybackTimingCounters(0.01, 1, 0.01, 1, 0)) == null)
    check(timing.record(PlaybackTimingCounters(Double.NaN, 4, 0.3, 4, 0)) == null)
    val adaptation = AdaptiveVideoPolicy()
    val profiles = listOf(VideoQuality.ECONOMY, VideoQuality.HD, VideoQuality.FHD_30,
        VideoQuality.QHD_30, VideoQuality.UHD_30)
    adaptation.reset(profiles)
    var switches = 0
    // Simulated 24-hour session, alternating healthy Wi-Fi and short congestion bursts.
    repeat(43_200) { frame ->
        val seconds = frame * 2L
        val unstable = frame % 1800 in 0..8
        val sample = if (unstable) ReceptionHealth(8.0, 12.0, 650.0, 20.0)
        else ReceptionHealth(adaptation.active.fps.toDouble(), 0.2, 45.0, 0.0)
        if (adaptation.accept(sample, profiles, seconds * 1000) != null) switches++
    }
    check(switches < 400) { "Excessive format flapping: $switches" }
    println("24-hour synthetic adaptive soak and frame pipeline timing: OK")
}
