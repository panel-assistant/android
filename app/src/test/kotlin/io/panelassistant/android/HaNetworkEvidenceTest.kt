package io.panelassistant.android

import io.panelassistant.android.sensors.HaNetworkPath
import io.panelassistant.android.sensors.HaNetworkPathMonitor
import io.panelassistant.android.sensors.HaNetworkPathPresentation
import io.panelassistant.android.sensors.HaNetworkPathSeverity
import io.panelassistant.android.sensors.HaPathFailureKind
import io.panelassistant.android.sensors.HaSocketState
import io.panelassistant.android.sensors.PathBurst
import io.panelassistant.android.sensors.PathEchoSource
import io.panelassistant.android.sensors.PathProbeCause
import io.panelassistant.android.sensors.PathProbeHistory
import io.panelassistant.android.sensors.PathProbeMonitor
import java.net.InetAddress
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drive the echo owner into the snapshot that the native chip and management surfaces render. */
class HaNetworkEvidenceTest {
    private class Source(var result: PathBurst?) : PathEchoSource {
        override fun burst(target: InetAddress, echoes: Int, perEchoTimeoutMs: Long, nowMs: () -> Long): PathBurst? = result
    }

    private fun healthySocket(): HaNetworkPath = HaNetworkPath().apply {
        onSocketState(HaSocketState.LIVE)
        repeat(30) { onRoundTrip(it * 1_000L, 9L) }
    }

    private fun echo(path: HaNetworkPath, burst: PathBurst?): HaNetworkPath.Snapshot {
        val monitor = PathProbeMonitor(source = Source(burst), onVerdict = path::onPathProbeVerdict)
        monitor.onRouteConnected(InetAddress.getByName("127.0.0.1"))
        monitor.onSocketState(HaSocketState.LIVE)
        monitor.runBurst(requireNotNull(monitor.claimBurst(30_000L))) { 30_000L }
        return path.snapshot(30_000L)
    }

    private fun tokens(snapshot: HaNetworkPath.Snapshot): Map<String, String> =
        HaNetworkPathPresentation.healthToken(snapshot).trim().split(' ').associate {
            val parts = it.split('=', limit = 2)
            parts[0] to parts[1]
        }

    @Test fun echoLossUsesTheLostEchoesWhileSocketMetricsKeepTheirMeaning() {
        val snapshot = echo(healthySocket(), PathBurst(30_000L, 5, 3, List(3) { 12L }))
        assertEquals(HaNetworkPathSeverity.SEVERE, snapshot.severity)
        assertEquals(PathProbeCause.LOSS, snapshot.cause)
        // These are the actual arguments the native view passes to its localized resource.
        assertEquals(listOf(2, 5, 1), haNetworkChipText(snapshot, Locale.ROOT).args)
        val text = requireNotNull(HaNetworkPathPresentation.statusText(snapshot))
        assertTrue(text, text.contains("2 of 5 network checks missed"))
        assertFalse(text, text.contains("no misses"))
        assertFalse("echo history is counted in bursts, not five-minute windows: $text", text.contains("last 5 min"))

        val health = tokens(snapshot)
        assertEquals("5,2,12,1", health["ha_net_echo"])
        val diagnostic = HaNetworkPathPresentation.diagnosticLine(snapshot)
        assertTrue(diagnostic, diagnostic.contains("source=echo echo_sent=5 echo_received=3 echo_p95=12 echo_bursts=1"))
        assertEquals("9", health["ha_net_p95"])
        assertEquals("30", health["ha_net_n"])
        assertEquals("0", health["ha_net_miss"])

        val status = JSONObject(HaNetworkPathPresentation.statusJson(snapshot))
        assertEquals(9L, status.getLong("p95_ms"))
        assertEquals(30, status.getInt("probes"))
        assertEquals(0, status.getInt("network_failures"))
        val evidence = status.getJSONObject("evidence")
        assertEquals("echo", evidence.getString("source"))
        assertEquals(5, evidence.getInt("probes"))
        assertEquals(2, evidence.getInt("network_failures"))
        assertEquals(12L, evidence.getLong("p95_ms"))
        assertEquals(1, evidence.getInt("bursts"))
    }

    @Test fun echoLatencyShowsItsOwnP95InsteadOfTheHealthySocketRoundTrip() {
        val snapshot = echo(healthySocket(), PathBurst(30_000L, 5, 5, List(5) { 500L }))
        assertEquals(HaNetworkPathSeverity.SEVERE, snapshot.severity)
        assertEquals(PathProbeCause.LATENCY, snapshot.cause)
        assertEquals(listOf("500 ms"), haNetworkChipText(snapshot, Locale.ROOT).args)
        val text = requireNotNull(HaNetworkPathPresentation.statusText(snapshot))
        assertTrue(text, text.contains("500 ms"))
        assertFalse(text, text.contains("p95 9 ms"))
        val health = tokens(snapshot)
        assertEquals("5,0,500,1", health["ha_net_echo"])
        assertEquals("9", health["ha_net_p95"])
        val evidence = JSONObject(HaNetworkPathPresentation.statusJson(snapshot)).getJSONObject("evidence")
        assertEquals("echo", evidence.getString("source"))
        assertEquals(500L, evidence.getLong("p95_ms"))
    }

    @Test fun unsupportedEchoRetainsSocketLossEvidence() {
        val path = healthySocket()
        path.onFailure(29_500L, HaPathFailureKind.NETWORK)
        path.onFailure(30_000L, HaPathFailureKind.NETWORK)
        val snapshot = echo(path, null)
        assertEquals(HaNetworkPathSeverity.SEVERE, snapshot.severity)
        assertEquals(PathProbeCause.LOSS, snapshot.cause)
        assertEquals(listOf(2, 32, 5), haNetworkChipText(snapshot, Locale.ROOT).args)
        assertTrue(requireNotNull(HaNetworkPathPresentation.statusText(snapshot)).contains("2 of 32 probes missed"))
        val health = tokens(snapshot)
        assertEquals("9", health["ha_net_p95"])
        assertEquals("32", health["ha_net_n"])
        assertEquals("2", health["ha_net_miss"])
    }

    @Test fun changingTheEvidenceAtUnchangedSeverityRefreshesTheNativeReader() {
        var changes = 0
        var now = 30_000L
        val path = HaNetworkPathMonitor(nowMs = { now }, path = healthySocket(), onChanged = { changes++ })
        val source = Source(PathBurst(now, 5, 3, List(3) { 12L }))
        val probe = PathProbeMonitor(
            source = source,
            onVerdict = path::onPathProbeVerdict,
            history = PathProbeHistory(maxBursts = 1),
        )
        probe.onRouteConnected(InetAddress.getByName("127.0.0.1"))
        probe.onSocketState(HaSocketState.LIVE)
        probe.runBurst(requireNotNull(probe.claimBurst(now))) { now }
        val loss = path.snapshot()
        val lossChanges = changes
        now += 1_000L
        source.result = PathBurst(now, 5, 5, List(5) { 500L })
        probe.onSocketSilence(now)
        probe.runBurst(requireNotNull(probe.claimBurst(now))) { now }
        val latency = path.snapshot()
        assertEquals(loss.severity, latency.severity)
        assertTrue("the existing chip must be told to read the new wording", changes > lossChanges)
        assertEquals(listOf("500 ms"), haNetworkChipText(latency, Locale.ROOT).args)
    }
}
