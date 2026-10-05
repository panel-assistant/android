package io.panelassistant.android.metrics

import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The room-climate source now exposes each raw authority reader; the helper→Shizuku ladder, parsing and
 * source selection live in [PanelMetrics] (see [PanelMetricsTest]). These pin only that the daemon reader
 * sends the exact `CHT8305` verb and the shell reader delegates to the injected Shizuku function.
 */
class OsMetricSourceTest {
    @Test fun roomClimateDaemonSendsTheCht8305Verb() {
        val daemon = FakeDaemon(mapOf("CHT8305" to "T=2384 H=5895"))
        val source = OsMetricSource(daemon = daemon, root = FakeRootShell(), shellRoomClimate = { null })

        assertEquals("T=2384 H=5895", source.roomClimateDaemon())
        assertEquals(listOf("CHT8305"), daemon.sent)
    }

    @Test fun roomClimateShellDelegatesToTheShizukuReader() {
        var shellCalls = 0
        val source = OsMetricSource(
            daemon = FakeDaemon(),
            root = FakeRootShell(),
            shellRoomClimate = { shellCalls++; "T=2384 H=5895" },
        )

        assertEquals("T=2384 H=5895", source.roomClimateShell())
        assertEquals(1, shellCalls)
    }
}
