package io.panelassistant.android.control

import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Real su availability callers with a file shared by successive process owners. */
class SuBootObservationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val processes = CountingSuLaunch()
    private var now = 0L

    @After fun close() = processes.close()

    // Reflection lets this same caller test overlay compile against a build predating the additive
    // observation API. An older build reports no loss, which must fail the loss assertions below.
    private fun owner(directory: File, boot: () -> String? = { BOOT_A }): SuShell =
        SuShell(launch = processes::launch, nowMs = { now }).also { shell ->
            shell.javaClass.methods.singleOrNull { it.name == "installBootHistory" && it.parameterCount == 2 }
                ?.invoke(shell, directory, boot)
        }

    private fun lost(shell: SuShell): Boolean =
        shell.javaClass.methods.singleOrNull { it.name == "rootLostSinceBoot" }
            ?.invoke(shell) as? Boolean ?: false

    @Test fun directAvailabilityLossSurvivesReplacementInTheSameBootAndRecoveryClearsIt() {
        val directory = temporary.newFolder()
        val first = owner(directory)
        assertTrue(first.availableIsolated())
        processes.mode = CountingSuLaunch.Mode.DEAD
        val replacement = owner(directory)
        assertFalse("unknown availability is not loss", lost(replacement))
        assertFalse(replacement.availableIsolated())
        assertTrue("a new process retains this boot's successful su answer", lost(replacement))
        val work = processes.rootWork()
        repeat(10) { assertTrue(lost(replacement)); now += 61_000L }
        assertEquals("reading the loss state must not start su", work, processes.rootWork())
        processes.mode = CountingSuLaunch.Mode.WORKING
        assertTrue(replacement.availableIsolated())
        assertFalse("root recovery clears the loss", lost(replacement))
    }

    @Test fun regularRootAvailabilityAlsoPersistsItsProof() {
        val directory = temporary.newFolder()
        assertTrue(owner(directory).available())
        processes.mode = CountingSuLaunch.Mode.DEAD
        val replacement = owner(directory)
        assertFalse(replacement.available())
        assertTrue(lost(replacement))
    }

    @Test fun successfulIsolatedRootCommandsPersistProofWithoutASeparateAvailabilityProbe() {
        val directory = temporary.newFolder()
        assertEquals("", owner(directory).runOutputIsolatedBounded("true", 1L, 5_000L))
        processes.mode = CountingSuLaunch.Mode.DEAD
        val replacement = owner(directory)
        assertFalse(replacement.availableCachedIsolated())
        assertTrue(lost(replacement))
        val attempts = processes.attempts.get()
        repeat(10) { assertFalse(replacement.availableCachedIsolated()) }
        assertEquals("the bounded availability cache is preserved", attempts, processes.attempts.get())
    }

    @Test fun knownSuAvailabilityLossRetainsTheNegativeProbeCache() {
        val shell = owner(temporary.newFolder())
        assertTrue(shell.availableIsolated())
        processes.mode = CountingSuLaunch.Mode.DEAD
        assertFalse(shell.available())
        assertTrue(lost(shell))
        val attempts = processes.attempts.get()
        repeat(10) { assertFalse(shell.available()) }
        assertEquals("nested availability denial must retain the negative TTL", attempts, processes.attempts.get())
    }

    @Test fun markerWriteFailureRetainsOnlyTheCurrentProcessesRealProof() {
        val directory = temporary.newFolder()
        File(directory, "su-answered-boot.tmp").mkdir()
        val shell = owner(directory)
        assertTrue(shell.availableIsolated())
        processes.mode = CountingSuLaunch.Mode.DEAD
        assertFalse(shell.availableIsolated())
        assertTrue("a disk failure cannot erase the observed su answer", lost(shell))
        val replacement = owner(directory)
        assertFalse(replacement.availableIsolated())
        assertFalse("a replacement cannot invent an unavailable durable proof", lost(replacement))
    }

    @Test fun aNewBootNeverInheritsThePreviousBootProof() {
        val directory = temporary.newFolder()
        assertTrue(owner(directory).availableIsolated())
        processes.mode = CountingSuLaunch.Mode.DEAD
        val replacement = owner(directory) { BOOT_B }
        assertFalse(replacement.availableIsolated())
        assertFalse(lost(replacement))
    }

    @Test fun freshRootlessAndHelperOnlyPanelsNeverReportLostDirectRoot() {
        processes.mode = CountingSuLaunch.Mode.DEAD
        val shell = owner(temporary.newFolder())
        assertFalse(shell.availableIsolated())
        val privilege = observePrivilegedRoutes(shell::observedUsable) { true }
        assertTrue(privilege.helperRootReady)
        assertFalse(privilege.directSuReady)
        assertFalse(lost(shell))
    }

    @Test fun missingOrUnreadableBootIdentityAndCorruptMarkerCannotInventProof() {
        val directory = temporary.newFolder()
        assertTrue(owner(directory).availableIsolated())
        processes.mode = CountingSuLaunch.Mode.DEAD
        for (boot in listOf<() -> String?>({ null }, { error("proc unreadable") }, { "bad-id" })) {
            val replacement = owner(directory, boot)
            assertFalse(replacement.availableIsolated())
            assertFalse(lost(replacement))
        }
        File(directory, "su-answered-boot").writeText("$BOOT_A trailing")
        val corrupt = owner(directory)
        assertFalse(corrupt.availableIsolated())
        assertFalse(lost(corrupt))
        File(directory, "su-answered-boot").delete()
        File(directory, "su-answered-boot").mkdir()
        val unreadable = owner(directory)
        assertFalse(unreadable.availableIsolated())
        assertFalse(lost(unreadable))
    }

    private companion object {
        const val BOOT_A = "00000000-0000-0000-0000-000000000001"
        const val BOOT_B = "00000000-0000-0000-0000-000000000002"
    }
}
