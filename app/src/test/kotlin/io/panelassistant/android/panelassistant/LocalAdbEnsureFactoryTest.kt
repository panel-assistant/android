package io.panelassistant.android.panelassistant

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.util.MainRecoveryWireFixture
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun theRealFactoryUsesTheMainEndpointWithoutPublishingOrStagingRecovery() = runTest {
        val context = NoStagingContext()
        val wire = MainRecoveryWireFixture()
        val config = newConfig(MemoryPreferences(), object : ContentResolver(null) {})
        val runtime = LocalAdbRuntime.create(context, backgroundScope, config, wire.client, StandardTestDispatcher(testScheduler))
        runtime.session("session", true)
        val result = (runtime.request("adb.ready", org.json.JSONObject(), { true }).result as org.json.JSONObject)
        assertTrue(result.getBoolean("ready"))
        assertTrue(wire.commands.isNotEmpty())
        wire.commands.groupBy { it.first }.values.forEach { commands ->
            assertEquals(listOf("VERSION", "BUILDID", "STATUS"), commands.map { it.second })
        }
        assertEquals(0, context.stagingReads)
    }

    @Test fun theRealFactoryReportsOldMissingAndStaleMainHelpersUnavailableWithoutStaging() = runTest {
        for (mode in listOf("missing", "old", "stale", "lost")) {
            val context = NoStagingContext()
            val wire = MainRecoveryWireFixture().apply {
                when (mode) {
                    "missing" -> missing = true
                    "old" -> version = "HELPER version=1.15.0 proto=1.15"
                    "stale" -> build = "BUILDID ${"0".repeat(64)}"
                    "lost" -> beforeOperation = { error("lost main") }
                }
            }
            val runtime = LocalAdbRuntime.create(context, backgroundScope, newConfig(MemoryPreferences(), object : ContentResolver(null) {}), wire.client, StandardTestDispatcher(testScheduler))
            runtime.session("session", true)
            val result = (runtime.request("adb.ready", org.json.JSONObject(), { true }).result as org.json.JSONObject)
            assertFalse(mode, result.getBoolean("ready"))
            assertEquals(mode, 0, context.stagingReads)
            assertTrue(wire.commands.none { it.second == "ADBRECOVERYENSURE" || it.second == "PING" })
        }
    }
}
