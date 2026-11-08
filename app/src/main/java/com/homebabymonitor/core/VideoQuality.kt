package com.homebabymonitor.core

/** Capture targets; supported size and frame rate are determined by the device at runtime. */
enum class VideoQuality(
    val width: Int,
    val height: Int,
    val fps: Int,
    val maxBitrateBps: Int,
    val title: String
) {
    ECONOMY(640, 480, 15, 750_000, "Экономия"),
    HD(1280, 720, 30, 3_000_000, "HD · 30"),
    FHD_60(1920, 1080, 60, 9_000_000, "Full HD · 60"),
    QHD_60(2560, 1440, 60, 16_000_000, "2K · 60");

    fun chooseFormat(supported: List<CaptureOption>): CaptureOption {
        val usable = supported.filter { it.width > 0 && it.height > 0 && it.fps > 0 }
        val sizeMatches = usable.filter { it.width <= width && it.height <= height }
        val fast = sizeMatches.filter { it.fps >= fps }
        return fast.maxByOrNull { it.width.toLong() * it.height }
            ?: sizeMatches.maxWithOrNull(compareBy<CaptureOption> { it.fps }.thenBy { it.width.toLong() * it.height })
            ?: usable.minWithOrNull(compareBy<CaptureOption> { it.width.toLong() * it.height }.thenByDescending { it.fps })
            ?: CaptureOption(width, height, fps)
    }

    companion object {
        fun fromWire(value: String): VideoQuality? = entries.firstOrNull { it.name == value }
    }
}

data class CaptureOption(val width: Int, val height: Int, val fps: Int) {
    val description: String get() = "${width}×${height} · до ${fps} к/с"
}
