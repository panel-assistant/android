package io.panelassistant.android.camera

import java.util.Base64

/** Annex-B byte-stream helpers: what `MediaCodec` emits and what RTP must never carry. */
object AnnexB {
    const val NAL_IDR = 5
    const val NAL_SEI = 6
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9

    fun nalType(nal: ByteArray): Int = nal[0].toInt() and 0x1F

    /**
     * Split an Annex-B buffer into NAL units without their start codes. Both 3- and 4-byte start codes
     * are recognised; bytes before the first start code are not a NAL unit and are dropped.
     */
    fun split(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<ByteArray> {
        val end = offset + length
        val units = ArrayList<ByteArray>(4)
        var start = -1
        var i = offset
        while (i + 2 < end) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                if (start >= 0) units += trimTrailingZeros(data, start, i)
                start = i + 3
                i += 3
            } else {
                i++
            }
        }
        if (start in 0..<end) units += trimTrailingZeros(data, start, end)
        return units.filter { it.isNotEmpty() }
    }

    /**
     * A 4-byte start code ends the previous unit with a zero byte that belongs to the code, not the
     * unit, and a buffer may end in trailing_zero_8bits; neither is payload. A NAL unit itself never
     * ends in a zero byte (the RBSP stop bit and emulation prevention see to that), so this is safe.
     */
    private fun trimTrailingZeros(data: ByteArray, from: Int, to: Int): ByteArray {
        var stop = to
        while (stop > from && data[stop - 1].toInt() == 0) stop--
        return data.copyOfRange(from, stop)
    }

    fun isKeyFrame(nals: List<ByteArray>): Boolean = nals.any { it.isNotEmpty() && nalType(it) == NAL_IDR }
}

/**
 * The sequence and picture parameter sets a client needs before it can decode anything. They arrive
 * from the encoder once, in the `BUFFER_FLAG_CODEC_CONFIG` buffer, and a client joining mid-stream
 * would never see that buffer — so they go into the SDP and are re-sent ahead of every IDR.
 */
class ParameterSets(val sps: ByteArray, val pps: ByteArray) {
    init {
        require(sps.size >= 4 && AnnexB.nalType(sps) == AnnexB.NAL_SPS) { "not an SPS" }
        require(pps.isNotEmpty() && AnnexB.nalType(pps) == AnnexB.NAL_PPS) { "not a PPS" }
    }

    /** `profile_idc`, `constraint_set` flags and `level_idc` as the six hex digits RFC 6184 wants. */
    val profileLevelId: String
        get() = sps.copyOfRange(1, 4).joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    fun spropParameterSets(): String {
        val b64 = Base64.getEncoder()
        return b64.encodeToString(sps) + "," + b64.encodeToString(pps)
    }

    override fun equals(other: Any?): Boolean =
        other is ParameterSets && sps.contentEquals(other.sps) && pps.contentEquals(other.pps)

    override fun hashCode(): Int = 31 * sps.contentHashCode() + pps.contentHashCode()

    companion object {
        /** From the codec-config buffer, or from any access unit that happens to carry both; null otherwise. */
        fun fromNalUnits(nals: List<ByteArray>): ParameterSets? {
            val sps = nals.firstOrNull { it.size >= 4 && AnnexB.nalType(it) == AnnexB.NAL_SPS } ?: return null
            val pps = nals.firstOrNull { it.isNotEmpty() && AnnexB.nalType(it) == AnnexB.NAL_PPS } ?: return null
            return ParameterSets(sps, pps)
        }

        fun fromCodecConfig(buffer: ByteArray): ParameterSets? = fromNalUnits(AnnexB.split(buffer))

        /**
         * From `MediaCodec`'s output format: `csd-0` and `csd-1` are Annex-B buffers, SPS then PPS on
         * most encoders, but some put both in `csd-0` and omit `csd-1` — so the two are split together
         * and the pair is taken from whatever they hold.
         */
        fun fromCsd(csd0: ByteArray?, csd1: ByteArray?): ParameterSets? {
            val nals = ArrayList<ByteArray>()
            csd0?.let { nals += AnnexB.split(it) }
            csd1?.let { nals += AnnexB.split(it) }
            return fromNalUnits(nals)
        }
    }
}

/**
 * The NAL units to put on the wire for one access unit: the parameter sets ahead of an IDR unless the
 * encoder already placed them there, and nothing extra for a non-key frame. Access-unit delimiters and
 * a bare parameter-set access unit (the config buffer itself) are dropped — the config is carried out
 * of band and re-sent by this function, never as a frame of its own.
 */
fun accessUnitForTransport(nals: List<ByteArray>, sets: ParameterSets?): List<ByteArray> {
    val payload = nals.filter { it.isNotEmpty() && AnnexB.nalType(it) != AnnexB.NAL_AUD }
    val parameterTypes = setOf(AnnexB.NAL_SPS, AnnexB.NAL_PPS)
    if (payload.none { AnnexB.nalType(it) !in parameterTypes }) return emptyList()
    if (!AnnexB.isKeyFrame(payload) || sets == null) return payload
    val carriesSps = payload.any { AnnexB.nalType(it) == AnnexB.NAL_SPS }
    val carriesPps = payload.any { AnnexB.nalType(it) == AnnexB.NAL_PPS }
    if (carriesSps && carriesPps) return payload
    return listOf(sets.sps, sets.pps) + payload.filter { AnnexB.nalType(it) !in parameterTypes }
}
