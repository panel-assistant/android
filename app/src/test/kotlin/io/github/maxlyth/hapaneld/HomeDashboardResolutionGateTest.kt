package io.github.maxlyth.hapaneld

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDashboardResolutionGateTest {
    @Test fun `stale endpoint credential or setting cannot admit a renderer`() {
        val gate = HomeDashboardResolutionAttemptGate()
        val firstOwner = owner("https://first.example", "first-refresh", "")
        val first = gate.start(firstOwner)
        assertTrue(gate.owns(first, firstOwner))
        assertFalse(gate.owns(first, owner("https://changed.example", "first-refresh", "")))
        assertFalse(gate.owns(first, owner("https://first.example", "replacement-refresh", "")))
        assertFalse(gate.owns(first, owner("https://first.example", "first-refresh", "/alpha")))

        val secondOwner = owner("https://first.example", "first-refresh", "/alpha")
        val second = gate.start(secondOwner)
        assertFalse(gate.owns(first, firstOwner))
        assertTrue(gate.owns(second, secondOwner))
        gate.invalidate()
        assertFalse(gate.owns(second, secondOwner))
    }

    private fun owner(url: String, refresh: String, path: String) = HomeDashboardResolutionOwner(
        authOwner = HaAuthOwner(url, refresh, "client", ""),
        configuredPath = path,
    )
}
