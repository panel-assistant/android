package io.panelassistant.android.metrics

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricPayloadTest {
    @Test fun measurementsRoundTrip() {
        val values = mapOf(1 to 4L, 7 to 1_234_567L, 22 to 1L)
        assertEquals(values, MetricPayload.decode(MetricPayload.encode(values)))
    }

    @Test fun anEmptyBucketCostsNothing() {
        assertTrue(MetricPayload.encode(emptyMap()).isEmpty())
        assertEquals(emptyMap<Int, Long>(), MetricPayload.decode(ByteArray(0)))
    }

    /** A bucket stores only what it has; that is why this beats fixed columns on a 64%-dense table. */
    @Test fun zeroesAreNotStored() {
        val encoded = MetricPayload.encode(mapOf(1 to 0L, 2 to 5L, 3 to 0L))
        assertEquals(mapOf(2 to 5L), MetricPayload.decode(encoded))
    }

    @Test fun extremeAndNegativeValuesSurvive() {
        val values = mapOf(1 to Long.MAX_VALUE, 2 to Long.MIN_VALUE, 3 to -1L, 4 to 0L - 42L)
        assertEquals(values.filterValues { it != 0L }, MetricPayload.decode(MetricPayload.encode(values)))
    }

    /** Zigzag exists so a negative value costs like a small one instead of ten bytes. */
    @Test fun smallNegativesStayCompact() {
        assertTrue(MetricPayload.encode(mapOf(1 to -1L)).size <= 3)
    }

    /**
     * The property that lets an older build read a newer panel's history: an id it has never heard of
     * decodes to a value it can ignore, rather than failing the whole bucket.
     */
    @Test fun unknownIdsDecodeAndCanBeIgnored() {
        val fromNewerBuild = MetricPayload.encode(mapOf(3 to 7L, 900 to 5L))
        val decoded = MetricPayload.decode(fromNewerBuild)!!
        assertEquals(7L, decoded[3])
        assertEquals(mapOf(3 to 7L), decoded.filterKeys { it in setOf(3) })
    }

    /** A corrupt diagnostic row must be skipped, never reported as real measurements. */
    @Test fun truncatedOrMalformedPayloadsAreRejected() {
        val good = MetricPayload.encode(mapOf(1 to 1_000_000L))
        assertNull("a value cut short must not decode", MetricPayload.decode(good.copyOf(good.size - 1)))
        assertNull("a dangling id with no value must not decode", MetricPayload.decode(byteArrayOf(0x02)))
        assertNull(MetricPayload.decode(byteArrayOf(0x80.toByte())))
        assertNull("continuation bytes beyond 64 bits must not decode", MetricPayload.decode(ByteArray(12) { 0x80.toByte() }))
        assertNull(MetricPayload.decode(null))
    }

    @Test fun randomisedRoundTripsHold() {
        val random = Random(20260724)
        repeat(500) {
            val values = (1..random.nextInt(1, 30)).associateWith { random.nextLong(-1_000_000, 1_000_000) }
            val expected = values.filterValues { it != 0L }
            assertEquals(expected, MetricPayload.decode(MetricPayload.encode(values)))
        }
    }

    /**
     * Guards the measurement this design rests on, and states its limit honestly.
     *
     * The 0.66x measured against a real panel comes from sparsity, not from the encoding being
     * universally smaller: real buckets are ~64% dense and average ~46 bytes against the ~101 a
     * fixed-column row costs. A *fully* dense bucket is roughly break-even, because with every metric
     * present the ids are pure overhead. That is the correct trade — it is never much worse, and much
     * better in the common case — so both halves are pinned.
     */
    @Test fun aTypicallyDenseBucketIsFarSmallerThanAFixedColumnRow() {
        val typical = (1..22).filter { it % 3 != 0 }.associateWith { (it * 137L) * 1_000 } // ~64% present
        val size = MetricPayload.encode(typical).size
        assertTrue("a typical bucket was $size bytes, expected well under a 101-byte fixed row", size < 75)
    }

    @Test fun aFullyDenseBucketIsAtWorstComparableToAFixedColumnRow() {
        val dense = (1..22).associateWith { (it * 137L) * 1_000 }
        val size = MetricPayload.encode(dense).size
        assertTrue("worst case must stay comparable, was $size bytes", size <= 110)
    }
}
