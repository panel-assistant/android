package io.panelassistant.android.http

import io.panelassistant.android.util.GuardDbMaintenanceProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardDbMaintenancePublicActionTest {
    @Test fun `public parser admits exactly three forward actions`() {
        val allowed = listOf(
            GuardDbMaintenanceProtocol.Action.WITHHOLD_PREMIGRATE,
            GuardDbMaintenanceProtocol.Action.RESTORE_PREMIGRATE,
            GuardDbMaintenanceProtocol.Action.FINALIZE,
        )

        allowed.forEach { action -> assertEquals(action, parsePublicGuardDbAction(action.name)) }
        assertNull(parsePublicGuardDbAction(GuardDbMaintenanceProtocol.Action.ROLLBACK.name))
        assertNull(parsePublicGuardDbAction("rollback"))
        assertNull(parsePublicGuardDbAction("UNKNOWN"))
    }

    @Test fun `public admission keeps rollback required observe only`() {
        assertTrue(guardDbActionAllowed(
            GuardDbMaintenanceProtocol.Phase.B_HEALTHY,
            GuardDbMaintenanceProtocol.Action.WITHHOLD_PREMIGRATE,
        ))
        assertTrue(guardDbActionAllowed(
            GuardDbMaintenanceProtocol.Phase.A_REFUSED,
            GuardDbMaintenanceProtocol.Action.RESTORE_PREMIGRATE,
        ))
        assertTrue(guardDbActionAllowed(
            GuardDbMaintenanceProtocol.Phase.A_HEALTHY,
            GuardDbMaintenanceProtocol.Action.FINALIZE,
        ))
        assertFalse(guardDbActionAllowed(
            GuardDbMaintenanceProtocol.Phase.ROLLBACK_REQUIRED,
            GuardDbMaintenanceProtocol.Action.ROLLBACK,
        ))
        assertTrue(guardDbActionSettlementPhases(GuardDbMaintenanceProtocol.Action.ROLLBACK).isEmpty())
    }
}
