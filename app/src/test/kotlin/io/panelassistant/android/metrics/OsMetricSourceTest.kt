package io.panelassistant.android.metrics

import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.platform.Daemon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source selection through the production boundary: governor reads prefer direct files, then the
 * typed helper, while room-climate retains its existing raw helper verb.
 */
class OsMetricSourceTest {
    @Test fun directGovernorsWinWithoutHelperOrRootCalls() {
        var helperReads = 0
        val daemon = object : Daemon by FakeDaemon() {
            override fun cpuGovernor(): String? { helperReads++; return "helper" }
            override fun cpuAvailableGovernors(): List<String>? { helperReads++; return listOf("helper") }
        }
        val root = FakeRootShell()
        val source = OsMetricSource(daemon, root, directRead = { path ->
            if (path.endsWith("scaling_governor")) "Vendor_1.fast-2\n" else " powersave\tperformance\n"
        })
        assertEquals("Vendor_1.fast-2", source.cpuGovernor())
        assertEquals("powersave performance", source.cpuAvailableGovernors())
        assertEquals(0, helperReads)
        assertTrue(root.outputRan.isEmpty())
    }

    @Test fun deniedOrMalformedDirectReadUsesTypedHelperAndRetainedFlagControlsCrossCheck() {
        var helperReads = 0
        val daemon = object : Daemon by FakeDaemon() {
            override fun cpuGovernor(): String? { helperReads++; return "Vendor_1.fast-2" }
            override fun cpuAvailableGovernors(): List<String>? { helperReads++; return listOf("powersave", "Vendor_1.fast-2") }
        }
        val root = FakeRootShell()
        val source = OsMetricSource(daemon, root, directRead = { path ->
            if (path.endsWith("scaling_governor")) throw java.io.IOException("denied") else "bad/path"
        })
        assertNull(source.cpuGovernor(allowRootFallback = false))
        assertNull(source.cpuAvailableGovernors(allowRootFallback = false))
        assertEquals(0, helperReads)
        repeat(6) {
            assertEquals("Vendor_1.fast-2", source.cpuGovernor())
            assertEquals("powersave Vendor_1.fast-2", source.cpuAvailableGovernors())
        }
        assertEquals(12, helperReads)
        assertTrue(root.outputRan.isEmpty())
        assertTrue(root.isolatedOutputRan.isEmpty())
        assertTrue(root.ran.isEmpty())
        assertEquals(0, root.availabilityChecks)
    }

    @Test fun roomClimateDaemonSendsTheCht8305Verb() {
        val daemon = FakeDaemon(mapOf("CHT8305" to "T=2384 H=5895"))
        val source = OsMetricSource(daemon = daemon, root = FakeRootShell())

        assertEquals("T=2384 H=5895", source.roomClimateDaemon())
        assertEquals(listOf("CHT8305"), daemon.sent)
    }
}
