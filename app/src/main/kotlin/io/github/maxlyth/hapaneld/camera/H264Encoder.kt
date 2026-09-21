package io.github.maxlyth.hapaneld.camera

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import java.util.ArrayDeque

/** What the owner needs to know about a running encoder, for the status object and the plan's evidence table. */
data class EncoderFacts(
    val name: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val kbps: Int,
    /** The colour format the framework negotiated for buffer input, as its integer constant. */
    val colorFormat: Int?,
    val cbr: Boolean,
)

/** The encoder as the owner drives it; a seam so the owner's lifecycle around it is not welded to `MediaCodec`. */
interface VideoEncoder : AutoCloseable {
    interface Listener {
        fun onParameterSets(sets: ParameterSets)

        /** One access unit as NAL units without start codes, on the owner's thread. */
        fun onAccessUnit(nals: List<ByteArray>, keyFrame: Boolean, ptsUs: Long, bytes: Int)

        /** The codec failed after starting; [detail] is a classified token, never an exception message. */
        fun onEncoderError(detail: String)
    }

    val facts: EncoderFacts

    /** Copy [image] into a free input buffer. False means the encoder was still busy and the frame was dropped. */
    fun feed(image: Image, ptsUs: Long): Boolean

    fun requestKeyFrame()
}

sealed interface EncoderOpen {
    class Ready(val encoder: VideoEncoder) : EncoderOpen
    /** A classified reason the encoder could not be opened within the caps; never an exception message. */
    data class Refused(val detail: String) : EncoderOpen
}

/**
 * Hardware H.264 through `MediaCodec` in asynchronous buffer-input mode. Every callback lands on
 * [handler] — the owner's camera thread — so the free-input queue and the listener need no locking,
 * and nothing here ever touches Android's main thread.
 *
 * Buffer input rather than an input surface is deliberate: the owner already paces frames to the cap,
 * and only frames it chooses to hand over are encoded, so the frame-rate ceiling is a property of this
 * class rather than a promise the sensor cannot keep (the panels' HALs offer no fixed low rate).
 * The bitrate target is configured through [EncoderFormat], which also grants the component the whole
 * H.264 quantiser range: measured on `c2.rk.avc.encoder`, a ceiling left to the vendor held the slices
 * at a median QP of 22 and delivered 4716 kbps against a 1000 kbps target. The owner still measures the
 * delivered rate and reports it, because the target remains the vendor's to meet.
 */
