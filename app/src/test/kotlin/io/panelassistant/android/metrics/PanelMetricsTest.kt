package io.panelassistant.android.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's coordination: two distinct caches (per-metric source resolution + snapshot freshness), a
 * single shared CPU-delta baseline, and one PERFDUMP per tick shared across daemon-resolved metrics. The
 * fake source counts each read so the tests assert on *which* source was hit, not just the value.
 *
 * `freshMs = 0` in the resolution tests so every `systemSnapshot()` re-enters `readCoherent` — otherwise
 * the value-freshness cache would return a cached snapshot and the resolution logic would never run.
 */
class PanelMetricsTest {

    /** Counting, fully-settable [MetricSource]; every field defaults to "unavailable". */
    private class FakeSource : MetricSource {
        var stat: String? = null
        var meminfo: String? = "MemTotal: 1000 kB\nMemFree: 100 kB\nMemAvailable: 400 kB\n"
        var loadavg: String? = null
        var thermal: List<Long> = emptyList()
        var gpu: String? = null
        var freqCur: List<Long> = emptyList()
        var freqMax: Long = 0
        var ip: String? = null
        var selinux: String? = null
        var dump: String? = null
        var roomDaemon: String? = null      // raw CHT8305 helper reply
        var roomShell: String? = null       // raw Shizuku shell-UID reply

        var statCalls = 0
        var dumpCalls = 0
        var roomDaemonCalls = 0
        var roomShellCalls = 0

        override fun perfDump(): String? { dumpCalls++; return dump }
        override fun statText(): String? { statCalls++; return stat }
        override fun meminfoText(): String? = meminfo
        override fun loadavgText(): String? = loadavg
        override fun thermalMilliValues(): List<Long> = thermal
        override fun gpuLoadRaw(): String? = gpu
        override fun cpuFreqCurMhz(): List<Long> = freqCur
        override fun cpuFreqMaxMhz(): Long = freqMax
        override fun localIp(): String? = ip
        override fun selinuxEnforce(): String? = selinux
        override fun cpuGovernor(allowRootFallback: Boolean): String? = null
        override fun cpuAvailableGovernors(allowRootFallback: Boolean): String? = null
        override fun roomClimateDaemon(): String? { roomDaemonCalls++; return roomDaemon }
        override fun roomClimateShell(): String? { roomShellCalls++; return roomShell }
    }

    private var now = 0L
    private fun reader(src: FakeSource, freshMs: Long = 0L, unavailRetryMs: Long = 1000L) =
        PanelMetrics(src, clock = { now }, freshMs = freshMs, unavailRetryMs = unavailRetryMs)

    private fun statStr(user: Long, idle: Long) = "cpu $user 0 0 $idle 0 0 0 0\n"

    // A full PERFDUMP so daemon-resolved metrics have real values.
    private val dumpText = "@STAT\ncpu 100 0 100 800 0 0 0 0\ncpu0 50 0 50 400 0 0 0 0\n" +
        "@LOAD 0.5 0.4 0.3\n@TEMP 42000\n@GPU 45@800000000Hz\n@PROC\n1234\t500\tproc\n@REND\n1234\t250\n@END\n"

    // A fully-healthy rooted panel: every direct read succeeds, so nothing ever touches the daemon.
    private fun healthyDirect() = FakeSource().apply {
        stat = statStr(0, 100); loadavg = "0.5 0.4 0.3"; thermal = listOf(42000L); gpu = "45@800000000Hz"
    }

    @Test fun roomClimateParsesAndCoalescesWithinTheFreshnessWindow() {
        val src = FakeSource().apply { roomDaemon = "T=2820 H=3400" }
        val r = reader(src, freshMs = 1500L)
        val a = r.roomClimate(now)!!
        assertEquals(28.20, a.tempC, 0.0001)
        assertEquals(34.0, a.humidityPct, 0.0001)
        // A second read within freshMs (the temp + humidity sensors on one heartbeat) reuses the value —
        // one daemon round-trip, not two.
        r.roomClimate(now + 100)
        assertEquals("temp+humidity coalesce onto one CHT8305 read", 1, src.roomDaemonCalls)
        // Past the window, it reads again.
        r.roomClimate(now + 2000)
        assertEquals(2, src.roomDaemonCalls)
    }

    @Test fun roomClimateIsNullWhenTheDaemonHasNoChip() {
        val src = FakeSource().apply { roomDaemon = null; roomShell = null }  // no authority reads → null
        assertNull(reader(src).roomClimate(now))
    }

