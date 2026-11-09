package com.homebabymonitor.core

/** Camera capture profiles. The actual sensor format and transport rate depend on hardware and Wi-Fi. */
enum class VideoQuality(
    val width: Int,
    val height: Int,
    val fps: Int,
    val maxBitrateBps: Int,
    val title: String
) {
    AUTO(3840, 2160, 60, 40_000_000, "Автоматически"),
    ECONOMY(640, 480, 15, 900_000, "Экономия · 15"),
    HD(1280, 720, 30, 4_500_000, "HD · 30"),
    FHD_30(1920, 1080, 30, 7_000_000, "Full HD · 30"),
    FHD_60(1920, 1080, 60, 12_000_000, "Full HD · 60"),
    QHD_30(2560, 1440, 30, 12_000_000, "2K · 30"),
    QHD_60(2560, 1440, 60, 20_000_000, "2K · 60"),
    UHD_30(3840, 2160, 30, 25_000_000, "4K · 30"),
    UHD_60(3840, 2160, 60, 40_000_000, "4K · 60");

    fun chooseFormat(supported: List<CaptureOption>): CaptureOption {
        val usable = supported.filter { it.width > 0 && it.height > 0 && it.fps > 0 }
        val fitting = usable.filter { it.width <= width && it.height <= height }
        // Prefer full requested resolution at requested FPS, rather than reducing resolution
        // solely because a smaller sensor format advertises a higher frame rate.
        val targetFps = fitting.filter { it.fps >= fps }
        val chosen = targetFps.maxWithOrNull(compareBy<CaptureOption> { it.width.toLong() * it.height }.thenBy { it.fps })
            ?: fitting.maxWithOrNull(compareBy<CaptureOption> { it.fps }.thenBy { it.width.toLong() * it.height })
            ?: usable.minWithOrNull(compareBy<CaptureOption> { it.width.toLong() * it.height }.thenByDescending { it.fps })
            ?: CaptureOption(width, height, fps)
        return chosen.copy(fps = minOf(chosen.fps, fps))
    }

    companion object {
        fun fromWire(value: String): VideoQuality? = entries.firstOrNull { it.name == value }
    }
}

data class CaptureOption(val width: Int, val height: Int, val fps: Int) {
    val description: String get() = "${width}×${height} · до ${fps} к/с"
}
