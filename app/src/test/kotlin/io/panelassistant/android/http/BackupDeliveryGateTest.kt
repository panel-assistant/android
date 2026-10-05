package io.panelassistant.android.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupDeliveryGateTest {
    @Test fun `one delivery lease excludes backup artifacts and releases idempotently`() {
        val lease = BackupDeliveryGate.acquire()!!
        assertTrue(BackupDeliveryGate.occupied())
        assertNull(BackupDeliveryGate.acquire())
        lease.close()
        lease.close()
        assertFalse(BackupDeliveryGate.occupied())
        BackupDeliveryGate.acquire()!!.close()
    }
}
