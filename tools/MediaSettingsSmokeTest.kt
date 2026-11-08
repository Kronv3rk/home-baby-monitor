import com.homebabymonitor.core.CaptureOption
import com.homebabymonitor.core.VideoQuality
import com.homebabymonitor.core.SleepSound
import com.homebabymonitor.core.SleepCommand

fun main() {
    val formats = listOf(CaptureOption(1280, 720, 60), CaptureOption(1920, 1080, 30))
    check(VideoQuality.FHD_30.chooseFormat(formats) == CaptureOption(1920, 1080, 30))
    check(VideoQuality.HD.chooseFormat(formats) == CaptureOption(1280, 720, 30))
    check(VideoQuality.FHD_30.chooseFormat(listOf(CaptureOption(1920, 1080, 60))).fps == 30)
    for (sound in SleepSound.entries) {
        check(SleepCommand.parse(SleepCommand(sound, 30, 20).encode())?.sound == sound)
    }
    println("Camera presets and sleep-sound protocol: OK")
}
