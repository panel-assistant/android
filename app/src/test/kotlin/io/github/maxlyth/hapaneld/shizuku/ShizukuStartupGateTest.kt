package io.github.maxlyth.hapaneld.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-stage startup admission that keeps `Application.onCreate` off the database.
 *
 * Registration installs the Binder listeners inside the foreground-start deadline; activation opens
 * durable reads once `PaneldService` has promoted. Each stage admits exactly one caller, so a sticky
 * listener callback arriving during `Application.onCreate` cannot open the database, and a second
 * activation cannot re-initialise a bridge that is already live.
 */
class ShizukuStartupGateTest {

    @Test fun registrationAdmitsExactlyOneCaller() {
        val gate = ShizukuStartupGate()
        assertFalse("nothing is registered before the first call", gate.registered)
        assertTrue("the first caller installs the listeners", gate.admitRegistration())
        assertTrue(gate.registered)
        assertFalse("a second initialize must not register the listeners twice", gate.admitRegistration())
    }

    @Test fun durableReadsStayClosedUntilTheServiceHasPromoted() {
        val gate = ShizukuStartupGate()
        gate.admitRegistration()
        assertFalse(
            "consent lives in ha-paneld.db, so registration alone must not open durable reads",
            gate.activated,
        )
    }

    @Test fun activationIsRefusedBeforeRegistration() {
        val gate = ShizukuStartupGate()
        assertFalse("there is no appContext to read with yet", gate.admitActivation())
        assertFalse(gate.activated)
    }

    @Test fun activationAdmitsExactlyOneCaller() {
        val gate = ShizukuStartupGate()
        gate.admitRegistration()
        assertTrue("the promote opens durable reads", gate.admitActivation())
        assertTrue(gate.activated)
        assertFalse("a second deferred activation is a no-op", gate.admitActivation())
        assertTrue("and it leaves the gate open", gate.activated)
    }
}
