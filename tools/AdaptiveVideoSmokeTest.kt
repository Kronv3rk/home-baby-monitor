import com.homebabymonitor.core.*

fun main() {
    val formats = listOf(CaptureOption(1920, 1080, 30), CaptureOption(1920, 1080, 60), CaptureOption(3840, 2160, 60))
    val local = VideoEndpointLimit(3840L * 2160 * 60)
    val conservative = AdaptiveVideoPolicy.supported(formats, local, VideoEndpointLimit.UNKNOWN)
    check(VideoQuality.UHD_60 !in conservative)
    check(VideoQuality.FHD_30 in conservative)
    val supported = AdaptiveVideoPolicy.supported(formats, local, local)
    check(VideoQuality.UHD_60 in supported)
    val controller = AdaptiveVideoPolicy()
    check(controller.reset(supported) == VideoQuality.FHD_30)
    val healthy = ReceptionHealth(30.0, 0.0, 40.0)
    repeat(7) { check(controller.accept(healthy, supported, 32000L + 2000L * it) == null) }
    check(controller.accept(healthy, supported, 46000L) == VideoQuality.FHD_60)
    check(controller.accept(ReceptionHealth(5.0, 10.0, 500.0), supported, 50000L) == null)
    check(controller.accept(ReceptionHealth(5.0, 10.0, 500.0), supported, 56000L) == VideoQuality.FHD_30)
    check(ReceptionHealth.parse(ReceptionHealth(30.0, 1.0, 85.0).encode()) != null)
    check(ReceptionHealth.parse("RX_HEALTH:20000:0:0:0") == null)
    println("Adaptive video capability and congestion policy: OK")
}
