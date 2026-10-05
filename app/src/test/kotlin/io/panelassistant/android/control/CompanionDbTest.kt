package io.panelassistant.android.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionDbTest {
    private val US = '\u001f'
    private fun line(id: String, internal: String, external: String) = "$id$US$internal$US$external"

    @Test fun parsesRowsWithBlankAndPresentInternalUrls() {
        val out = listOf(
            line("1", "", "https://ha.example.com"),                       // blank internal — needs repair
            line("2", "http://10.0.0.5:8123", "https://ha.example.com"),   // fine
        ).joinToString("\n")
        val rows = CompanionDb.parseServers(out)
        assertEquals(2, rows.size)
        assertEquals("1", rows[0].id); assertEquals("", rows[0].internalUrl)
        assertEquals("https://ha.example.com", rows[0].externalUrl)
        assertEquals("http://10.0.0.5:8123", rows[1].internalUrl)
    }

    @Test fun toleratesBlankLinesAndShortRows() {
        // Leading/trailing blank lines, and row 2 has only two fields (no external column).
        val out = "\n" + line("1", "", "https://x") + "\n\n" + "2${US}http://y"
        val rows = CompanionDb.parseServers(out)
        assertEquals(2, rows.size)
        assertEquals("1", rows[0].id)
        assertEquals("2", rows[1].id)
        assertEquals("http://y", rows[1].internalUrl)
        assertEquals("", rows[1].externalUrl)                              // missing trailing field -> ""
    }

    @Test fun needsRepairOnlyWhenInternalBlankAndExternalPresent() {
        assertTrue(CompanionDb.needsRepair(CompanionDb.ServerRow("1", "", "https://ha")))
        assertTrue(CompanionDb.needsRepair(CompanionDb.ServerRow("1", "   ", "https://ha")))
        assertTrue(CompanionDb.needsRepair(CompanionDb.ServerRow("1", "null", "https://ha")))  // sqlite NULL leaked as literal
        // No external to copy from -> not repairable.
        assertFalse(CompanionDb.needsRepair(CompanionDb.ServerRow("1", "", "")))
        assertFalse(CompanionDb.needsRepair(CompanionDb.ServerRow("1", "", "null")))
        // Internal already set -> nothing to do.
        assertFalse(CompanionDb.needsRepair(CompanionDb.ServerRow("1", "http://10.0.0.5:8123", "https://ha")))
    }

    @Test fun internalUrlWarningOnlyWhenCompanionRendersTheDashboard() {
        // Automatic selection is the built-in renderer, so Companion-specific warnings stay quiet.
        assertFalse(CompanionDb.warningApplies(""))
        assertTrue(CompanionDb.warningApplies(io.panelassistant.android.util.CompanionInstaller.FULL_PKG))
        assertTrue(CompanionDb.warningApplies(io.panelassistant.android.util.CompanionInstaller.MINIMAL_PKG))
        // Built-in renderer or another explicit dashboard app: the Companion doesn't draw, so no banner.
        assertFalse(CompanionDb.warningApplies(SystemController.BUILTIN_DASHBOARD))
        assertFalse(CompanionDb.warningApplies("de.ozerov.fully"))
    }

    @Test fun urlsWithPipesSurviveParsing() {
        // The default sqlite3 '|' separator would corrupt this; the Unit-Separator dump doesn't.
        val out = line("1", "http://h/a|b", "https://ha/x|y")
        val rows = CompanionDb.parseServers(out)
        assertEquals(1, rows.size)
        assertEquals("http://h/a|b", rows[0].internalUrl)
        assertEquals("https://ha/x|y", rows[0].externalUrl)
    }

    @Test fun emptyOutputYieldsNoRows() {
        assertTrue(CompanionDb.parseServers("").isEmpty())
        assertTrue(CompanionDb.parseServers("\n\n").isEmpty())
    }

    @Test fun oneServerObservationProjectsPreferredUrlAndRepairStatus() {
        val observed = CompanionDb.observeServers(listOf(
            CompanionDb.ServerRow("1", "", "https://ha.example.com/"),
            CompanionDb.ServerRow("2", "http://10.0.0.5:8123/", "https://ha.example.com"),
        ))

        assertTrue(observed.probeSucceeded)
        assertEquals("https://ha.example.com", observed.preferredUrl)
        assertTrue(observed.status.needsRepair)
        assertEquals(1, observed.status.affected)
    }

    @Test fun emptyServerTableIsKnownEmpty() {
        val observed = CompanionDb.observeServers(emptyList())

        assertTrue(observed.probeSucceeded)
        assertEquals(CompanionDb.ServerObservation.EMPTY, observed)
        assertNull(observed.preferredUrl)
        assertFalse(observed.status.needsRepair)
        assertEquals(0, observed.status.affected)
    }

    @Test fun unreadableServerTableIsUnknownRatherThanKnownEmpty() {
        val observed = CompanionDb.observeServers(null)

        assertFalse(observed.probeSucceeded)
        assertEquals(CompanionDb.ServerObservation.UNKNOWN, observed)
        assertNull(observed.preferredUrl)
        assertFalse(observed.status.needsRepair)
        assertEquals(0, observed.status.affected)
    }

    @Test fun unreadableRefreshPreservesLastKnownPayloadWithoutClaimingSuccess() {
        val known = CompanionDb.observeServers(listOf(
            CompanionDb.ServerRow("1", "", "https://ha.example.com/"),
        ))

        val retained = CompanionDb.retainLastKnownServerObservation(
            previous = known,
            observed = CompanionDb.ServerObservation.UNKNOWN,
        )

        assertFalse(retained.probeSucceeded)
        assertEquals("https://ha.example.com", retained.preferredUrl)
        assertTrue(retained.status.needsRepair)
        assertEquals(1, retained.status.affected)
    }

    @Test fun parsesCompanionPageZoomFromThemesXml() {
        val xml = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <int name="page_zoom_level" value="115" />
                <string name="theme">dark</string>
            </map>
        """.trimIndent()
        assertEquals(115, CompanionDb.parsePageZoom(xml))
    }

    @Test fun pageZoomNullWhenAbsentOrUnreadable() {
        assertNull(CompanionDb.parsePageZoom("""<map><string name="theme">dark</string></map>"""))
        assertNull(CompanionDb.parsePageZoom(""))
        assertNull(CompanionDb.parsePageZoom(null))
    }

    // --- warning(): the shared decision the status endpoint + dashboard/Install banner both consume ---
    private val needsRepairObs = CompanionDb.observeServers(listOf(
        CompanionDb.ServerRow("1", "", "https://ha.example.com/"),
    ))

    @Test fun warningQuietWhenTheCompanionIsNotTheRenderer() {
        // A non-companion dashboard package never trips the internal-URL warning, even with bad rows.
        assertNull(CompanionDb.warning("de.ozerov.fully", needsRepairObs, directSuReady = true))
    }

    @Test fun warningStaysQuietForAutomaticBuiltinRenderer() {
        assertNull(CompanionDb.warning("", needsRepairObs, directSuReady = true))
    }

    @Test fun warningReportsProbeFailedOnlyForAnExplicitCompanionAndPrivilegedRetry() {
        val companion = io.panelassistant.android.util.CompanionInstaller.MINIMAL_PKG
        assertEquals(CompanionDb.Warning.ProbeFailed, CompanionDb.warning(companion, CompanionDb.ServerObservation.UNKNOWN, directSuReady = true))
        // No su → ha-paneld can't re-inspect, so stay quiet rather than nag.
        assertNull(CompanionDb.warning(companion, CompanionDb.ServerObservation.UNKNOWN, directSuReady = false))
    }

    @Test fun warningQuietForHealthyOrMissingObservation() {
        assertNull(CompanionDb.warning("", CompanionDb.ServerObservation.EMPTY, directSuReady = true))
        assertNull(CompanionDb.warning("", null, directSuReady = true))
    }
}
