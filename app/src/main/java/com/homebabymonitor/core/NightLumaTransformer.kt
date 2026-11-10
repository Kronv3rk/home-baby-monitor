package com.homebabymonitor.core

import java.nio.ByteBuffer

/**
 * Row-wise I420 plane operations. A single scratch row is reused for each plane;
 * no per-pixel JNI ByteBuffer calls, no mutation of caller buffer positions.
 */
object NightLumaTransformer {
    private val liftTable = ByteArray(256) { value ->
        (value * 1.25 + 14).toInt().coerceIn(0, 255).toByte()
    }

    fun liftLuma(
        source: ByteBuffer, sourceStride: Int,
        destination: ByteBuffer, destinationStride: Int, width: Int, height: Int
    ) = transfer(source, sourceStride, destination, destinationStride, width, height, true)

    fun copyPlane(
        source: ByteBuffer, sourceStride: Int,
        destination: ByteBuffer, destinationStride: Int, width: Int, height: Int
    ) = transfer(source, sourceStride, destination, destinationStride, width, height, false)

    private fun transfer(
        source: ByteBuffer, sourceStride: Int,
        destination: ByteBuffer, destinationStride: Int, width: Int, height: Int,
        transformLuma: Boolean
    ) {
        require(width > 0 && height > 0 &&
            sourceStride >= width && destinationStride >= width &&
            (height - 1L) * sourceStride + width <= source.capacity().toLong() &&
            (height - 1L) * destinationStride + width <= destination.capacity().toLong()) {
            "Invalid I420 plane size or stride"
        }
        val src = source.duplicate()
        val dst = destination.duplicate()
        val row = ByteArray(width)
        for (y in 0 until height) {
            src.position(y * sourceStride)
            src.get(row, 0, width)
            if (transformLuma) {
                for (x in 0 until width) row[x] = liftTable[row[x].toInt() and 0xff]
            }
            dst.position(y * destinationStride)
            dst.put(row, 0, width)
        }
    }
}