class MediaCodecH264Encoder private constructor(
    private val codec: MediaCodec,
    override val facts: EncoderFacts,
    private val listener: VideoEncoder.Listener,
) : VideoEncoder {

    private val freeInputs = ArrayDeque<Int>()
    private var closed = false
    private var configSeen = false
    private var inlineParameterSetsLogged = false

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (!closed) freeInputs.addLast(index)
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (closed) return
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(bytes, 0, info.size)
                    deliver(bytes, info)
                }
            } catch (e: IllegalStateException) {
                listener.onEncoderError("output_${e.javaClass.simpleName}")
            } finally {
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (closed) return
            // Classified only: the vendor's diagnostic text is free-form and stays out of the logs.
            Log.w(TAG, "encoder ${facts.name} failed: code=${e.errorCode} recoverable=${e.isRecoverable} transient=${e.isTransient}")
            listener.onEncoderError(if (e.isRecoverable) "codec_recoverable" else "codec_error")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // The standard place for the parameter sets: csd-0 (SPS) and csd-1 (PPS). A codec-config
            // buffer, if the encoder also emits one, carries the same sets and is then ignored.
            if (closed || configSeen) return
            val sets = ParameterSets.fromCsd(bytesOf(format, "csd-0"), bytesOf(format, "csd-1")) ?: return
            configSeen = true
            Log.i(TAG, "encoder ${facts.name}: parameter sets arrived in the output format (csd-0/csd-1)")
            listener.onParameterSets(sets)
        }
    }

    private fun bytesOf(format: MediaFormat, key: String): ByteArray? {
        if (!format.containsKey(key)) return null
        val buffer = format.getByteBuffer(key) ?: return null
        val copy = ByteArray(buffer.remaining())
        buffer.duplicate().get(copy)
        return copy
    }

    private fun deliver(bytes: ByteArray, info: MediaCodec.BufferInfo) {
        val nals = AnnexB.split(bytes)
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            // Evidence for the plan's SPS/PPS question: the config buffer exists on this encoder.
            Log.i(TAG, "encoder ${facts.name}: a codec-config buffer arrived${if (configSeen) " after the output format already carried the sets" else ""}")
            if (configSeen) return
            val sets = ParameterSets.fromCodecConfig(bytes)
            if (sets == null) {
                listener.onEncoderError("config_without_parameter_sets")
                return
            }
            configSeen = true
            listener.onParameterSets(sets)
            return
        }
        val keyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 || AnnexB.isKeyFrame(nals)
        if (keyFrame && !inlineParameterSetsLogged) {
            inlineParameterSetsLogged = true
            val inline = ParameterSets.fromNalUnits(nals) != null
            Log.i(TAG, "encoder ${facts.name}: IDR access units ${if (inline) "carry" else "do not carry"} parameter sets inline")
            if (!configSeen && inline) listener.onParameterSets(requireNotNull(ParameterSets.fromNalUnits(nals)))
        }
        listener.onAccessUnit(nals, keyFrame, info.presentationTimeUs, bytes.size)
    }

    override fun feed(image: Image, ptsUs: Long): Boolean {
        if (closed) return false
        val index = freeInputs.pollFirst() ?: return false
        return try {
            val target = codec.getInputImage(index)
            if (target == null) {
                // A codec that refuses flexible image input cannot be fed from this path at all.
                freeInputs.addFirst(index)
                listener.onEncoderError("no_flexible_input_image")
                return false
            }
            val width = minOf(image.width, target.width)
            val height = minOf(image.height, target.height)
            val src = image.planes
            val dst = target.planes
            for (plane in 0 until 3) {
                val planeWidth = if (plane == 0) width else width / 2
                val planeHeight = if (plane == 0) height else height / 2
                YuvPlanes.copyPlane(
                    src[plane].buffer, src[plane].rowStride, src[plane].pixelStride,
                    dst[plane].buffer, dst[plane].rowStride, dst[plane].pixelStride,
                    planeWidth, planeHeight,
                )
            }
            target.close()
            codec.queueInputBuffer(index, 0, width * height * 3 / 2, ptsUs, 0)
            true
        } catch (e: IllegalStateException) {
            listener.onEncoderError("feed_${e.javaClass.simpleName}")
            false
        }
    }

    override fun requestKeyFrame() {
        if (closed) return
        runCatching {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        freeInputs.clear()
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    companion object {
        private const val TAG = "ha-paneld/camera-enc"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        /** Seconds between IDRs; one definition, in [EncoderFormat], because the format and the transport both read it. */
        const val IDR_INTERVAL_S = EncoderFormat.IDR_INTERVAL_S

        fun candidates(width: Int, height: Int): List<EncoderCandidate> =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, ignoreCase = true) } }
                .mapNotNull { info ->
                    val caps = runCatching { info.getCapabilitiesForType(MIME) }.getOrNull() ?: return@mapNotNull null
                    val video = caps.videoCapabilities ?: return@mapNotNull null
                    val hardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated else EncoderSelection.hardwareByName(info.name)
                    EncoderCandidate(
                        name = info.name,
                        hardware = hardware,
                        minBps = video.bitrateRange.lower,
                        maxBps = video.bitrateRange.upper,
                        sizeSupported = runCatching { video.isSizeSupported(width, height) }.getOrDefault(false),
                        cbr = runCatching {
                            caps.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
                        }.getOrDefault(false),
                    )
                }

        /** Open on [handler]'s thread. A refusal is classified; the caps are ceilings and are never exceeded here. */
        fun open(width: Int, height: Int, fps: Int, kbps: Int, handler: Handler, listener: VideoEncoder.Listener): EncoderOpen {
            val choice = when (val c = EncoderSelection.choose(candidates(width, height), kbps)) {
                is EncoderChoice.Refused -> return EncoderOpen.Refused(c.detail)
                is EncoderChoice.Chosen -> c
            }
            val keys = EncoderFormat.keys(fps = fps, bps = choice.bps, cbr = choice.cbr, sdkInt = Build.VERSION.SDK_INT)
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                keys.forEach { (key, value) -> setInteger(key, value) }
            }
            val codec = try {
                MediaCodec.createByCodecName(choice.name)
            } catch (e: Exception) {
                return EncoderOpen.Refused("create_${e.javaClass.simpleName}")
            }
            val negotiated: Int?
            try {
                val holder = arrayOfNulls<MediaCodecH264Encoder>(1)
                codec.setCallback(
                    object : MediaCodec.Callback() {
                        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) { holder[0]?.callback?.onInputBufferAvailable(codec, index) }
                        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) { holder[0]?.callback?.onOutputBufferAvailable(codec, index, info) }
                        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) { holder[0]?.callback?.onError(codec, e) }
                        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) { holder[0]?.callback?.onOutputFormatChanged(codec, format) }
                    },
                    handler,
                )
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                negotiated = runCatching { codec.inputFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT) }.getOrNull()
                val facts = EncoderFacts(choice.name, width, height, fps, choice.bps / 1_000, negotiated, choice.cbr)
                val encoder = MediaCodecH264Encoder(codec, facts, listener)
                holder[0] = encoder
                codec.start()
                Log.i(TAG, "encoder ${choice.name} started ${width}x$height@$fps ${choice.bps / 1_000}kbps cbr=${choice.cbr} qp_max=${keys[EncoderFormat.QP_MAX] ?: "vendor"} input_color_format=$negotiated")
                return EncoderOpen.Ready(encoder)
            } catch (e: Exception) {
                runCatching { codec.release() }
                return EncoderOpen.Refused("configure_${e.javaClass.simpleName}")
            }
        }
    }
}
