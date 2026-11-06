package io.panelassistant.android.panelassistant

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.util.EnsureWireFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

internal class LocalAdbEnsureFactoryTest : PanelRuntimeRig() {
    private class NoStagingContext : ContextWrapper(null) {
        var stagingReads = 0
        override fun getApplicationContext(): Context = this
        override fun getAssets(): AssetManager { stagingReads++; error("bootstrap must not read assets") }
        override fun getCacheDir(): File { stagingReads++; error("bootstrap must not stage files") }
    }

    @Suppress("UNCHECKED_CAST")
    private fun bootstrap(runtime: LocalAdbRuntime): Boolean {
        // Invoke the actual factory callback without starting Android's UI/heartbeat machinery.
        val callback = LocalAdbRuntime::class.java.getDeclaredField("bootstrap").apply { isAccessible = true }.get(runtime) as () -> Boolean
        return callback()
    }

    @Test fun theRealFactoryStartsRecoveryThroughItsIdentityAdmittedHelperWithoutStaging() = runTest {
        val context = NoStagingContext()
        val config = newConfig(MemoryPreferences(), object : ContentResolver(null) {})
        val wire = EnsureWireFixture()
        val runtime = LocalAdbRuntime.create(context, backgroundScope, config, wire.client)
        assertTrue(bootstrap(runtime))
        assertEquals(listOf(1 to "VERSION", 1 to "BUILDID", 1 to "ADBRECOVERYENSURE"), wire.commands)
        wire.identity = wire.identity.copy(build = "BUILDID ${"0".repeat(64)}")
        assertFalse(bootstrap(runtime))
        assertEquals(listOf(2 to "VERSION", 2 to "BUILDID"), wire.commands.takeLast(2))
        assertEquals(0, context.stagingReads)
    }

    @Test fun theRealFactoryReturnsUnavailableForMissingOldRefusedAndLostHelpersWithoutStaging() = runTest {
        for (mode in listOf("missing", "old", "refused", "lost")) {
            val context = NoStagingContext()
            val wire = EnsureWireFixture().apply {
                when (mode) {
                    "missing" -> missing = true
                    "old" -> identity = identity.copy(version = "HELPER version=1.14.0 proto=1.14")
                    "refused" -> identity = identity.copy(reply = "ADBRECOVERYENSURE UNAVAILABLE\n".toByteArray())
                    "lost" -> identity = identity.copy(reply = null)
                }
            }
            val runtime = LocalAdbRuntime.create(context, backgroundScope, newConfig(MemoryPreferences(), object : ContentResolver(null) {}), wire.client)
            assertFalse(mode, bootstrap(runtime))
            assertEquals(mode, 0, context.stagingReads)
            assertTrue(wire.commands.none { it.second == "PING" })
        }
    }
}
