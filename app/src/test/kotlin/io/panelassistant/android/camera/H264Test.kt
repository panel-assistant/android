package io.panelassistant.android.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The byte-level contract between what the encoder emits and what a client joining mid-stream can decode. */
class H264Test {

    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1F, 0xDA.toByte(), 0x01, 0x40)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x06, 0xE2.toByte())
    private val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x00, 0x11)
    private val slice = byteArrayOf(0x41, 0x9A.toByte(), 0x02, 0x03)

    private fun annexB(vararg nals: ByteArray, fourByte: Boolean = true): ByteArray {
        val code = if (fourByte) byteArrayOf(0, 0, 0, 1) else byteArrayOf(0, 0, 1)
        return nals.fold(ByteArray(0)) { acc, nal -> acc + code + nal }
    }

    @Test fun splitRecognisesBothStartCodeLengthsAndDropsThem() {
        val units = AnnexB.split(annexB(sps, pps, idr))
        assertEquals(3, units.size)
        assertArrayEquals(sps, units[0])
        assertArrayEquals(pps, units[1])
        assertArrayEquals(idr, units[2])
        val three = AnnexB.split(annexB(sps, idr, fourByte = false))
        assertEquals(2, three.size)
        assertArrayEquals(idr, three[1])
        val mixed = AnnexB.split(byteArrayOf(0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + idr)
        assertArrayEquals("the zero of a four-byte code belongs to the code, not the previous unit", sps, mixed[0])
        assertArrayEquals(idr, mixed[1])
    }

    @Test fun splitHonoursOffsetAndLengthAndIgnoresLeadingGarbage() {
        val buffer = byteArrayOf(9, 9) + annexB(idr) + byteArrayOf(7, 7)
        val units = AnnexB.split(buffer, offset = 2, length = 4 + idr.size)
        assertEquals(1, units.size)
        assertArrayEquals(idr, units[0])
        assertTrue("nothing before the first start code is a unit", AnnexB.split(byteArrayOf(1, 2, 3)).isEmpty())
        val padded = AnnexB.split(annexB(idr) + byteArrayOf(0, 0))
        assertArrayEquals("trailing zero bytes after the last unit are not payload", idr, padded.single())
    }

    @Test fun parameterSetsComeOutOfTheCodecConfigBufferWithTheRfc6184Fields() {
        val sets = requireNotNull(ParameterSets.fromCodecConfig(annexB(sps, pps)))
        assertArrayEquals(sps, sets.sps)
        assertArrayEquals(pps, sets.pps)
        assertEquals("42C01F", sets.profileLevelId)
        assertEquals("Z0LAH9oBQA==,aM4G4g==", sets.spropParameterSets())
        assertNull("a config buffer without a PPS is not usable", ParameterSets.fromCodecConfig(annexB(sps)))
        assertNull(ParameterSets.fromCodecConfig(annexB(idr)))
    }

    @Test fun parameterSetsComeOutOfTheOutputFormatCsdBuffersInEitherLayout() {
        val split = ParameterSets.fromCsd(annexB(sps), annexB(pps))
        assertArrayEquals(sps, split?.sps)
        assertArrayEquals("the PPS comes from csd-1", pps, split?.pps)
        val together = ParameterSets.fromCsd(annexB(sps, pps), null)
        assertArrayEquals("some encoders put both sets in csd-0 and omit csd-1", pps, together?.pps)
        assertEquals("42C01F", split?.profileLevelId)
        assertNull("csd-0 alone with only an SPS is not usable", ParameterSets.fromCsd(annexB(sps), null))
        assertNull(ParameterSets.fromCsd(null, null))
    }

    @Test fun anIdrGetsTheParameterSetsAheadOfItUnlessTheEncoderAlreadyPutThemThere() {
        val sets = ParameterSets(sps, pps)
        val injected = accessUnitForTransport(listOf(idr), sets)
        assertEquals(3, injected.size)
        assertArrayEquals(sps, injected[0])
        assertArrayEquals(pps, injected[1])
        assertArrayEquals(idr, injected[2])
        val inline = accessUnitForTransport(listOf(sps, pps, idr), sets)
        assertEquals("already carried inline: not doubled", 3, inline.size)
        assertArrayEquals(sps, inline[0])
        val stale = byteArrayOf(0x67, 0x64, 0x00, 0x1F)
        val replaced = accessUnitForTransport(listOf(stale, idr), sets)
        assertEquals("an IDR carrying only an SPS gets the current pair instead", 3, replaced.size)
        assertArrayEquals(sps, replaced[0])
        assertArrayEquals(pps, replaced[1])
    }

    @Test fun aNonKeyFrameIsSentAsIsAndDelimitersAndBareConfigAreDropped() {
        val sets = ParameterSets(sps, pps)
        val plain = accessUnitForTransport(listOf(slice), sets)
        assertEquals(1, plain.size)
        assertArrayEquals(slice, plain[0])
        assertFalse(AnnexB.isKeyFrame(plain))
        assertEquals("delimiters are not payload", 1, accessUnitForTransport(listOf(byteArrayOf(0x09, 0xF0.toByte()), slice), sets).size)
        assertTrue("the config buffer is carried out of band, never as a frame", accessUnitForTransport(listOf(sps, pps), sets).isEmpty())
        assertEquals("without known sets an IDR still goes out", 1, accessUnitForTransport(listOf(idr), null).size)
    }
}
