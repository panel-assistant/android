package io.panelassistant.android.metrics

import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The room-climate source exposes the raw helper reader; parsing and source selection live in
 * [PanelMetrics] (see [PanelMetricsTest]). This pins only that the reader sends the exact `CHT8305` verb.
 */
class OsMetricSourceTest {
    @Test fun roomClimateDaemonSendsTheCht8305Verb() {
        val daemon = FakeDaemon(mapOf("CHT8305" to "T=2384 H=5895"))
        val source = OsMetricSource(daemon = daemon, root = FakeRootShell())

        assertEquals("T=2384 H=5895", source.roomClimateDaemon())
        assertEquals(listOf("CHT8305"), daemon.sent)
    }
}
