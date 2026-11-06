package io.panelassistant.android

import android.content.ContextWrapper
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.http.AcceptedConfigTransaction
import io.panelassistant.android.http.ApplyAcceptedResult
import io.panelassistant.android.http.ConfigPostParameters
import io.panelassistant.android.http.ConfigValueProjection
import io.panelassistant.android.http.RevisionStore
import io.panelassistant.android.http.normalizeConfigPostParameters
import io.panelassistant.android.panelassistant.PanelAssistantManagedSettings
import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.platform.SystemEnv
import io.panelassistant.android.state.StateConverger
import io.panelassistant.android.testsupport.scratchDirectory
import io.panelassistant.android.util.BorrowedRendererSettings
import io.panelassistant.android.util.LatestOperationPolicy
import io.panelassistant.android.util.LatestOperationTimeoutPolicy
import io.panelassistant.android.util.RendererPreparationCoordinator
import io.panelassistant.android.util.RendererPreparationState
import io.panelassistant.android.util.RetirableMutationGate
import io.panelassistant.android.util.ServiceRuntimeOwner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
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
        val rig = PortalRenameRig(profileId, storedName = "Old name")
        rig.vendorResult = vendorResult
        val service = rig.service
        val owner = rig.owner
        val android = rig.android
        val vendorRequests = rig.vendorRequests
        try {
            rig.prefs.values["friendly_name"] = "New name"
            PaneldService::class.java.getDeclaredMethod(
                "enqueueReconfigure", Set::class.java, Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(service, setOf("friendly_name"), true)

            val settled = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < settled &&
                (owner.pendingLatestCount() > 0 || !owner.isRunning() || owner.current().mdns === rig.previousAdvertiser)
            ) {
                Thread.sleep(20)
            }
            assertTrue(owner.reconnect(requireNotNull(owner.observe())) {}.get(5, TimeUnit.SECONDS))

            assertTrue("the rename must leave the runtime running", owner.isRunning())
            val renamed = owner.current()
            assertSame("a rename must keep the running state core", rig.core, renamed.core)
            assertSame("a rename must keep the running command dispatcher", rig.commands, renamed.commands)
            assertTrue("the kept dispatcher must still admit commands", gate(rig.commands).isOpen())
            assertTrue("the kept state core must still be open", gate(rig.core).isOpen())
            assertNotSame("the advertiser carries the name, so it is replaced", rig.previousAdvertiser, renamed.mdns)
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

            // The same name accepted again (a repeated Home Assistant rename, a restore) writes nothing more,
            // and reopens Meta's editor only when Meta has not yet verified that name.
            settleReconfigure(service, owner, setOf("friendly_name"))
            assertEquals(listOf("New name"), android.writes)
            val portalRequests = when {
                !expectPortal -> emptyList()
                vendorResult == true -> listOf("New name")
                else -> listOf("New name", "New name")
            }
            assertEquals("a repeated accepted name reopens Meta's editor only while unverified", portalRequests, vendorRequests.toList())

            settleReconfigure(service, owner, setOf("ha_expose_temperature"))
            assertEquals("an unrelated refresh never opens Meta settings", portalRequests, vendorRequests.toList())
        } finally {
            owner.shutdown(5_000L) {}
        }
    }

    /**
     * Panel Assistant offers the Home Assistant name again on every new session. Once Meta's editor has
     * verified a name, the same name offered again leaves Meta alone; anything Meta has not verified is
     * still sent, including a name an older app stored before it could drive Meta's editor.
     */
    @Test fun aVerifiedNameOfferedAgainByANewSessionNeverReopensMetasEditor() {
        withPortal(storedName = "Old name") { rig ->
            rig.vendorResult = true
            offer(rig, "Kitchen")
            assertEquals(listOf("Kitchen"), rig.vendorRequests.toList())
            assertEquals("Verified: native result", read(rig.service, "portalNameStatus"))
            offer(rig, "Kitchen")
            assertEquals("a verified name offered again leaves Meta alone", listOf("Kitchen"), rig.vendorRequests.toList())
            assertEquals("Verified: native result", read(rig.service, "portalNameStatus"))
            offer(rig, "Garden")
            assertEquals("a different name still renames", listOf("Kitchen", "Garden"), rig.vendorRequests.toList())
        }
    }

    @Test fun aNameMetaFailedToVerifyIsSentAgain() {
        withPortal(storedName = "Old name") { rig ->
            rig.vendorResult = false
            offer(rig, "Kitchen")
            rig.vendorResult = true
            offer(rig, "Kitchen")
            assertEquals(listOf("Kitchen", "Kitchen"), rig.vendorRequests.toList())
            assertEquals("Verified: native result", read(rig.service, "portalNameStatus"))
        }
    }

    @Test fun aNameMetaNeverAnsweredIsSentAgain() {
        withPortal(storedName = "Old name") { rig ->
            rig.vendorResult = null
            offer(rig, "Kitchen")
            offer(rig, "Kitchen")
            assertEquals(listOf("Kitchen", "Kitchen"), rig.vendorRequests.toList())
        }
    }

    @Test fun aNameStoredByAnOlderAppIsSentToMetaOnItsFirstOffer() {
        withPortal(storedName = "Kitchen") { rig ->
            rig.vendorResult = true
            offer(rig, "Kitchen")
            assertEquals(listOf("Kitchen"), rig.vendorRequests.toList())
        }
    }

    @Test fun aVerifiedNameIsSentAgainAfterALaterRenameFailed() {
        withPortal(storedName = "Old name") { rig ->
            rig.vendorResult = true
            offer(rig, "Kitchen")
            rig.vendorResult = false
            offer(rig, "Garden")
            rig.vendorResult = true
            offer(rig, "Kitchen")
            assertEquals(listOf("Kitchen", "Garden", "Kitchen"), rig.vendorRequests.toList())
        }
    }

    /**
     * Meta answers on its own thread, so an older rename can report success after a newer one has started.
     * That late success must not mark its name verified: Meta may now show the newer attempt's result.
     */
    @Test fun aLateSuccessForASupersededRenameNeverMarksItsNameVerified() {
        withPortal(storedName = "Old name") { rig ->
            offer(rig, "Kitchen")
            offer(rig, "Garden")
            rig.vendorCallbacks[0](true, "native result")
            assertEquals("Pending native verification", read(rig.service, "portalNameStatus"))
            rig.vendorCallbacks[1](false, "native result")
            assertEquals("Failed: native result", read(rig.service, "portalNameStatus"))
            offer(rig, "Kitchen")
            assertEquals(listOf("Kitchen", "Garden", "Kitchen"), rig.vendorRequests.toList())
            assertEquals(3, rig.vendorCallbacks.size)
        }
    }

    private fun withPortal(storedName: String, block: (PortalRenameRig) -> Unit) {
        val rig = PortalRenameRig("meta-portal", storedName)
        try { block(rig) } finally { rig.owner.shutdown(5_000L) {} }
    }

    /**
     * Panel Assistant's session writes friendly_name: the Configure page's validation, the accepted-settings
     * commit and its reconfigure into the service's refresh, which this waits for.
     */
    private fun offer(rig: PortalRenameRig, name: String) {
        val before = read(rig.service, "appliedNetworkConfiguration")
        val reconfigures = rig.reconfigured.size
        val code = runBlocking {
            PanelAssistantManagedSettings.apply(
                mapOf("friendly_name" to name),
                admit = { true },
                validate = { values ->
                    val raw = io.ktor.http.Parameters.build { values.forEach { (key, value) -> append(key, value) } }
                    (normalizeConfigPostParameters(raw, io.panelassistant.android.config.Capabilities()) as? ConfigPostParameters.Ok)
                        ?.values?.let { ok -> ok.names().associateWith { ok[it].orEmpty() } }
                },
                commit = { accepted, admit -> rig.transaction.applyAccepted(accepted, admit = admit) is ApplyAcceptedResult.Applied },
            )
        }
        assertNull("the session's name is accepted", code)
        assertEquals("every offer reaches the service's refresh as friendly_name", listOf(setOf("friendly_name")), rig.reconfigured.drop(reconfigures))
        awaitSettled(rig.service, rig.owner, before)
    }

    /** A service on a real runtime owner whose refresh reaches a fake of Meta's name editor. */
    private inner class PortalRenameRig(profileId: String?, storedName: String) {
        val prefs = fakePreferences(mapOf("panel_id" to PANEL, "friendly_name" to storedName))
        val config = Config(prefs.instance)
        val service = PaneldService()
        val android = FakeAndroidName("Jenna")
        val vendorRequests = CopyOnWriteArrayList<String>()
        /** What Meta's editor reports for each request; null leaves the request unanswered. */
        @Volatile var vendorResult: Boolean? = null
        /** Each request's completion, so a test can answer it later and in any order. */
        val vendorCallbacks = CopyOnWriteArrayList<(Boolean, String) -> Unit>()
        val reconfigured = CopyOnWriteArrayList<Set<String>>()
        val core = idleCore(config)
        val commands = idleCommands()
        val previousAdvertiser = MdnsAdvertiser(
            ContextWrapper(null), config, acquireMulticastLock = { {} }, discoveryId = { null },
        )
        val owner: ServiceRuntimeOwner<PaneldService.NetworkRuntime>
        val transaction: AcceptedConfigTransaction

        init {
            fenceProcessExit(service)
            setField(service, "config", config)
            setField(service, "liveSettingAuthority", LiveSettingAuthority(emptySet(), bootIdentity = { "boot" }))
            setField(service, "appliedNetworkConfiguration", call(service, "currentNetworkConfigurationSnapshot"))
            setField(service, "androidDeviceName\$delegate", lazy { android.deviceName })
            profileId?.let {
                setField(service, "profile", io.panelassistant.android.device.profile.BundledProfileFixtures.profile(it))
            }
            val accessibilityType = io.panelassistant.android.platform.AccessibilityActions::class.java
            val accessibility = java.lang.reflect.Proxy.newProxyInstance(
                accessibilityType.classLoader, arrayOf(accessibilityType),
            ) { _, method, args ->
                when (method.name) {
                    "renamePortal" -> {
                        vendorRequests += args!![0] as String
                        @Suppress("UNCHECKED_CAST")
                        val finished = args[1] as (Boolean, String) -> Unit
                        vendorCallbacks += finished
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
            val reconfigure = PaneldService::class.java.getDeclaredMethod(
                "performNetworkReconfigure", ServiceRuntimeOwner.LatestMutation::class.java,
            ).apply { isAccessible = true }
            owner = ServiceRuntimeOwner(
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
            val serviceReconfigure = PaneldService::class.java.getDeclaredMethod("reconfigure", Set::class.java)
                .apply { isAccessible = true }
            transaction = AcceptedConfigTransaction(
                config = config,
                revisions = RevisionStore(scratchDirectory("rename-test-revisions").toFile()),
                rendererPreparation = RendererPreparationCoordinator(
                    builtinPackage = "builtin",
                    state = { RendererPreparationState("builtin", "") },
                    borrow = { BorrowedRendererSettings("http://ha.test", "", "", 0, "", null) },
                    persist = { true },
                ),
                system = SystemController(object : SystemEnv {
                    override val ownPackage = "io.panelassistant.android"
                    override fun isInstalled(pkg: String) = false
                    override fun launchComponent(pkg: String): String? = null
                    override fun homeActivities(): List<ActivityRef> = emptyList()
                    override fun defaultHome(): ActivityRef? = null
                    override fun directStart(component: String) = false
                }),
                values = ConfigValueProjection(
                    config = config,
                    configLiveValues = { emptyMap() },
                    renderedLiveValues = { emptyMap() },
                    pendingLiveSettings = { emptyMap() },
                    stalledLiveSettings = { emptySet() },
                    proximityJson = { "{}" },
                    powerSafetyJson = { "{}" },
                    haAreaCatalogJson = { "{}" },
                ),
                applySetting = { _, _ -> io.panelassistant.android.LiveSettingRequestOutcome.APPLIED },
                onEntityTargetChanged = {},
                setEntityLearningEnabled = { true },
                effectiveDashboardIsBuiltin = { false },
                requestTameReconcileAfterCommit = { true },
                snapInvalidate = {},
                onReconfigure = { keys -> reconfigured += keys; serviceReconfigure.invoke(service, keys) },
            )
            assertTrue(owner.start {}.get(5, TimeUnit.SECONDS))
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
        awaitSettled(service, owner, before)
    }

    private fun awaitSettled(service: PaneldService, owner: ServiceRuntimeOwner<PaneldService.NetworkRuntime>, before: Any?) {
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
