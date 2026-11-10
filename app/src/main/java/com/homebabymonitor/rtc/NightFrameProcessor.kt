package com.homebabymonitor.rtc

import android.util.Log
import com.homebabymonitor.core.NightFrameBudget
import com.homebabymonitor.core.NightLumaTransformer
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoProcessor
import org.webrtc.VideoSink

/**
 * Optional low-light luma boost. On high-resolution / high-rate captures, the
 * original camera texture is forwarded unchanged rather than read back to CPU.
 * Software enhancement cannot replace exposure control or infrared optics.
 */
class NightFrameProcessor(private val onCapture: (Int, Int) -> Unit = { _, _ -> }) : VideoProcessor {
    @Volatile var enabled: Boolean = false
    @Volatile private var sink: VideoSink? = null
    @Volatile private var requestedFps: Int = 30
    @Volatile private var lastWidth: Int = 0
    @Volatile private var lastHeight: Int = 0
    private val budget = NightFrameBudget()

    fun configureCaptureFps(fps: Int) { requestedFps = fps }

    /** Stable wire status; not a claim that software processing remains active at 4K. */
    fun modeCode(): String = when (
        budget.decide(enabled, lastWidth, lastHeight, requestedFps, System.nanoTime())
    ) {
        NightFrameBudget.Decision.DISABLED -> "OFF"
        NightFrameBudget.Decision.SOFTWARE -> "SOFTWARE"
        NightFrameBudget.Decision.TOO_EXPENSIVE -> "PASSTHROUGH"
        NightFrameBudget.Decision.OVERLOADED -> "COOLDOWN"
    }

    fun lastProcessingMs(): Long = budget.lastProcessingMilliseconds()

    override fun setSink(sink: VideoSink?) { this.sink = sink }
    override fun onCapturerStarted(success: Boolean) {}
    override fun onCapturerStopped() {}

    override fun onFrameCaptured(frame: VideoFrame) {
        lastWidth = frame.buffer.width
        lastHeight = frame.buffer.height
        onCapture(lastWidth, lastHeight)
        val destination = sink ?: return
        if (budget.decide(enabled, lastWidth, lastHeight, requestedFps,
                System.nanoTime()) != NightFrameBudget.Decision.SOFTWARE) {
            // The fast path never calls toI420, allocates a frame, or copies pixel data.
            destination.onFrame(frame)
            return
        }

        val began = System.nanoTime()
        var adjusted: VideoFrame? = null
        val source = try { frame.buffer.toI420() }
            catch (e: RuntimeException) {
                Log.w("NightFrameProcessor", "Cannot read low-light frame; forwarding original", e)
                null
            }
        if (source == null) {
            destination.onFrame(frame)
            return
        }
        try {
            val copy = JavaI420Buffer.allocate(source.width, source.height)
            try {
                NightLumaTransformer.liftLuma(source.dataY, source.strideY,
                    copy.dataY, copy.strideY, source.width, source.height)
                val chromaWidth = (source.width + 1) / 2
                val chromaHeight = (source.height + 1) / 2
                NightLumaTransformer.copyPlane(source.dataU, source.strideU,
                    copy.dataU, copy.strideU, chromaWidth, chromaHeight)
                NightLumaTransformer.copyPlane(source.dataV, source.strideV,
                    copy.dataV, copy.strideV, chromaWidth, chromaHeight)
                adjusted = VideoFrame(copy, frame.rotation, frame.timestampNs)
            } finally {
                if (adjusted == null) copy.release()
            }
        } catch (e: RuntimeException) {
            Log.w("NightFrameProcessor", "Night correction failed; preserving original frame", e)
        } finally {
            source.release()
            budget.observeCost(System.nanoTime() - began, System.nanoTime())
        }
        val delivered = adjusted
        if (delivered == null) destination.onFrame(frame)
        else try { destination.onFrame(delivered) } finally { delivered.release() }
    }
}