    // --- the helper→Shizuku ladder, re-homed from OsMetricSourceTest onto the Resolvable that now owns it --

    @Test fun roomClimateEstablishedHelperWinsWithoutConsultingShizuku() {
        val src = FakeSource().apply { roomDaemon = "T=2384 H=5895"; roomShell = "T=9999 H=9999" }
        val v = reader(src).roomClimate(now)!!
        assertEquals(23.84, v.tempC, 0.0001)
        assertEquals(58.95, v.humidityPct, 0.0001)
        assertEquals("Shizuku is not consulted while the helper reads", 0, src.roomShellCalls)
    }

    @Test fun roomClimateUnavailableHelperFallsBackToShizuku() {
        val src = FakeSource().apply { roomDaemon = "ERR"; roomShell = "T=2384 H=5895" }
        val v = reader(src).roomClimate(now)!!
        assertEquals(23.84, v.tempC, 0.0001)
        assertEquals("the helper is tried first before the Shizuku fallback", 1, src.roomDaemonCalls)

        // The helper is retried once on the next fresh read, but the failed helper is not probed twice.
        now += 1
        reader(src).roomClimate(now)
        assertEquals("one helper probe per priority-ordered read", 2, src.roomDaemonCalls)
    }

    @Test fun roomClimateHelperRecoveryRetakesPriority() {
        val src = FakeSource().apply { roomDaemon = "ERR"; roomShell = "T=2384 H=5895" }
        val r = reader(src)
        assertEquals(23.84, r.roomClimate(now)!!.tempC, 0.0001)
        src.roomDaemon = "T=2820 H=3400"
        now += 1
        val recovered = r.roomClimate(now)!!
        assertEquals("a recovered helper regains priority over Shizuku", 28.20, recovered.tempC, 0.0001)
        assertEquals(2, src.roomDaemonCalls)
        assertEquals("Shizuku is not consulted after helper recovery", 1, src.roomShellCalls)
    }

    @Test fun roomClimateMalformedHelperReplyDoesNotMaskAValidShizukuReading() {
        val src = FakeSource().apply { roomDaemon = "T=oops H=5895"; roomShell = "T=2384 H=5895" }
        val v = reader(src).roomClimate(now)!!
        assertEquals(23.84, v.tempC, 0.0001)
    }

    @Test fun resolvesDirectOnceAndDoesNotReprobeOnSuccessfulReads() {
        val src = healthyDirect()
        val r = reader(src)
        repeat(3) { now += 2000; r.systemSnapshot() }
        assertEquals("direct /proc/stat read once per tick", 3, src.statCalls)
        assertEquals("daemon never probed once DIRECT wins", 0, src.dumpCalls)
    }

    @Test fun multipleDaemonMetricsShareOnePerfdumpPerTick() {
        // All direct reads fail → cpu/temp/gpu/loadavg all resolve to the daemon; one dump serves them.
        val src = FakeSource().apply { dump = dumpText }
        val r = reader(src)
        val snap = r.systemSnapshot()
        assertEquals("exactly one PERFDUMP for all four daemon metrics", 1, src.dumpCalls)
        assertEquals(42.0, snap.socTempC!!, 0.0001)  // proves the values came from the single dump
        assertEquals(45, snap.gpuPct)
        assertEquals(listOf("0.5", "0.4", "0.3"), snap.loadavg)
        assertNotNull(snap.dump)                      // carried through for PerfReader's top/render
    }

    @Test fun readFailureReresolvesToTheDaemonAndSticksThere() {
        val src = healthyDirect()
        val r = reader(src)
        now += 2000; r.systemSnapshot()               // resolves DIRECT
        assertEquals(0, src.dumpCalls)

        // DIRECT stops working; the daemon is now up.
        src.stat = null; src.dump = dumpText
        now += 2000; r.systemSnapshot()               // winner fails → re-resolve → DAEMON wins
        val statAfterReresolve = src.statCalls
        val dumpAfterReresolve = src.dumpCalls
        assertTrue("re-resolution probed the daemon", dumpAfterReresolve >= 1)

        now += 2000; r.systemSnapshot()               // winner is now DAEMON: no more DIRECT probing
        assertEquals("DIRECT not retried once DAEMON is the sticky winner", statAfterReresolve, src.statCalls)
        assertTrue("keeps reading via the daemon", src.dumpCalls > dumpAfterReresolve)
    }

