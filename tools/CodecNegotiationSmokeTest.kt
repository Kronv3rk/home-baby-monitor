import com.homebabymonitor.core.*

fun main() {
    val max4k = 3840L * 2160 * 60
    val full = VideoCodecBudgets(mapOf("H264" to 1920L * 1080 * 30, "H265" to max4k, "AV1" to max4k))
    check(VideoCodecBudgets.parse(full.encode()) == full)
    check(VideoCodecBudgets.parse("RX_CODECS:H264=120,H264=121") == null)
    check(VideoCodecBudgets.parse("RX_CODECS:H264=-1") == null)
    check(VideoCodecBudgets.parse("RX_CODECS:H265=99999999999999") == null)
    check(VideoCodecBudgets.parse("RX_CODECS:CATVIDEO=2000000") == null)
    check(VideoCodecBudgets.parse("RX_CODECS:H264=0") == null)
    check(VideoCodecBudgets.parse("RX_CODECS:H264=abc") == null)
    check(VideoCodecBudgets.parse("RX_CODECS:" + "a".repeat(260)) == null)
    check(full.rateFor("H264") < max4k)
    check(full.rateFor("H265") == max4k)
    check(full.rateFor("VP8") <= 1920L * 1080 * 30)
    val choices = listOf("VP8", "H264", "RTX", "H265", "AV1", "RED", "VP9")
    val prefer = VideoCodecPreference.ordered(choices, full)
    check(prefer.first() == "H265" || prefer.first() == "AV1")
    check(prefer.takeLast(2) == listOf("RTX", "RED"))
    val legacy = VideoCodecPreference.ordered(choices, VideoCodecBudgets(mapOf("H264" to 1920L * 1080 * 30)))
    check(legacy.first() == "H264")
    val camera = listOf(CaptureOption(1280,720,30),CaptureOption(1920,1080,30),CaptureOption(3840,2160,60))
    val sender = VideoCodecBudgets(mapOf("H264" to 1920L*1080*30, "H265" to max4k))
    val receiver = VideoCodecBudgets(mapOf("H264" to max4k, "H265" to 1920L*1080*30))
    val h265Profiles = AdaptiveVideoPolicy.supported(camera, 
        VideoEndpointLimit(sender.rateFor("H265")), VideoEndpointLimit(receiver.rateFor("H265")))
    check(VideoQuality.UHD_60 !in h265Profiles)
    val highBoth = AdaptiveVideoPolicy.supported(camera,
        VideoEndpointLimit(sender.rateFor("H265")), VideoEndpointLimit(max4k))
    check(VideoQuality.UHD_60 in highBoth)
    println("Codec agreement, downgrade, strict codec messages: OK")
}
