package io.panelassistant.android.control

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class PowerSafetyMutationPolicyTest {
    @Test fun `only disabling an enabled guard is a safety reduction`() {
        assertTrue(PowerSafetyMutationPolicy.requestsSafetyReduction(true, false, true, null))
        assertTrue(PowerSafetyMutationPolicy.requestsSafetyReduction(true, null, true, false))
        assertFalse(PowerSafetyMutationPolicy.requestsSafetyReduction(false, false, false, false))
        assertFalse(PowerSafetyMutationPolicy.requestsSafetyReduction(false, true, false, true))
        assertFalse(PowerSafetyMutationPolicy.requestsSafetyReduction(true, true, true, true))
        assertFalse(PowerSafetyMutationPolicy.requestsSafetyReduction(true, null, true, null))
    }

    @Test fun `guard switch parser accepts only exact on and off tokens`() {
        assertEquals(true, PowerSafetyMutationPolicy.parseGuardSwitch(" ON "))
        assertEquals(false, PowerSafetyMutationPolicy.parseGuardSwitch("off"))
        assertEquals(null, PowerSafetyMutationPolicy.parseGuardSwitch("0"))
        assertEquals(null, PowerSafetyMutationPolicy.parseGuardSwitch("disabled"))
        assertEquals(null, PowerSafetyMutationPolicy.parseGuardSwitch(""))
    }
}
