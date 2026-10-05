package io.panelassistant.android.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UninstallOutcomeTest {
    @Test fun explicitPackageManagerSuccessIsAccepted() =
        assertTrue(uninstallSucceeded("Success\n", "package:/data/app/example.apk\n"))

    @Test fun successfulEmptyPathProbeProvesAbsenceAfterAmbiguousUninstallOutput() =
        assertTrue(uninstallSucceeded("", ""))

    @Test fun failedOrUnavailablePathProbeNeverMasqueradesAsAbsence() {
        assertFalse(uninstallSucceeded(null, null))
        assertFalse(uninstallSucceeded("Failure [blocked]", null))
        assertFalse(uninstallSucceeded("Failure [blocked]", "package:/data/app/example.apk"))
    }
}
