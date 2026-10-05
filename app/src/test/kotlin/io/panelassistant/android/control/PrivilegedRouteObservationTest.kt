package io.panelassistant.android.control

import io.panelassistant.android.shizuku.ShizukuBridge
import io.panelassistant.android.shizuku.ShizukuState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedRouteObservationTest {
    @Test fun observesEachAuthorityExactlyOnce() {
        var suCalls = 0
        var helperCalls = 0
        var shizukuCalls = 0

        val observed = observePrivilegedRoutes(
            directSuProbe = { suCalls += 1; false },
            helperRootProbe = { helperCalls += 1; true },
            shizukuSnapshot = {
                shizukuCalls += 1
                ShizukuBridge.Snapshot(ShizukuState.STOPPED, ready = false)
            },
        )

        assertFalse(observed.directSuReady)
        assertTrue(observed.helperRootReady)
        assertTrue(observed.rootControlReady)
        assertTrue(observed.typedShellControlReady)
        assertEquals(1, suCalls)
        assertEquals(1, helperCalls)
        assertEquals(1, shizukuCalls)
    }

    @Test fun shizukuProvesTypedShellControlButNeverRootControl() {
        val observed = PrivilegedRouteObservation(
            directSuReady = false,
            helperRootReady = false,
            shizuku = ShizukuBridge.Snapshot(ShizukuState.READY, ready = true),
        )

        assertFalse(observed.rootControlReady)
        assertTrue(observed.typedShellControlReady)
        assertFalse(observed.admits(PrivilegeRoute.SU))
        assertFalse(observed.admits(PrivilegeRoute.DAEMON))
        assertTrue(observed.admits(PrivilegeRoute.SHIZUKU))
        assertFalse(observed.admits(PrivilegeRoute.ACCESSIBILITY))
    }

    @Test fun directOrHelperRouteIndependentlyProvesRootControl() {
        listOf(true to false, false to true, true to true).forEach { (direct, helper) ->
            val observed = PrivilegedRouteObservation(
                directSuReady = direct,
                helperRootReady = helper,
                shizuku = ShizukuBridge.Snapshot(ShizukuState.DISABLED, ready = false),
            )

            assertTrue(observed.rootControlReady)
            assertTrue(observed.typedShellControlReady)
        }
    }

    @Test fun typedCapabilitySkipsHelperWhenDirectSuOrShizukuAlreadyProvesIt() {
        listOf(true to false, false to true, true to true).forEach { (directSu, shizukuReady) ->
            var helperCalls = 0
            val observed = observeTypedShellCapability(
                directSuProbe = { directSu },
                helperRootProbe = { helperCalls += 1; true },
                shizukuSnapshot = {
                    ShizukuBridge.Snapshot(
                        if (shizukuReady) ShizukuState.READY else ShizukuState.STOPPED,
                        ready = shizukuReady,
                    )
                },
            )

            assertTrue(observed.typedShellControlReady)
            assertEquals(0, helperCalls)
        }
    }

    @Test fun typedCapabilityProbesHelperOnceWhenNoOtherTypedRouteIsReady() {
        var helperCalls = 0
        val observed = observeTypedShellCapability(
            directSuProbe = { false },
            helperRootProbe = { helperCalls += 1; true },
            shizukuSnapshot = {
                ShizukuBridge.Snapshot(ShizukuState.STOPPED, ready = false)
            },
        )

        assertFalse(observed.directSuReady)
        assertFalse(observed.shizuku.ready)
        assertTrue(observed.typedShellControlReady)
        assertEquals(1, helperCalls)
    }
}
