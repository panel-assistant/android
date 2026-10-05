package io.panelassistant.android.control

import io.panelassistant.android.preferredAdminHomeRepairRoute
import io.panelassistant.android.shouldAttemptPeriodicAdminHomeRepair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminHomeSafetyTickTest {
    @Test fun disabledPolicyAndMissingRouteAreInert() {
        assertFalse(shouldAttemptPeriodicAdminHomeRepair(false, false, PrivilegeRoute.DAEMON, 1L, Long.MIN_VALUE))
        assertFalse(shouldAttemptPeriodicAdminHomeRepair(false, true, null, 1L, Long.MIN_VALUE))
        assertNull(preferredAdminHomeRepairRoute(helperRootReady = false, directSuReady = false))
    }

    @Test fun capturedHelperIsPreferredAndDirectSuIsTheOnlyFallback() {
        assertEquals(PrivilegeRoute.DAEMON, preferredAdminHomeRepairRoute(true, true))
        assertEquals(PrivilegeRoute.SU, preferredAdminHomeRepairRoute(false, true))
    }

    @Test fun oneFailureSuppressesRepeatedTicksUntilConfigGenerationChanges() {
        assertTrue(shouldAttemptPeriodicAdminHomeRepair(false, true, PrivilegeRoute.DAEMON, 7L, Long.MIN_VALUE))
        assertFalse(shouldAttemptPeriodicAdminHomeRepair(false, true, PrivilegeRoute.DAEMON, 7L, 7L))
        assertTrue(shouldAttemptPeriodicAdminHomeRepair(false, true, PrivilegeRoute.DAEMON, 8L, 7L))
    }

    @Test fun teardownClosesPeriodicRepairAdmission() {
        assertFalse(shouldAttemptPeriodicAdminHomeRepair(true, true, PrivilegeRoute.SU, 8L, Long.MIN_VALUE))
    }
}
