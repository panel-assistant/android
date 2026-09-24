package io.github.maxlyth.hapaneld.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TransferCurveTest {

    private val maxima = intArrayOf(1, 15, 100, 255, 1023, 4095)

    private val shaped = listOf(
        TransferCurve.Gamma(2.2),
        TransferCurve.Gamma(2.2, floor = 12 / 255.0),
        TransferCurve.Gamma(0.5, floor = 0.1),
        TransferCurve.Points(listOf(0 to 0, 1 to 10, 64 to 20, 128 to 70, 255 to 255)),
        TransferCurve.Points(listOf(0 to 0, 128 to 30, 255 to 255), floor = 8 / 255.0),
    )

    @Test fun identityIsByteIdenticalToTheHistoricLinearScaling() {
        for (max in maxima) {
            for (v in 0..255) {
                assertEquals("forward v=$v max=$max", (v.toLong() * max / 255).toInt(), TransferCurve.Identity.toHardware(v, max))
            }
            for (h in 0..max) {
                assertEquals("inverse h=$h max=$max", (h.toLong() * 255 / max).toInt().coerceIn(0, 255), TransferCurve.Identity.toLevel(h, max))
            }
        }
    }

    @Test fun zeroIsOffAndNonZeroIsNeverOff() {
        for (curve in shaped) for (max in maxima) {
            assertEquals("$curve max=$max", 0, curve.toHardware(0, max))
            assertEquals("$curve max=$max", 0, curve.toLevel(0, max))
            for (v in 1..255) assertTrue("$curve v=$v max=$max", curve.toHardware(v, max) >= 1)
        }
    }

    @Test fun fullScaleReachesTheNodeMaximum() {
        for (curve in shaped + TransferCurve.Identity) for (max in maxima) {
            assertEquals("$curve max=$max", max, curve.toHardware(255, max))
            assertEquals("$curve max=$max", 255, curve.toLevel(max, max))
        }
    }

    @Test fun everyCurveIsMonotone() {
        for (curve in shaped + TransferCurve.Identity) for (max in maxima) {
            for (v in 1..255) {
                assertTrue("$curve forward v=$v max=$max", curve.toHardware(v, max) >= curve.toHardware(v - 1, max))
            }
            for (h in 1..max) {
                assertTrue("$curve inverse h=$h max=$max", curve.toLevel(h, max) >= curve.toLevel(h - 1, max))
            }
        }
    }

    @Test fun inverseRecoversEveryReachableHardwareValue() {
        // Identity is exempt: it keeps the historic truncating scale in both directions.
        for (curve in shaped) for (max in maxima) {
            for (v in 0..255) {
                val h = curve.toHardware(v, max)
                assertEquals("$curve v=$v max=$max", h, curve.toHardware(curve.toLevel(h, max), max))
            }
        }
    }

    @Test fun roundTripIsWithinTheCurvesOwnQuantisation() {
        // A request comes back as a request with the same hardware value: never more than the width of the
        // run of requests the curve collapses onto that value, and exactly when the curve is injective there.
        for (curve in shaped) {
            for (v in 0..255) {
                val h = curve.toHardware(v, 1023)
                val back = curve.toLevel(h, 1023)
                val run = (0..255).filter { curve.toHardware(it, 1023) == h }
                assertTrue("$curve v=$v back=$back", back in run)
                if (run.size == 1) assertEquals("$curve v=$v", v, back)
            }
        }
    }

    @Test fun inverseOfAnUnreachableValuePicksTheNearerNeighbour() {
        val curve = TransferCurve.Points(listOf(0 to 0, 1 to 100, 2 to 200, 255 to 255))
        assertEquals(100, curve.toHardware(1))
        assertEquals(200, curve.toHardware(2))
        assertEquals(1, curve.toLevel(140))
        assertEquals(2, curve.toLevel(160))
    }

    @Test fun inverseBelowTheFloorStillReadsAsLit() {
        val curve = TransferCurve.Gamma(2.2, floor = 40 / 255.0)
        assertEquals(40, curve.toHardware(1))
        assertEquals(1, curve.toLevel(3))
    }

    @Test fun floorKeepsTheLowestRequestVisible() {
        val curve = TransferCurve.from("perceptual", floor = 12)
        assertEquals(12, curve.toHardware(1))
        assertEquals(12 * 4, curve.toHardware(1, 1020))
        assertEquals(1, TransferCurve.Gamma(2.2).toHardware(1, 255)) // no floor: still the least lit value
        // Linear with a floor is spelled gamma 1.0, since identity takes no floor.
        val linear = TransferCurve.from("gamma", gamma = 1.0, floor = 20)
        assertEquals(20, linear.toHardware(1))
        assertEquals(138, linear.toHardware(128))   // 20 + 128/255 · 235 = 137.96
    }

    @Test fun gammaBendsTheLowEndDown() {
        val curve = TransferCurve.Gamma(2.2)
        assertEquals(56, curve.toHardware(128))   // 255 · 0.502^2.2
        assertEquals(12, curve.toHardware(64))
        val root = TransferCurve.Gamma(0.5)
        assertEquals(181, root.toHardware(128))   // 255 · 0.502^0.5
    }

    @Test fun pointsInterpolateLinearlyBetweenControlPoints() {
        val curve = TransferCurve.Points(listOf(0 to 0, 128 to 32, 255 to 255))
        assertEquals(16, curve.toHardware(64))
        assertEquals(32, curve.toHardware(128))
        assertEquals(144, curve.toHardware(192))  // 32 + 64/127 · 223 = 144.4
    }

    @Test fun namedCurvesResolve() {
        assertEquals(TransferCurve.Identity, TransferCurve.from("identity"))
        assertEquals(TransferCurve.Gamma(2.2), TransferCurve.from("perceptual"))
        assertEquals(TransferCurve.Gamma(1.8, 10 / 255.0), TransferCurve.from("gamma", gamma = 1.8, floor = 10))
        assertEquals(
            TransferCurve.Points(listOf(0 to 0, 255 to 255)),
            TransferCurve.from("points", points = listOf(0 to 0, 255 to 255)),
        )
    }

    @Test fun invalidDeclarationsAreRefused() {
        val bad = listOf<() -> Any>(
            { TransferCurve.from("sigmoid") },
            { TransferCurve.from("gamma") },
            { TransferCurve.from("gamma", gamma = 0.1) },
            { TransferCurve.from("gamma", gamma = 6.0) },
            { TransferCurve.from("perceptual", gamma = 2.0) },
            { TransferCurve.from("perceptual", points = listOf(0 to 0, 255 to 255)) },
            { TransferCurve.from("identity", floor = 4) },
            { TransferCurve.from("perceptual", floor = 200) },
            { TransferCurve.from("perceptual", floor = -1) },
            { TransferCurve.from("points") },
            { TransferCurve.from("points", points = listOf(0 to 0)) },
            { TransferCurve.from("points", points = listOf(0 to 5, 255 to 255)) },
            { TransferCurve.from("points", points = listOf(0 to 0, 255 to 200)) },
            { TransferCurve.from("points", points = listOf(0 to 0, 100 to 50, 100 to 60, 255 to 255)) },
            { TransferCurve.from("points", points = listOf(0 to 0, 100 to 80, 150 to 60, 255 to 255)) },
            { TransferCurve.from("points", points = listOf(0 to 0, 100 to 300, 255 to 255)) },
        )
        bad.forEachIndexed { index, build ->
            try {
                build()
                fail("declaration $index was accepted")
            } catch (expected: IllegalArgumentException) {
                assertFalse(expected.message.isNullOrBlank())
            }
        }
    }
}
