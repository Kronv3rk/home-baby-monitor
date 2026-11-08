import com.homebabymonitor.core.PairingLink
import com.homebabymonitor.core.CaptureOption
import com.homebabymonitor.core.VideoQuality
import com.homebabymonitor.core.SleepSound
import com.homebabymonitor.core.SleepCommand

fun main() {
    val code = "0123456789ABCDEFGHJK"
    val url = PairingLink.create("192.168.0.18", code)
    check(PairingLink.parse(url)?.ip == "192.168.0.18")
    check(PairingLink.parse(url)?.code == code)
    check(PairingLink.parse("https://evil.tld/?ip=192.168.0.18&key=$code") == null)
    check(PairingLink.parse("hbm://pair?v=2&ip=192.168.0.18&key=$code") == null)
    check(PairingLink.parse("hbm://pair?v=1&ip=8.8.8.8&key=$code") == null)
    check(PairingLink.parse("hbm://pair?v=1&ip=192.168.0.18&key=BAD") == null)
    check(PairingLink.parse("hbm://pair?v=1&ip=192.168.0.18&key=$code&bad=1") == null)
    check(VideoQuality.QHD_60.width == 2560)
    check(VideoQuality.QHD_60.height == 1440)
    check(VideoQuality.QHD_60.fps == 60)
    check(VideoQuality.FHD_60.fps == 60)
    check(VideoQuality.fromWire("QHD_60") == VideoQuality.QHD_60)
    check(VideoQuality.fromWire("invalid") == null)
    val command = SleepCommand(SleepSound.LULLABY, 30, 24)
    check(SleepCommand.parse(command.encode()) == command)
    check(SleepCommand.parse("MUSIC:RAIN:100000:1") == null)
    check(SleepCommand.parse("MUSIC:BAD:10:20") == null)
    check(SleepCommand.parse("MUSIC:OFF:30:101") == null)
    val options = listOf(CaptureOption(2560,1440,30), CaptureOption(1920,1080,60), CaptureOption(1280,720,60))
    check(VideoQuality.QHD_60.chooseFormat(options) == CaptureOption(1920,1080,60))
    check(VideoQuality.FHD_60.chooseFormat(options) == CaptureOption(1920,1080,60))
    check(VideoQuality.QHD_60.chooseFormat(options + CaptureOption(2560,1440,60)) == CaptureOption(2560,1440,60))
    val onlyFormat = CaptureOption(3840, 2160, 30)
    check(VideoQuality.QHD_60.chooseFormat(listOf(onlyFormat)) == onlyFormat)
    println("InteractionSmokeTest PASSED")
}