    @Test fun unavailableMetricBacksOffThenRecoversWhenItsSourceAppears() {
        val src = FakeSource()                         // no direct, no daemon → nothing resolves
        val r = reader(src, unavailRetryMs = 1000L)

        now = 0; val s0 = r.systemSnapshot()
        assertNull(s0.socTempC)
        val dumpsAtResolve = src.dumpCalls             // one probe attempt at resolution (memoized dump)
        assertTrue(dumpsAtResolve >= 1)

        now = 500; r.systemSnapshot()                  // within backoff → no re-probe at all
        assertEquals("unavailable metric does not probe during backoff", dumpsAtResolve, src.dumpCalls)

        src.dump = dumpText                            // daemon appears
        now = 1500; val s2 = r.systemSnapshot()        // backoff elapsed → retry → recovers
        assertTrue("retried after the backoff window", src.dumpCalls > dumpsAtResolve)
        assertEquals(42.0, s2.socTempC!!, 0.0001)
    }

    @Test fun freshnessCacheCoalescesReadsWithinTheWindow() {
        val src = healthyDirect()
        val r = reader(src, freshMs = 1500L)
        now = 0; r.systemSnapshot()
        assertEquals(1, src.statCalls)
        now = 1000; r.systemSnapshot()                 // within 1500 → cache hit, no re-read
        assertEquals("coalesced onto the cached snapshot", 1, src.statCalls)
        now = 2000; r.systemSnapshot()                 // past the window → re-reads
        assertEquals(2, src.statCalls)
    }

    @Test fun wallClockRollbackExpiresSnapshotAndRoomCaches() {
        val src = healthyDirect().apply { roomDaemon = "T=2820 H=3400" }
        val r = reader(src, freshMs = 1500L)
        now = 10_000
        r.systemSnapshot()
        r.roomClimate()
        assertEquals(1, src.statCalls)
        assertEquals(1, src.roomDaemonCalls)

        now = 10_500
        r.systemSnapshot()
        r.roomClimate()
        assertEquals("forward time inside the window remains cached", 1, src.statCalls)
        assertEquals(1, src.roomDaemonCalls)

        now = 9_000
        assertEquals(9_000L, r.systemSnapshot().ts)
        r.roomClimate()
        assertEquals("rollback is an expiry boundary", 2, src.statCalls)
        assertEquals(2, src.roomDaemonCalls)
    }

    @Test fun wallClockRollbackRetriesAnUnavailableSourceImmediately() {
        val src = FakeSource()
        val r = reader(src, unavailRetryMs = 120_000L)
        now = 10_000
        assertNull(r.systemSnapshot().socTempC)
        val failedAttempts = src.dumpCalls

        src.dump = dumpText
        now = 9_000
        assertEquals(42.0, r.systemSnapshot().socTempC!!, 0.0001)
        assertTrue("rollback must not preserve an unavailable-source backoff", src.dumpCalls > failedAttempts)
    }

    @Test fun cpuDeltaUsesTheSingleSharedBaselineAcrossReads() {
        val src = healthyDirect()
        val r = reader(src)
        now = 0; assertNull("no baseline yet on the first read", r.systemSnapshot().cpuOverall)
        src.stat = statStr(50, 100)                    // +50 busy jiffies, idle unchanged → 100% busy delta
        now = 1000; assertEquals(100, r.systemSnapshot().cpuOverall)
    }

    @Test fun memoryReadsDirectEvenWhenTheCpuSourceIsUnavailable() {
        val src = FakeSource()                         // stat/daemon absent, but meminfo present (world-readable)
        val snap = reader(src).systemSnapshot()
        assertNull(snap.cpuOverall)
        assertEquals(60, snap.memPercent)              // 600/1000 kB
    }

    // --- boot time (relocated from the deleted Diagnostics facade) ------------------------------------

    @Test fun bootTimeIsWallMinusElapsedFormattedIsoUtc() {
        var wall = 1_700_003_600_000L                  // 2023-11-14T23:13:20Z wall clock
        val r = PanelMetrics(FakeSource(), clock = { wall }, elapsedRealtime = { 3_600_000L })
        // boot = wall (1_700_003_600_000) − 1h elapsed (3_600_000) = 1_700_000_000_000 → 2023-11-14T22:13:20Z
        assertEquals("2023-11-14T22:13:20Z", r.bootTime())
    }

    @Test fun bootTimeIsConstantOnceComputed() {
        var wall = 1_700_003_600_000L
        val r = PanelMetrics(FakeSource(), clock = { wall }, elapsedRealtime = { 3_600_000L })
        val first = r.bootTime()
        wall += 10_000_000L                            // wall clock advances after the first read
        assertEquals("boot time is a constant timestamp, computed once", first, r.bootTime())
    }
}
