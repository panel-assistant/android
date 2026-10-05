package io.panelassistant.android.camera

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

/** The one copy between the camera's frame and the encoder's input honours every stride on both sides. */
class YuvPlanesTest {

    private fun plane(width: Int, height: Int, rowStride: Int, pixelStride: Int, value: (x: Int, y: Int) -> Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(rowStride * height + 16)
        for (y in 0 until height) for (x in 0 until width) buffer.put(y * rowStride + x * pixelStride, value(x, y).toByte())
        return buffer
    }

    private fun readBack(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): List<Int> =
        (0 until height).flatMap { y -> (0 until width).map { x -> buffer.get(y * rowStride + x * pixelStride).toInt() and 0xFF } }

    @Test fun aPackedPlaneWithPaddedRowsCopiesRowByRow() {
        val src = plane(6, 3, rowStride = 8, pixelStride = 1) { x, y -> 10 * y + x }
        val dst = ByteBuffer.allocate(16 * 3)
        YuvPlanes.copyPlane(src, 8, 1, dst, 16, 1, width = 6, height = 3)
        assertEquals(readBack(src, 6, 3, 8, 1), readBack(dst, 6, 3, 16, 1))
        assertEquals("padding untouched", 0, dst.get(6).toInt())
        assertEquals("positions are not disturbed", 0, src.position())
        assertEquals(0, dst.position())
    }

    @Test fun interleavedChromaCopiesOnlyThisPlanesSamplesSoTheNeighbourPlaneCannotBleedThrough() {
        // Camera NV21-style chroma: pixel stride 2, so U and V bytes alternate. Only the plane's own
        // samples may move; the interleaved neighbours belong to the other plane, whose order on the
        // target side may differ (NV12 source into an NV21 target).
        val src = plane(4, 2, rowStride = 10, pixelStride = 2) { x, y -> 100 + 10 * y + x }
        for (y in 0 until 2) for (x in 0 until 3) src.put(y * 10 + x * 2 + 1, (50 + x).toByte())
        val dst = ByteBuffer.allocate(24)
        YuvPlanes.copyPlane(src, 10, 2, dst, 12, 2, width = 4, height = 2)
        assertEquals(readBack(src, 4, 2, 10, 2), readBack(dst, 4, 2, 12, 2))
        assertEquals("the byte between two samples stayed behind", 0, dst.get(12 + 3).toInt())
    }

    @Test fun differingPixelStridesFallBackToAPerSampleCopy() {
        val src = plane(4, 2, rowStride = 8, pixelStride = 2) { x, y -> 1 + 4 * y + x }
        val dst = ByteBuffer.allocate(8)
        YuvPlanes.copyPlane(src, 8, 2, dst, 4, 1, width = 4, height = 2)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), readBack(dst, 4, 2, 4, 1))
        val back = ByteBuffer.allocate(16)
        YuvPlanes.copyPlane(dst, 4, 1, back, 8, 2, width = 4, height = 2)
        assertEquals(readBack(src, 4, 2, 8, 2), readBack(back, 4, 2, 8, 2))
    }
}
