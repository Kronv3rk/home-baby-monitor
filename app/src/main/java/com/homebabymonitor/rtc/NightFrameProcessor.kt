package com.homebabymonitor.rtc

import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoProcessor
import org.webrtc.VideoSink
import java.nio.ByteBuffer

/** Optional software luma lift. It cannot create detail in total darkness or replace infrared optics. */
class NightFrameProcessor(private val onCapture: (Int, Int) -> Unit = { _, _ -> }) : VideoProcessor {
    @Volatile var enabled: Boolean = false
    @Volatile private var sink: VideoSink? = null

    override fun setSink(sink: VideoSink?) { this.sink = sink }
    override fun onCapturerStarted(success: Boolean) {}
    override fun onCapturerStopped() {}

    override fun onFrameCaptured(frame: VideoFrame) {
        onCapture(frame.buffer.width, frame.buffer.height)
        val destination = sink ?: return
        if (!enabled) { destination.onFrame(frame); return }
        val source = frame.buffer.toI420() ?: return
        try {
            val copy = JavaI420Buffer.allocate(source.width, source.height)
            copyPlane(source.dataY, source.strideY, copy.dataY, copy.strideY, source.width, source.height, boost = true)
            copyPlane(source.dataU, source.strideU, copy.dataU, copy.strideU, (source.width + 1) / 2, (source.height + 1) / 2)
            copyPlane(source.dataV, source.strideV, copy.dataV, copy.strideV, (source.width + 1) / 2, (source.height + 1) / 2)
            val adjusted = VideoFrame(copy, frame.rotation, frame.timestampNs)
            try { destination.onFrame(adjusted) } finally { adjusted.release() }
        } finally { source.release() }
    }

    private fun copyPlane(src: ByteBuffer, srcStride: Int, dst: ByteBuffer, dstStride: Int, width: Int, height: Int, boost: Boolean = false) {
        for (row in 0 until height) for (col in 0 until width) {
            val value = src.get(row * srcStride + col).toInt() and 255
            // YUV luma lift without increasing chroma saturation.
            val transformed = if (boost) (value * 1.25 + 14).toInt().coerceIn(0, 255) else value
            dst.put(row * dstStride + col, transformed.toByte())
        }
    }
}
