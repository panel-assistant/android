package io.panelassistant.android.camera

import java.nio.ByteBuffer

/**
 * Copies one plane of a `YUV_420_888` image into one plane of a codec's flexible input image, honouring
 * both sides' row and pixel strides. This is the whole cost of feeding the encoder from the paced
 * frame path instead of a second capture surface: one copy per encoded frame, at most a few
 * milliseconds for 720p, in exchange for a frame-rate ceiling that holds by construction.
 *
 * Only a packed plane (pixel stride 1 on both sides — luma, and planar chroma) takes the row-run fast
 * path. Interleaved chroma is copied sample by sample even when both sides interleave, because a run
 * copy would carry the neighbouring plane's bytes along and an NV12 source into an NV21 target would
 * silently swap the colours; the per-sample copy only ever moves this plane's own samples.
 *
 * Buffers are addressed absolutely; their positions and limits are neither read nor changed.
 */
object YuvPlanes {
    fun copyPlane(
        src: ByteBuffer,
        srcRowStride: Int,
        srcPixelStride: Int,
        dst: ByteBuffer,
        dstRowStride: Int,
        dstPixelStride: Int,
        width: Int,
        height: Int,
    ) {
        require(width > 0 && height > 0)
        require(srcPixelStride >= 1 && dstPixelStride >= 1)
        if (srcPixelStride == 1 && dstPixelStride == 1) {
            val row = ByteArray(width)
            val from = src.duplicate()
            val to = dst.duplicate()
            for (y in 0 until height) {
                from.position(y * srcRowStride)
                from.get(row, 0, width)
                to.position(y * dstRowStride)
                to.put(row, 0, width)
            }
            return
        }
        for (y in 0 until height) {
            val srcRow = y * srcRowStride
            val dstRow = y * dstRowStride
            for (x in 0 until width) {
                dst.put(dstRow + x * dstPixelStride, src.get(srcRow + x * srcPixelStride))
            }
        }
    }
}
