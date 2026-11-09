package com.homebabymonitor.rtc

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.homebabymonitor.core.AdaptiveVideoPolicy
import com.homebabymonitor.core.VideoEndpointLimit

/** Runtime capability probing: no device-model lists and no hard-coded manufacturer exceptions. */
internal object DeviceVideoCapabilities {
    data class Result(val limit: VideoEndpointLimit, val codecs: String)

    private fun mime(codecName: String): String? = when (codecName.uppercase()) {
        "H264" -> MediaFormat.MIMETYPE_VIDEO_AVC
        "VP8" -> MediaFormat.MIMETYPE_VIDEO_VP8
        "VP9" -> MediaFormat.MIMETYPE_VIDEO_VP9
        "AV1" -> if (Build.VERSION.SDK_INT >= 29) MediaFormat.MIMETYPE_VIDEO_AV1 else null
        else -> null // No claim of HEVC unless the bundled WebRTC build advertises it.
    }

    fun probe(encoding: Boolean, webrtcCodecNames: Set<String>): Result {
        val allowedMimes = webrtcCodecNames.mapNotNull(::mime).toSet()
        if (allowedMimes.isEmpty()) return Result(VideoEndpointLimit.UNKNOWN, "нет доступных кодеков WebRTC")
        return try {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            val supported = LinkedHashSet<String>()
            var fastest = 0L
            for (codec in codecs) {
                if (codec.isEncoder != encoding || !isHardware(codec)) continue
                for (mime in allowedMimes) {
                    if (codec.supportedTypes.none { it.equals(mime, true) }) continue
                    val video = try { codec.getCapabilitiesForType(mime).videoCapabilities }
                        catch (_: Exception) { null } ?: continue
                    supported.add(mime.substringAfterLast('/'))
                    for (profile in AdaptiveVideoPolicy.ordered) {
                        if (profile.width <= 0 || profile.height <= 0) continue
                        val works = try {
                            val dimensions = video.areSizeAndRateSupported(
                                profile.width, profile.height, profile.fps.toDouble()
                            )
                            val guaranteed = if (Build.VERSION.SDK_INT >= 29) {
                                val points = video.supportedPerformancePoints
                                points.isNullOrEmpty() || points.any { published ->
                                    published.covers(MediaCodecInfo.VideoCapabilities.PerformancePoint(
                                        profile.width, profile.height, profile.fps))
                                }
                            } else true
                            dimensions && guaranteed
                        } catch (_: Exception) { false }
                        if (works) fastest = maxOf(
                            fastest, profile.width.toLong() * profile.height * profile.fps
                        )
                    }
                }
            }
            Result(
                if (fastest > 0) VideoEndpointLimit(fastest) else VideoEndpointLimit.UNKNOWN,
                if (supported.isEmpty()) "аппаратный кодек не обнаружен"
                    else supported.joinToString(", ").uppercase()
            )
        } catch (e: Exception) {
            Log.w("DeviceVideoCapabilities", "Codec capabilities unavailable", e)
            Result(VideoEndpointLimit.UNKNOWN, "возможности кодеков неизвестны")
        }
    }

    private fun isHardware(codec: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return codec.isHardwareAccelerated
        val name = codec.name.lowercase()
        return !name.startsWith("omx.google.") && !name.startsWith("c2.android.") &&
            !name.contains("software") && !name.startsWith("omx.ffmpeg.")
    }
}
