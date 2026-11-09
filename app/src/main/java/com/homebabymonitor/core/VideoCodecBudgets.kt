package com.homebabymonitor.core

/**
 * Hardware limits for codecs that the bundled WebRTC factory *also* advertises.
 * A global maximum is not sufficient: a fast HEVC decoder cannot decode a 4K H.264 stream.
 */
data class VideoCodecBudgets(val rates: Map<String, Long>) {
    init {
        require(rates.size <= 5 && rates.all { (name, rate) ->
            name in KNOWN && rate in MIN_RATE..MAX_RATE
        })
    }

    /** No announcement is treated conservatively, never as native 4K support. */
    fun rateFor(codec: String?): Long =
        rates[codec?.uppercase()] ?: VideoEndpointLimit.UNKNOWN.maxPixelsPerSecond

    fun encode(): String =
        "RX_CODECS:" + KNOWN.filter { it in rates }
            .joinToString(",") { "$it=${rates.getValue(it)}" }

    companion object {
        val KNOWN = listOf("H265", "AV1", "VP9", "H264", "VP8")
        const val MIN_RATE = 640L * 480 * 15
        const val MAX_RATE = 7680L * 4320 * 120

        fun parse(wire: String): VideoCodecBudgets? {
            if (wire.length > 180 || !wire.startsWith("RX_CODECS:")) return null
            val value = wire.substringAfter(':')
            if (value.isEmpty()) return VideoCodecBudgets(emptyMap())
            val result = linkedMapOf<String, Long>()
            for (item in value.split(',')) {
                if (item.count { it == '=' } != 1) return null
                val key = item.substringBefore('=')
                val number = item.substringAfter('=').toLongOrNull() ?: return null
                if (key !in KNOWN || key in result || number !in MIN_RATE..MAX_RATE) return null
                result[key] = number
            }
            return VideoCodecBudgets(result)
        }
    }
}

/** Keep all WebRTC recovery formats (RTX/RED/FEC), changing only codec preference order. */
object VideoCodecPreference {
    private val codecOrder = VideoCodecBudgets.KNOWN.withIndex().associate { it.value to it.index }

    fun rank(codecName: String, hardware: VideoCodecBudgets): Int {
        val codec = codecName.uppercase()
        if (codec !in codecOrder) return 1000
        return if (codec in hardware.rates) codecOrder.getValue(codec)
        else 100 + codecOrder.getValue(codec)
    }

    fun ordered(names: List<String>, hardware: VideoCodecBudgets): List<String> =
        names.withIndex().sortedWith(
            compareBy<IndexedValue<String>> { rank(it.value, hardware) }.thenBy { it.index }
        ).map { it.value }
}
