package io.panelassistant.android

import android.content.ContextWrapper
import io.panelassistant.android.util.InstallProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service's software operations, driven through the install entry point the Install tab and the
 * blocked-dashboard repair call. Every operation shares the one process-wide [InstallProgress] lane,
 * so what an owner sees is whether its request was admitted and what the lane says when it ends.
 */
class ServiceComponentOperationsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val context = ContextWrapper(null)

    @After fun releaseTheLane() {
        scope.cancel()
        InstallProgress.start("test cleanup")?.let { InstallProgress.finish(it, "idle") }
    }

    @Test fun anInstallIsRefusedWhileAnotherOperationHoldsTheLaneAndTheHolderKeepsIt() {
        val holder = InstallProgress.start("restore")
        assertNotNull(holder)

        val admitted = operations().installComponent("companion", "install", "")

        assertFalse(admitted)
        assertTrue(InstallProgress.running)
        assertEquals("restore", InstallProgress.component)
        InstallProgress.finish(holder!!, "done")
    }

    @Test fun retiredPanelPickerNeverStartsAnOperation() {
        var started = 0
        assertFalse(operations().installComponent("paneld", "install", "v1.0") { started++ })
        assertFalse(InstallProgress.running)
        assertEquals(0, started)
    }

    @Test fun anInstallThatFailsStillReleasesTheLaneWithItsError() {
        val admitted = operations(profile = { error("no profile") }).installComponent("companion", "install", "2026.1.1")

        assertTrue(admitted)
        awaitLaneReleased()
        assertEquals("error: no profile", InstallProgress.message)
    }

    @Test fun anInstallAdmittedAsTheServiceStopsDoesNotStrandTheLane() {
        scope.cancel()

        val admitted = operations().installComponent("companion", "install", "")

        assertTrue(admitted)
        awaitLaneReleased()
        assertEquals("cancelled", InstallProgress.message)
    }

    private fun operations(profile: () -> io.panelassistant.android.device.DeviceProfile = { error("unused") }) =
        ServiceComponentOperations(context, scope, { error("no config") }, { error("no core") }, profile)

    private fun awaitLaneReleased() {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (InstallProgress.running && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse("the operation must release the lane", InstallProgress.running)
    }
}
