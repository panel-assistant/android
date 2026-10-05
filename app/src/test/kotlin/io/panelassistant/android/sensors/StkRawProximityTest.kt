package io.panelassistant.android.sensors

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class StkRawProximityTest {
    @Test fun startupCacheAndRepeatedReadsCannotEstablishLiveness() {
        val gate = StkRawProximityGate()
        for (now in listOf(0L, 100L, 1_000L, 20_000L)) {
            val sample = gate.observe(100, now)!!
            assertFalse(sample.fresh)
            assertFalse(sample.wakeEligible)
        }
        val firstChange = gate.observe(101, 20_100)!!
        assertTrue(firstChange.fresh)
        assertFalse(firstChange.wakeEligible)
        assertTrue(gate.observe(102, 20_200)!!.wakeEligible)
    }

    @Test fun repeatedCacheDoesNotExtendLeaseAndResumeCannotWake() {
        val gate = StkRawProximityGate()
        gate.observe(100, 0)
        gate.observe(101, 100)
        assertTrue(gate.observe(101, 2_099)!!.fresh)
        val expired = gate.observe(101, 2_100)!!
        assertFalse(expired.fresh)
        assertTrue(expired.becameUnavailable)
        val resumed = gate.observe(102, 2_200)!!
        assertTrue(resumed.fresh)
        assertFalse(resumed.wakeEligible)
    }

    @Test fun timerExpiryInvalidatesBeforeWorkerResultAndResumedChange() {
        val gate = StkRawProximityGate()
        gate.observe(100, 0)
        gate.observe(101, 100)
        assertFalse(gate.expire(2_099))
        assertTrue(gate.expire(2_100))
        assertFalse(gate.expire(2_101))
        assertFalse(gate.observe(102, 2_200)!!.wakeEligible)
    }

    @Test fun failedReadRequiresNewSeedAndThenChange() {
        val gate = StkRawProximityGate()
        gate.observe(100, 0)
        gate.observe(101, 100)
        assertTrue(gate.observe(null, 200)!!.becameUnavailable)
        assertFalse(gate.observe(102, 300)!!.fresh)
        val restored = gate.observe(103, 400)!!
        assertTrue(restored.fresh)
        assertFalse(restored.wakeEligible)
        assertTrue(gate.observe(104, 500)!!.wakeEligible)
        assertFalse(gate.observe(105, 499)!!.fresh)
    }

    @Test fun deadlineIncludesBlockedReadAndQueuedDelivery() {
        assertEquals(123, stkRawReadWithinDeadline(123, 100, 600))
        assertNull(stkRawReadWithinDeadline(123, 100, 601))
        assertNull(stkRawReadWithinDeadline(123, 100, 99))
        assertNull(stkRawReadWithinDeadline(123, -1, 100))
        assertNull(stkRawReadWithinDeadline(null, 100, 200))
    }

    @Test fun stalledReadExpiresReadinessEvenWithoutRuntimeTickWork() {
        val gate = StkRawProximityGate()
        val engine = ProximityCalibrationEngine(observedSourceMode = ProximityCalibrationEngine.Mode.RANGED)
        gate.observe(100, 0)
        val fresh = gate.observe(101, 100)!!
        engine.observe(101f, 100, fresh.wakeEligible, fresh.fresh)
        assertTrue(engine.current().available)
        assertFalse(engine.needsTick())
        // No worker result and no gesture timer: the independent watchdog expires the lease.
        assertTrue(gate.expire(2_100))
        engine.sourceUnavailable(2_100)
        assertFalse(engine.current().available)
        val late = stkRawReadWithinDeadline(102, 200, 2_200)
        assertNull(late)
        assertFalse(gate.observe(late, 2_200)!!.fresh)
        assertFalse(engine.tick(2_200).gesture)
    }

    @Test fun cachedStartupKeepsIntroductionOpenUntilValueChanges() {
        val gate = StkRawProximityGate()
        val engine = ProximityCalibrationEngine(observedSourceMode = ProximityCalibrationEngine.Mode.RANGED)
        engine.start(0)
        for (now in listOf(100L, 200L, 1_000L)) {
            assertFalse(gate.observe(100, now)!!.fresh)
            val state = engine.sourceUnavailable(now)
            assertEquals(ProximityCalibrationEngine.Stage.INTRO, state.stage)
            assertTrue(state.active)
            assertFalse(state.available)
        }
        val sample = gate.observe(101, 1_100)!!
        val ready = engine.observe(101f, 1_100, sample.wakeEligible, sample.fresh)
        assertTrue(ready.available)
        assertFalse(ready.gesture)
        assertEquals(ProximityCalibrationEngine.Stage.CLEAR, engine.action("begin", 1_200).stage)
    }

    @Test fun shutdownRejectsLateResult() {
        val gate = StkRawProximityGate()
        gate.observe(100, 0)
        gate.observe(101, 100)
        gate.close()
        assertNull(gate.observe(102, 200))
        assertFalse(gate.expire(5_000))
    }

    @Test fun parserAcceptsOnlyUnsigned16BitDecimal() {
        for (value in listOf("0", "1\n", "65535\n")) {
            assertEquals(value.trim().toInt(), StkRawProximityReader.parse(ByteArrayInputStream(value.toByteArray())))
        }
        for (value in listOf("", "-1", "+1", "1.0", "65536", "1\n2", " 1", "1 ", "99999999999", "NaN")) {
            assertTrue(value, runCatching { StkRawProximityReader.parse(ByteArrayInputStream(value.toByteArray())) }.isFailure)
        }
    }

    @Test fun parserReadIsBoundedEvenWithoutEof() {
        var count = 0
        val endless = object : InputStream() {
            override fun read(): Int { count++; return '1'.code }
        }
        assertTrue(runCatching { StkRawProximityReader.parse(endless) }.isFailure)
        assertEquals(9, count)
    }

    @Test fun readerRequiresBoundContractAndNeverWritesValue() {
        val root = java.nio.file.Files.createTempDirectory("raw-proximity-contract").toFile()
        try {
            val value = File(root, "st_psensor/psensor_value")
            value.parentFile!!.mkdirs()
            value.writeText("1234\n")
            val devices = File(root, "devices")
            val device = File(devices, "2-0046-1")
            device.mkdirs()
            val name = File(device, "name")
            name.writeText("ps_stk3a5x\n")
            val reader = StkRawProximityReader(value, devices)
            assertTrue(runCatching { reader.read() }.isFailure)
            File(device, "driver").mkdir()
            assertEquals(1234, reader.read())
            assertEquals("1234\n", value.readText())
            name.writeText("ps_stk3x3x\n")
            assertTrue(runCatching { reader.read() }.isFailure)
            assertEquals("1234\n", value.readText())
        } finally { root.deleteRecursively() }
    }

    // Mirrors the NSPanel 86 and NSPanel 120 sysfs layouts: both chips enumerate, only one driver binds.
    private fun sysfs(root: File, bound: String, staleValue: Boolean = true): Pair<File, File> {
        val value = File(root, "st_psensor/psensor_value")
        if (staleValue) {
            value.parentFile!!.mkdirs()
            value.writeText("61\n")
        }
        val devices = File(root, "devices")
        for ((node, chip) in listOf("2-0046-1" to "ps_stk3a5x", "2-0047-1" to "ps_stk3x3x")) {
            File(devices, node).mkdirs()
            File(devices, "$node/name").writeText("$chip\n")
            if (chip == bound) File(devices, "$node/driver").mkdir()
        }
        return value to devices
    }

    private fun withRoot(block: (File) -> Unit) {
        val root = java.nio.file.Files.createTempDirectory("raw-proximity").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test fun boundStk3a5xSelectsRaw16() = withRoot { root ->
        val (value, devices) = sysfs(root, bound = "ps_stk3a5x")
        val reader = StkRawProximityReader(value, devices)
        assertTrue(reader.isPresent())
        assertEquals(ProximityAcquisition.STK_RAW, proximityAcquisition(false, null, true, reader.isPresent()))
        assertEquals(61, reader.read())
        // The binding, not the cache file, decides: unbind the same tree and the HAL is selected.
        assertTrue(File(devices, "2-0046-1/driver").delete())
        assertEquals(ProximityAcquisition.ANDROID_HAL, proximityAcquisition(false, null, true, reader.isPresent()))
    }

    @Test fun stk3x3xPanelWithStaleRaw16CacheSelectsHal() = withRoot { root ->
        val (value, devices) = sysfs(root, bound = "ps_stk3x3x")
        val reader = StkRawProximityReader(value, devices)
        assertTrue(value.isFile)
        assertFalse(reader.isPresent())
        assertEquals(ProximityAcquisition.ANDROID_HAL, proximityAcquisition(false, null, true, reader.isPresent()))
        // The cache directory alone, with no value file and no devices, is not an interface either.
        val empty = File(root, "empty/st_psensor/psensor_value").also { it.parentFile!!.mkdirs() }
        assertFalse(StkRawProximityReader(empty, File(root, "empty/devices")).isPresent())
    }

    @Test fun boundStk3a5xWithoutValueFileSelectsHal() = withRoot { root ->
        val (value, devices) = sysfs(root, bound = "ps_stk3a5x", staleValue = false)
        assertFalse(StkRawProximityReader(value, devices).isPresent())
    }

    @Test fun probeSecurityExceptionSelectsHalBecauseRaw16CouldNeverRead() = withRoot { root ->
        val (value, _) = sysfs(root, bound = "ps_stk3a5x")
        val denied = object : File(root, "devices") {
            override fun listFiles(): Array<File>? = throw SecurityException("denied")
        }
        val reader = StkRawProximityReader(value, denied)
        assertFalse(reader.isPresent())
        assertEquals(ProximityAcquisition.ANDROID_HAL, proximityAcquisition(false, null, true, reader.isPresent()))
        assertTrue(runCatching { reader.read() }.exceptionOrNull() is SecurityException)
    }
}
