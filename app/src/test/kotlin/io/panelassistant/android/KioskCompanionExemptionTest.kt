package io.panelassistant.android

import io.panelassistant.android.control.AppState
import io.panelassistant.android.control.parseForegroundPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kiosk companion exemption. Every assertion here protects one of two failure directions, and they
 * are not symmetric: letting the wrong app escape the lock silently disables the kiosk, while refusing
 * a legitimate companion merely leaves the operator where they already were.
 */
class KioskCompanionExemptionTest {

    @Test fun anEmptyCompanionListLeavesTheLockExactlyAsItWas() {
        assertTrue(shouldKioskReturnToDashboard(AppState.BG, adminUiVisible = false))
        assertFalse(shouldKioskReturnToDashboard(AppState.BG, adminUiVisible = true))
        assertFalse(shouldKioskReturnToDashboard(AppState.FG, adminUiVisible = false))
        assertFalse(shouldKioskReturnToDashboard(AppState.UNKNOWN, adminUiVisible = false))
        assertFalse(shouldKioskReturnToDashboard(AppState.DEAD, adminUiVisible = false))
    }

    @Test fun anExemptForegroundPackageIsNotPulledBack() {
        assertFalse(shouldKioskReturnToDashboard(AppState.BG, adminUiVisible = false) { true })
    }

    @Test fun aNonExemptForegroundPackageIsStillPulledBack() {
        assertTrue(shouldKioskReturnToDashboard(AppState.BG, adminUiVisible = false) { false })
    }

    /** The probe is behind a lazy `&&` so a locked panel showing its own dashboard pays nothing. */
    @Test fun theForegroundProbeIsNotRunWhileTheDashboardIsInFront() {
        var probes = 0
        shouldKioskReturnToDashboard(AppState.FG, adminUiVisible = false) { probes++; true }
        shouldKioskReturnToDashboard(AppState.UNKNOWN, adminUiVisible = false) { probes++; true }
        shouldKioskReturnToDashboard(AppState.DEAD, adminUiVisible = false) { probes++; true }
        assertEquals(0, probes)
    }

    /** The admin UI keeps its exemption without consulting the probe — order matters for cost. */
    @Test fun theForegroundProbeIsNotRunWhileTheAdminUiIsVisible() {
        var probes = 0
        assertFalse(shouldKioskReturnToDashboard(AppState.BG, adminUiVisible = true) { probes++; true })
        assertEquals(0, probes)
    }

    @Test fun theProbeIsConsultedExactlyOnceWhenItDecides() {
        var probes = 0
        shouldKioskReturnToDashboard(AppState.BG, adminUiVisible = false) { probes++; false }
        assertEquals(1, probes)
    }

    @Test fun companionListParsingAcceptsCommasNewlinesAndUntidyInput() {
        assertEquals(
            linkedSetOf("com.example.ava", "com.other.app"),
            parseKioskCompanionPackages("  com.example.ava , com.other.app  "),
        )
        assertEquals(
            linkedSetOf("com.example.ava", "com.other.app"),
            parseKioskCompanionPackages("com.example.ava\ncom.other.app"),
        )
        assertEquals(emptySet<String>(), parseKioskCompanionPackages(""))
        assertEquals(emptySet<String>(), parseKioskCompanionPackages("  ,  ,\n "))
    }

    @Test fun anUnreadableForegroundIsNotExempt() {
        assertFalse(isKioskCompanionForeground(null, setOf("com.example.ava")))
    }

    @Test fun exemptionMatchesTheWholePackageAndNothingElse() {
        val exempt = setOf("com.example.ava")
        assertTrue(isKioskCompanionForeground("com.example.ava", exempt))
        assertFalse(isKioskCompanionForeground("com.example.avatar", exempt))
        assertFalse(isKioskCompanionForeground("com.example", exempt))
        assertFalse(isKioskCompanionForeground("evil.com.example.ava", exempt))
        assertFalse(isKioskCompanionForeground("com.example.ava.extra", exempt))
        assertFalse(isKioskCompanionForeground("com.example.ava", emptySet()))
    }

    @Test fun theFocusedPackageIsReadOutOfARealDumpsysLine() {
        assertEquals(
            "io.github.maxlyth.hapaneld",
            parseForegroundPackage(
                "  mCurrentFocus=Window{cb16e00 u0 io.github.maxlyth.hapaneld/" +
                    "io.panelassistant.android.DashboardActivity}"
            ),
        )
        assertEquals(
            "com.example.ava",
            parseForegroundPackage("mCurrentFocus=Window{db840dc u0 com.example.ava/com.example.ava.MainActivity}"),
        )
    }

    /**
     * Every one of these must be null rather than a guess. A wrong package here exempts the wrong app,
     * which is the failure that silently turns the kiosk off.
     */
    @Test fun unreadableFocusLinesNeverProduceAPackage() {
        assertNull(parseForegroundPackage("mCurrentFocus=null"))
        assertNull(parseForegroundPackage("mCurrentFocus="))
        assertNull(parseForegroundPackage("mCurrentFocus=Window{a1b2 u0 NavigationBar0}"))
        assertNull(parseForegroundPackage("mCurrentFocus=Window{a1b2 u0 StatusBar}"))
        assertNull(parseForegroundPackage("mCurrentFocus=Window{}"))
        assertNull(parseForegroundPackage(""))
        assertNull(parseForegroundPackage("mFocusedApp=AppWindowToken{x u0 com.example.ava/.MainActivity}"))
    }

    /**
     * dumpsys puts more than one field on a line. The body must be read from after the marker, not
     * from the first brace on the line, or a neighbouring window supplies the package and the kiosk
     * exempts an app that does not hold focus.
     */
    @Test fun aWindowNamedBeforeTheMarkerOnTheSameLineIsNotMistakenForFocus() {
        assertEquals(
            "com.example.ava",
            parseForegroundPackage(
                "  mFocusedWindow=Window{aa11 u0 com.other.app/com.other.app.MainActivity} " +
                    "mCurrentFocus=Window{bb22 u0 com.example.ava/com.example.ava.MainActivity}"
            ),
        )
    }

    @Test fun theFocusLineIsFoundAmongOtherOutput() {
        assertEquals(
            "com.example.ava",
            parseForegroundPackage(
                "  mFocusedWindow=Window{zz u0 something}\n" +
                    "  mCurrentFocus=Window{db840dc u0 com.example.ava/com.example.ava.MainActivity}\n"
            ),
        )
    }
}
