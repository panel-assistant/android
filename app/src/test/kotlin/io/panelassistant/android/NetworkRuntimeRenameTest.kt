package io.panelassistant.android

import android.content.ContextWrapper
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.state.StateConverger
import io.panelassistant.android.util.LatestOperationPolicy
import io.panelassistant.android.util.LatestOperationTimeoutPolicy
import io.panelassistant.android.util.RetirableMutationGate
import io.panelassistant.android.util.ServiceRuntimeOwner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Renaming a panel changes only what its mDNS advertisement says. The reconfigure the Configure page
 * starts keeps the running state core and command dispatcher, so nothing queued is cancelled and no
 * state is restored or reported again; only the advertiser is replaced with the new name.
 */
internal class NetworkRuntimeRenameTest : io.panelassistant.android.testsupport.PanelRuntimeRig() {
    @Test fun aRenamedPanelKeepsItsStateCoreAndDispatcherAndAdvertisesTheNewName() = checkRename()

    @Test fun aHomeAssistantNameOfferReachesMetasEditorOnBothPortalProfilesEvenIfAlreadyStored() {
        listOf("meta-portal", "meta-portal-10-gen2").forEach { checkRename(it, expectPortal = true) }
    }

    @Test fun otherProfilesNeverOpenMetasEditorForANameOffer() = checkRename("generic")

    @Test fun nativeVerificationResultIsReportedSeparatelyFromAcceptedName() {
        listOf(false, true).forEach { checkRename("meta-portal", expectPortal = true, vendorResult = it) }
    }

    private fun checkRename(profileId: String? = null, expectPortal: Boolean = false, vendorResult: Boolean? = null) {
        val prefs = fakePreferences(mapOf("panel_id" to PANEL, "friendly_name" to "Old name"))
        val config = Config(prefs.instance)
        val service = PaneldService()
        fenceProcessExit(service)
        setField(service, "config", config)
        setField(service, "liveSettingAuthority", LiveSettingAuthority(emptySet(), bootIdentity = { "boot" }))
        setField(service, "appliedNetworkConfiguration", call(service, "currentNetworkConfigurationSnapshot"))
        val android = FakeAndroidName("Jenna")
        setField(service, "androidDeviceName\$delegate", lazy { android.deviceName })
        profileId?.let {
            setField(service, "profile", io.panelassistant.android.device.profile.BundledProfileFixtures.profile(it))
        }
        val vendorRequests = CopyOnWriteArrayList<String>()
        val accessibilityType = io.panelassistant.android.platform.AccessibilityActions::class.java
        val accessibility = java.lang.reflect.Proxy.newProxyInstance(
            accessibilityType.classLoader, arrayOf(accessibilityType),
        ) { _, method, args ->
            when (method.name) {
                "renamePortal" -> {
                    vendorRequests += args!![0] as String
                    @Suppress("UNCHECKED_CAST")
                    val finished = args[1] as (Boolean, String) -> Unit
                    vendorResult?.let { finished(it, "native result") }
                    true
                }
                "cancelPortalRename" -> Unit
                else -> false
            }
        }
        // The same caller test also runs on the base: without the optional vendor connection the
        // explicit name is still accepted, but no native rename request is emitted.
        PaneldService::class.java.declaredFields.firstOrNull { it.name == "portalNameAccessibility" }
            ?.apply { isAccessible = true }?.set(service, accessibility)

        val core = idleCore(config)
        val commands = idleCommands()
        val previousAdvertiser = MdnsAdvertiser(
            ContextWrapper(null), config, acquireMulticastLock = { {} }, discoveryId = { null },
        )
        val reconfigure = PaneldService::class.java.getDeclaredMethod(
            "performNetworkReconfigure", ServiceRuntimeOwner.LatestMutation::class.java,
        ).apply { isAccessible = true }
        val owner = ServiceRuntimeOwner<PaneldService.NetworkRuntime>(
            initial = PaneldService.NetworkRuntime(core, commands, previousAdvertiser),
            threadName = "rename-test-runtime",
            latestOperation = LatestOperationPolicy(
                timeout = LatestOperationTimeoutPolicy(
                    budgetMs = 30_000L, schedule = { _, _ -> true }, cancel = {}, onTimeout = {},
                ),
                operation = { reconfigure.invoke(service, this) },
            ),
        )
        setField(service, "runtime", owner)
        setField(service, "mdnsRuntimeReconciler", MdnsRuntimeReconciler(owner) { _, _ -> })
        try {
            assertTrue(owner.start {}.get(5, TimeUnit.SECONDS))

            prefs.values["friendly_name"] = "New name"
            PaneldService::class.java.getDeclaredMethod(
                "enqueueReconfigure", Set::class.java, Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(service, setOf("friendly_name"), true)

            val settled = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < settled &&
                (owner.pendingLatestCount() > 0 || !owner.isRunning() || owner.current().mdns === previousAdvertiser)
            ) {
                Thread.sleep(20)
            }
            assertTrue(owner.reconnect(requireNotNull(owner.observe())) {}.get(5, TimeUnit.SECONDS))

            assertTrue("the rename must leave the runtime running", owner.isRunning())
            val renamed = owner.current()
            assertSame("a rename must keep the running state core", core, renamed.core)
            assertSame("a rename must keep the running command dispatcher", commands, renamed.commands)
            assertTrue("the kept dispatcher must still admit commands", gate(commands).isOpen())
            assertTrue("the kept state core must still be open", gate(core).isOpen())
            assertNotSame("the advertiser carries the name, so it is replaced", previousAdvertiser, renamed.mdns)
            assertEquals("New name", read(renamed.mdns, "runtimeFriendlyName"))
            val applied = read(service, "appliedNetworkConfiguration") as NetworkConfigurationSnapshot
            assertEquals("New name", applied.runtime.friendlyName)
            assertEquals("Android's device name follows the rename", listOf("New name"), android.writes)
            assertEquals(if (expectPortal) listOf("New name") else emptyList<String>(), vendorRequests.toList())
            if (expectPortal) {
                assertEquals(
                    when (vendorResult) { true -> "Verified: native result"; false -> "Failed: native result"; null -> "Pending native verification" },
                    read(service, "portalNameStatus"),
                )
            }

            // The same name accepted again (a repeated Home Assistant rename, a restore) writes nothing more.
            settleReconfigure(service, owner, setOf("friendly_name"))
            assertEquals(listOf("New name"), android.writes)
            assertEquals(
                "a repeated accepted name still verifies Meta's independent name",
                if (expectPortal) listOf("New name", "New name") else emptyList<String>(), vendorRequests.toList(),
            )

            settleReconfigure(service, owner, setOf("ha_expose_temperature"))
            assertEquals("an unrelated refresh never opens Meta settings", if (expectPortal) 2 else 0, vendorRequests.size)
        } finally {
            owner.shutdown(5_000L) {}
        }
    }

    /** Android's Settings.Global device name, as the service reads and writes it. */
    private class FakeAndroidName(var name: String?) {
        val writes = mutableListOf<String>()
        val deviceName = io.panelassistant.android.platform.AndroidDeviceName(
            granted = { true }, read = { name }, write = { writes += it; name = it; true },
        )
    }

    private fun settleReconfigure(service: PaneldService, owner: ServiceRuntimeOwner<PaneldService.NetworkRuntime>, keys: Set<String>) {
        val before = read(service, "appliedNetworkConfiguration")
        PaneldService::class.java.getDeclaredMethod(
            "enqueueReconfigure", Set::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(service, keys, true)
        val settled = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < settled && (owner.pendingLatestCount() > 0 || read(service, "appliedNetworkConfiguration") === before)) {
            Thread.sleep(20)
        }
        assertTrue(owner.reconnect(requireNotNull(owner.observe())) {}.get(5, TimeUnit.SECONDS))
        assertTrue("the reconfigure must settle", read(service, "appliedNetworkConfiguration") !== before)
    }

    /**
     * Hiding a sensor with its exposure pip on the Configure page must reach Panel Assistant: the reconfigure
     * the config route requests re-reports the channel, now unavailable, so Home Assistant stops showing the
     * value it last received.
     */
    @Test fun hidingASensorOnTheConfigurePageReportsItNotSharedToPanelAssistant() {
        val rig = rig(configure = {
            it.setRaw(requireNotNull(SettingsRegistry.spec("panel_id")), "exposure-test")
            it.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Exposure test")
        })
        // A diagnostic count and room readings, each delivered through its own production path.
        val channels = listOf("diag_wifi_outages_24h", "illuminance", "temperature", "humidity")
        val seen = channels.associateWith { CopyOnWriteArrayList<StateConverger.Observation.Reportable>() }
        val notShared = StateConverger.Observation.NoReading(StateConverger.NoReadingReason.NOT_SHARED)
        rig.core.addStateSink { key, observation -> seen[key]?.add(observation) }
        val service = PaneldService()
        fenceProcessExit(service)
        setField(service, "config", rig.config)
        setField(service, "liveSettingAuthority", LiveSettingAuthority(emptySet(), bootIdentity = { "boot" }))
        setField(service, "appliedNetworkConfiguration", call(service, "currentNetworkConfigurationSnapshot"))
        val owner = runtimeOwner(service, rig.core, rig.commands, rig.config)
        try {
            assertTrue(owner.start {}.get(5, TimeUnit.SECONDS))
            rig.core.reconcile("diag_wifi_outages_24h")
            rig.core.publishLight(42)
            rig.core.publishTemperature(21.5f)
            rig.core.publishHumidity(40f)
            for (channel in channels) {
                assertTrue("while exposed $channel is reported", seen.getValue(channel).lastOrNull() is StateConverger.Observation.Known)
            }

            // What the exposure pips commit, then the reconfigure DirectConfigPost requests for the applied keys.
            channels.forEach { rig.config.setHaExposed(it, false) }
            PaneldService::class.java.getDeclaredMethod(
                "enqueueReconfigure", Set::class.java, Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(service, channels.map { "ha_expose_$it" }.toSet(), true)

            awaitCondition {
                drainStatePump()
                channels.all { seen.getValue(it).lastOrNull() == notShared }
            }
            assertEquals(
                "Panel Assistant must learn each hidden sensor is not shared, not keep its last value",
                channels.associateWith { notShared },
                channels.associateWith { seen.getValue(it).lastOrNull() },
            )
        } finally {
            owner.shutdown(5_000L) {}
            rig.close()
        }
    }

    /** The service's runtime owner around [core] and [commands], running the real Configure-page reconfigure. */
    private fun runtimeOwner(
        service: PaneldService,
        core: PanelStateCore,
        commands: PanelCommandDispatcher,
        config: Config,
    ): ServiceRuntimeOwner<PaneldService.NetworkRuntime> {
        val reconfigure = PaneldService::class.java.getDeclaredMethod(
            "performNetworkReconfigure", ServiceRuntimeOwner.LatestMutation::class.java,
        ).apply { isAccessible = true }
        val owner = ServiceRuntimeOwner<PaneldService.NetworkRuntime>(
            initial = PaneldService.NetworkRuntime(
                core, commands,
                MdnsAdvertiser(ContextWrapper(null), config, acquireMulticastLock = { {} }, discoveryId = { null }),
            ),
            threadName = "exposure-test-runtime",
            latestOperation = LatestOperationPolicy(
                timeout = LatestOperationTimeoutPolicy(
                    budgetMs = 30_000L, schedule = { _, _ -> true }, cancel = {}, onTimeout = {},
                ),
                operation = { reconfigure.invoke(service, this) },
            ),
        )
        setField(service, "runtime", owner)
        setField(service, "mdnsRuntimeReconciler", MdnsRuntimeReconciler(owner) { _, _ -> })
        return owner
    }

    /** A state core with nothing registered and no Home Assistant address, so a link resolve returns at once. */
    private fun idleCore(config: Config): PanelStateCore = allocate(PanelStateCore::class.java).also { core ->
        setOn(core, "config", config)
        setOn(core, "lifecycle", RetirableMutationGate())
        setOn(core, "converger", io.panelassistant.android.state.StateConverger())
        setOn(core, "haLinkResolutionThread", AtomicReference<Thread?>())
    }

    /** A command dispatcher with nothing queued. */
    private fun idleCommands(): PanelCommandDispatcher = allocate(PanelCommandDispatcher::class.java).also { commands ->
        val zigbee = ZigbeeActuationCoordinator()
        setOn(commands, "lifecycle", RetirableMutationGate())
        setOn(commands, "zigbeeActuation", zigbee)
        setOn(commands, "zigbeeLease", zigbee.activate())
        setOn(commands, "queue", CommandQueue())
        setOn(commands, "operations", ManageOperations(ManageTable(), { io.panelassistant.android.security.LocalApprovalBroker.instance }))
        setOn(commands, "zigbeeWorker", io.panelassistant.android.util.LatestDispatcher.singleSlot<Boolean>("rename-test-zigbee", consume = {}))
    }

    private fun gate(target: Any): RetirableMutationGate = read(target, "lifecycle") as RetirableMutationGate

    /** The finalizer ends in `exitProcess` on an incomplete teardown; claiming its recovery slot keeps the JVM. */
    private fun fenceProcessExit(service: PaneldService) {
        val boundary = read(service, "teardownBoundary") as ServiceTeardownBoundary
        assertTrue(boundary.recordCompletionAndClaimRecovery(false))
    }

    private fun call(service: PaneldService, name: String): Any? =
        PaneldService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)

    private fun read(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun setOn(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun setField(service: PaneldService, name: String, value: Any?) {
        PaneldService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    private companion object {
        val PANEL = "rename-test-panel-${ProcessHandle.current().pid()}"
    }
}
