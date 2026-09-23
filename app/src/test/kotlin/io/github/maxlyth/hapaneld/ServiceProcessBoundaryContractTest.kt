package io.github.maxlyth.hapaneld

import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceProcessBoundaryContractTest {
    @Test fun watchdogObservationsPrecedeEveryMqttAdmissionGate() {
        val service = source("PaneldService.kt")
        val watchdog = service.substring(service.indexOf("private fun startMqttWatchdog()"), service.indexOf("private fun revalidateMdns("))
        val tick = watchdog.indexOf("requestWatchdogLocalObservation(runtime) { it.mqtt }")
        assertTrue("running watchdog must request hardware observations", tick >= 0)
        assertTrue(tick > watchdog.indexOf("if (!mqttWatchdogAlive) break"))
        assertTrue(tick < watchdog.indexOf("if (terminalRecoveryNeeded)"))
        assertTrue(tick < watchdog.indexOf("supervisor.tick("))
        assertTrue(tick < watchdog.indexOf("if (rebuild != null) continue"))
        assertTrue(tick < watchdog.indexOf("HeartbeatAdmission.decide("))
        val bridge = source("MqttBridge.kt")
        val heartbeat = bridge.substring(bridge.indexOf("fun heartbeat()"), bridge.indexOf("private fun syncLocalState()"))
        assertFalse("MQTT heartbeats must not duplicate local observations", heartbeat.contains("syncLocalState()"))
    }

    @Test fun nativeActivationAndPublishedServiceOwnersStayBehindStartupFence() {
        val service = source("PaneldService.kt")
        val create = service.substring(service.indexOf("override fun onCreate()"), service.indexOf("private fun buildMqtt("))
        val start = service.substring(service.indexOf("override fun onStartCommand("), service.indexOf("private fun startMqttWatchdog()"))
        val fence = start.indexOf("restartLease.awaitPredecessor()")
        val complete = start.indexOf("}, complete = {")
        assertTrue(fence >= 0)
        assertTrue(complete > fence)
        listOf(
            "HaNetworkPathRuntime.install(haNetworkPath)",
            "PathProbeRuntime.install(haPathProbe, haSocketClock)",
            "HaLifecycleRuntime.install(haLifecycle)",
            "BuiltinDashboard.setRendererSettledListener(rendererSettledForLifecycle)",
            "CameraPermissionPrompt.install(",
            "config.registerChangeListener(voicePrefsListener)",
            "voiceForegroundRetry =",
            "voice.start()",
        ).forEach { publication ->
            assertFalse("construction published $publication", create.contains(publication))
            assertTrue("$publication must follow the predecessor fence", start.indexOf(publication) > fence)
        }
        assertTrue(start.indexOf("activeRuntime.mqtt.haLifecycleLease?.let") > start.indexOf("HaLifecycleRuntime.install(haLifecycle)"))
        assertTrue(start.contains("HaLifecycleRuntime.installMqttLease(haLifecycle, lease)"))
        assertTrue(start.indexOf("initialNativeDemand = currentPanelAssistantTransportDemand()") > start.indexOf("activeRuntime.mqtt.start()"))
        // These short callbacks contain no nested blocks. Bound their closing brace, not merely
        // their opener: an empty callback followed by publication must fail this contract.
        val activation = Regex("""\}, complete = \{([^{}]*)\}""").find(start)
        assertTrue("startup completion callback must remain bounded", activation != null)
        assertTrue(activation!!.groupValues[1].contains("panelAssistantTransport.replaceDemand(initialNativeDemand)"))
        assertFalse(start.substring(0, activation.range.first).contains("replaceDemand("))
        val tail = start.substring(activation.range.last + 1)
        assertFalse(tail.contains("refreshPanelAssistantTransport()"))
        assertFalse(tail.contains("replaceDemand("))
        val refresh = service.substring(service.indexOf("private fun refreshPanelAssistantTransport()"), service.indexOf("private fun refreshHaLifecycleWatch()"))
        val admitted = Regex("""runtime\.runIfRunning \{([^{}]*)\}""").find(refresh)
        assertTrue("refresh admission callback must remain bounded", admitted != null)
        assertTrue(admitted!!.groupValues[1].contains("panelAssistantTransport.replaceDemand(demand)"))
        assertFalse(refresh.substring(0, admitted.range.first).contains("replaceDemand("))
        assertFalse(refresh.substring(admitted.range.last + 1).contains("replaceDemand("))
    }

    @Test fun servicePromotesForegroundBeforeHeavyweightCreation() {
        val source = source("PaneldService.kt")
        val create = source.substring(
            source.indexOf("override fun onCreate()"),
            source.indexOf("private fun buildMqtt("),
        )
        val start = source.substring(
            source.indexOf("override fun onStartCommand("),
            source.indexOf("private fun startMqttWatchdog()"),
        )

        val promotion = create.indexOf("startForegroundCompat(nativeString(R.string.starting), silent = true)")
        assertTrue(promotion > create.indexOf("super.onCreate()"))
        listOf(
            "SERVICE_RESTART_BARRIER.enter()",
            "Config(this)",
            "migrateLiveStore()",
            "RuntimeProfileRegistry(this)",
            "PaneldServer(",
        ).forEach { heavyweight ->
            assertTrue("foreground promotion must precede $heavyweight", promotion < create.indexOf(heavyweight))
        }
        assertTrue(create.indexOf("migrateLiveStore()") < create.indexOf("ensurePanelId()"))
        assertTrue(
            create.indexOf("ensurePanelId()") <
                create.indexOf("updateForegroundStatus(nativeString(R.string.starting))"),
        )
        assertFalse(start.contains("startForegroundCompat("))
    }

    @Test fun mqttNoProgressBoundaryDoesNotMutateTransportAndRetriesRejectedAdmission() {
        val service = source("PaneldService.kt")
        val watchdog = service.substring(
            service.indexOf("private fun startMqttWatchdog()"),
            service.indexOf("private fun registerNetworkCallback()"),
        )
        val recovery = watchdog.substring(
            watchdog.indexOf("is ConnectionSupervisor.Action.ProcessRecovery"),
            watchdog.indexOf("ConnectionSupervisor.Action.None"),
        )

        assertTrue(recovery.contains("if (recoveryRestart.request()) break"))
        assertFalse(recovery.contains("runtime.reconnect"))
        assertFalse(watchdog.contains("processRecoveryRequested"))
        assertTrue(watchdog.contains("process recovery request was not admitted; retrying next tick"))
    }

    @Test fun mqttFallbackProofIsConnectionScopedAndWatchdogFailureIsTerminal() {
        val service = source("PaneldService.kt")
        val watchdog = service.substring(
            service.indexOf("private fun startMqttWatchdog()"),
            service.indexOf("private fun registerNetworkCallback()"),
        )

        assertTrue(watchdog.contains("val watchdogObservation = watched.watchdogObservation()"))
        assertTrue(watchdog.contains("val observedTicket = watchdogObservation.recoveryTicket"))
        assertTrue(watchdog.contains("connectionGeneration = progress.connectionGeneration"))
        assertTrue(watchdog.contains("supervisor.rebuildAdmitted()"))
        assertTrue(watchdog.contains("if (rebuild != null) continue"))
        assertTrue(watchdog.contains("if (watchedRuntime == null)"))
        assertFalse(watchdog.contains("?.value?.mqtt ?: mqtt"))

        assertTrue(watchdog.contains("catch (failure: Exception)"))
        assertTrue(watchdog.contains("terminalRecoveryNeeded = true"))
        assertTrue(watchdog.contains("MQTT watchdog failed; requesting bounded process recovery"))
        assertTrue(watchdog.contains("mqttWatchdogAlive = false"))

        val mqtt = source("MqttBridge.kt")
        val observation = mqtt.substring(
            mqtt.indexOf("internal fun watchdogObservation()"),
            mqtt.indexOf("private val familyPreference", mqtt.indexOf("internal fun watchdogObservation()")),
        )
        assertTrue(
            "watchdog must read the hold before projecting progress from one atomic authority",
            observation.indexOf("val holdSelectedFamily") < observation.indexOf("val progress"),
        )
    }

    @Test fun mqttFamilyFallbackIsStagedBeforeOwnerSubmissionAndReconnectCannotFlipTwice() {
        val service = source("PaneldService.kt")
        val watchdog = service.substring(
            service.indexOf("private fun startMqttWatchdog()"),
            service.indexOf("private fun registerNetworkCallback()"),
        )
        val fallback = watchdog.substring(
            watchdog.indexOf("is ConnectionSupervisor.Action.Rebuild"),
            watchdog.indexOf("is ConnectionSupervisor.Action.SkipRebuild"),
        )
        assertTrue(
            fallback.indexOf("watched.stageAlternateFamilyForReconnect(observedTicket)") <
                fallback.indexOf("runtime.reconnect(watchedRuntime)"),
        )
        assertTrue(fallback.contains("target.mqtt.reconnect(recoveryTicket)"))
        assertTrue(watchdog.contains("MqttRecoveryOutcome.NO_LONGER_NEEDED"))
        assertTrue(watchdog.contains("reconcileRejectedRecovery(attempt.ticket)"))
        assertFalse(fallback.contains("target.mqtt.reconnect(flipFamily"))

        val mqtt = source("MqttBridge.kt")
        val stage = mqtt.substring(
            mqtt.indexOf("internal fun stageAlternateFamilyForReconnect("),
            mqtt.indexOf("fun reconnect()"),
        )
        val reconnect = mqtt.substring(
            mqtt.indexOf("fun reconnect()"),
            mqtt.indexOf("private fun onConnected("),
        )

        assertTrue(stage.contains("familyPreference.stageAlternate("))
        assertTrue(stage.contains("recoveryTicketForReconnect(baseline)"))
        assertFalse(reconnect.contains("familyPreference.stageAlternate"))
        assertTrue(reconnect.indexOf("transport.disconnectDetached()") < reconnect.indexOf("startOpen()"))
    }

    @Test fun mqttConnectedCallbackCannotRunAnnouncementWorkOrPinTheLifecycleGate() {
        val mqtt = source("MqttBridge.kt")
        val callback = mqtt.substring(
            mqtt.indexOf("private fun onConnected("),
            mqtt.indexOf("private fun performConnectionEvent("),
        )
        val disconnectedCallback = mqtt.substring(
            mqtt.indexOf("private fun onDisconnected("),
            mqtt.indexOf("private fun requestReAnnounce("),
        )
        val connectionEvent = mqtt.substring(
            mqtt.indexOf("private fun performConnectionEvent("),
            mqtt.indexOf("private fun performConnectAnnouncement("),
        )
        val announcement = mqtt.substring(
            mqtt.indexOf("private fun performConnectAnnouncement("),
            mqtt.indexOf("private fun connectionAnnouncementIsCurrent("),
        )
        val startOpen = mqtt.substring(
            mqtt.indexOf("private fun startOpen()"),
            mqtt.indexOf("private fun scheduleAuthRetry("),
        )
        val stop = mqtt.substring(mqtt.indexOf("fun stop("), mqtt.indexOf("companion object", mqtt.indexOf("fun stop(")))

        assertTrue(callback.contains("connectionEventDispatcher.submit(MqttConnectionEvent.Connected(connection, addressFamily))"))
        listOf(
            "lifecycle.runIfOpen",
            "discoveryCapabilities.snapshot",
            "transport.subscribe",
            "publishDiscovery",
            "pruneStaleDiscovery",
            "restoreAndPublishStates",
        ).forEach { forbidden -> assertFalse("connected callback contains $forbidden", callback.contains(forbidden)) }
        assertTrue(disconnectedCallback.contains("connectionEventDispatcher.submit("))
        assertTrue(disconnectedCallback.contains("mqttAutomaticReconnectAllowed(classified, admission)"))
        assertFalse(disconnectedCallback.contains("lifecycle.runIfOpen"))
        assertTrue(connectionEvent.contains("lifecycle.runIfOpen"))
        assertTrue(connectionEvent.contains("connectAnnouncementDispatcher.submit(announcement)"))
        assertTrue(
            startOpen.indexOf("connectionEventDispatcher.supersede()") <
                startOpen.indexOf("activeConnection = null"),
        )
        assertTrue(announcement.contains("discoveryCapabilities.snapshot()"))
        assertTrue(announcement.contains("publishDiscovery(capabilitySnapshot)"))
        assertTrue(announcement.contains("publish(availabilityTopic, \"online\", retain = true)"))
        assertFalse(announcement.contains("lifecycle.runIfOpen"))
        assertTrue(stop.contains("connectionEventDispatcher.close()"))
        assertTrue(stop.contains("connectAnnouncementDispatcher.close()"))
        assertTrue(stop.contains("connectionEventDispatcher.closeAndJoin(deadline.remainingMs())"))
        assertTrue(stop.contains("connectAnnouncementDispatcher.closeAndJoin(deadline.remainingMs())"))
        assertTrue(stop.contains("connectAnnouncementDrained"))
    }

    @Test fun mqttRetirementClosesAdmissionBeforeBoundedDrainWithoutHoldingAMutationMonitor() {
        val mqtt = source("MqttBridge.kt")
        assertFalse(Modifier.isSynchronized(MqttBridge::class.java.getDeclaredMethod("start").modifiers))
        assertFalse(Modifier.isSynchronized(
            MqttBridge::class.java.getDeclaredMethod("reconnect").modifiers,
        ))
        MqttBridge::class.java.declaredMethods.filter { it.name in setOf("onConnected", "onDisconnected") }
            .forEach { callback ->
            assertFalse("transport callback holds the bridge monitor: ${callback.name}",
                Modifier.isSynchronized(callback.modifiers))
        }
        listOf("stopLock", "lifecycleGeneration", "@Volatile private var stopped", "stateConvergerOwner")
            .forEach { assertFalse("obsolete MQTT lifecycle state remains: $it", mqtt.contains(it)) }

        val stop = mqtt.substring(mqtt.indexOf("fun stop("), mqtt.indexOf("companion object", mqtt.indexOf("fun stop(")))
        val firstWait = stop.indexOf("lifecycle.awaitDrained(deadline)")
        listOf(
            "lifecycle.closeAdmission()",
            "connectionEventDispatcher.close()",
            "connectAnnouncementDispatcher.close()",
            "stateConverger.close()",
            "commandDispatcher.close()",
            "authScheduler.shutdownNow()",
            "zigbeeWorker.closeAndJoin(0L)",
            "adbReassertWorker.closeAndJoin(0L)",
            "reannounceDispatcher.close()",
        ).forEach { step ->
            assertTrue("MQTT retirement signal missing before its first wait: $step",
                stop.indexOf(step) in 0 until firstWait)
        }
        listOf("ownersDrained", "finalization", "deadline.remainingMs()", "transport.publishThenDisconnect(")
            .forEach { assertTrue("typed/shared-deadline MQTT retirement step missing: $it", stop.contains(it)) }
    }

    @Test fun mqttRecoveryAuthorityIsThePrimaryConnectionStateSource() {
        val mqtt = source("MqttBridge.kt")
        val service = source("PaneldService.kt")
        assertTrue(mqtt.contains("val state: String get() = recoveryAuthority.snapshot().state"))
        assertTrue(mqtt.contains("val lastOkMs: Long get() = recoveryAuthority.snapshot().brokerProgress.lastOkMs"))
        assertFalse(mqtt.contains("@Volatile var state:"))
        assertFalse(mqtt.contains("@Volatile private var brokerProgress"))
        assertFalse(mqtt.contains("@Volatile private var applicationReadyEver"))
        listOf("connecting", "discovering", "config-error", "announcing", "connected", "disabled")
            .forEach { state ->
                assertTrue("missing atomic MQTT state transition: $state",
                    mqtt.contains("publishRecoveryLifecycleState(\"$state\"") ||
                        mqtt.contains("publishRecoveryLifecycleStateWithAddressFamily(\"$state\"") ||
                        (state == "announcing" && mqtt.contains("state = \"announcing\"")))
            }
        val connack = mqtt.substring(
            mqtt.indexOf("internal fun recordMqttConnack("),
            mqtt.indexOf("internal fun reconcileRejectedMqttRecovery("),
        )
        assertTrue(
            connack.indexOf("authority.updateProgress(") <
                connack.indexOf("authority.updateLifecycleWithAddressFamily("),
        )
        val connectedEvent = mqtt.substring(
            mqtt.indexOf("is MqttConnectionEvent.Connected -> {"),
            mqtt.indexOf("is MqttConnectionEvent.Disconnected ->"),
        )
        assertTrue(connectedEvent.contains("recordMqttConnack("))
        assertTrue(
            connectedEvent.indexOf("recordMqttConnack(") <
                connectedEvent.indexOf("stateConverger.markAllDirty()"),
        )
        assertTrue(mqtt.contains("publishRecoveryLifecycleState(authRecovery.snapshot(now).state)"))
        assertTrue(mqtt.contains("else publishRecoveryLifecycleState(event.state)"))
        assertTrue(
            service.contains("bridge.servesMqttConfiguration(") &&
                service.contains("config.mqttAddressFamily,"),
        )
    }

    @Test fun hiveTransportPublishesTupleTransitionsAndCallbackEnqueuesInOneOrder() {
        val hive = source("mqtt/HiveMqTransport.kt")
        val connected = hive.substring(
            hive.indexOf(".addConnectedListener"),
            hive.indexOf(".addDisconnectedListener"),
        )
        val disconnected = hive.substring(
            hive.indexOf(".addDisconnectedListener"),
            hive.indexOf("// ssl:///mqtts://"),
        )

        assertTrue(connected.contains("synchronized(sessionLock)"))
        assertTrue(connected.indexOf("session = Session(") < connected.indexOf("callbacks.onConnected(lease,"))
        assertTrue(disconnected.contains("synchronized(sessionLock)"))
        assertTrue(disconnected.indexOf("session = Session(") < disconnected.indexOf("callbacks.onDisconnected("))
        assertTrue(
            hive.contains("builder.transportConfig()") &&
                hive.contains(".socketConnectTimeout(") &&
                hive.contains("ADDRESS_FAMILY_CONNECT_TIMEOUT_SECONDS = 10L"),
        )
        assertFalse(hive.contains("@Volatile private var client"))
        assertFalse(hive.contains("@Volatile private var connectionLease"))
    }

    @Test fun replacementRegistersBeforeTheOldServiceCanExit() {
        val source = source("ConfigActivity.kt")
        val restart = source.substring(
            source.indexOf("if (restartService) {"),
            source.indexOf("readinessJob = activityScope.launch"),
        )

        assertTrue(restart.indexOf("stopService(") < restart.indexOf("PaneldService.start(this)"))
        assertFalse(restart.contains("delay("))
    }

    @Test fun everyActiveReplacementOwnerStartsBehindTheProcessFence() {
        val source = source("PaneldService.kt")
        val onCreate = source.substring(source.indexOf("override fun onCreate()"), source.indexOf("override fun onStartCommand("))
        val startup = source.substring(source.indexOf("restartLease.awaitPredecessor()"), source.indexOf("\n        Thread({"))
        val activeMarkers = listOf(
            "reconcileHelperInstallStaging()",
            "brightness.applyPreventIdleDim",
            "EntityLearningRuntime.attach(entityLearning)",
            "kiosk.recoverPersistentState(",
            "val touchSoundIntent = resolveTouchSoundIntentOnce()",
            "touchSound.reassert(touchSoundIntent)",
            "bootChime.applyPersisted()",
            "server.requestTameReconcile()",
            "server.prewarm()",
            "sensors.prepare()",
        )

        activeMarkers.forEach { marker ->
            assertFalse("$marker started in onCreate", onCreate.contains(marker))
            assertTrue("$marker missing behind fence", startup.contains(marker))
        }
        assertTrue(source.contains("restartLease.completeTeardown()"))
    }

    @Test fun teardownClosesExternalMutationAdmissionBeforeAnyBoundedWaitCanExpire() {
        val source = source("PaneldService.kt")
        val destroy = source.substring(source.indexOf("override fun onDestroy()"), source.indexOf("if (!stopped)"))
        val admissions = source.substring(source.indexOf("private fun closeServiceAdmissions()"), source.indexOf("override fun onDestroy()"))

        assertTrue(destroy.contains("closeServiceAdmissions()"))
        assertTrue(destroy.indexOf("closeServiceAdmissions()") < destroy.indexOf("ensureScreenExitRecovery()"))
        assertTrue(admissions.contains("beginAudioTeardown(audio::closeAdmission, audio::cancelCurrent)"))
        listOf("kiosk.closeAdmission()", "navbar.closeAdmission()", "screen.closeAdmission()").forEach { close ->
            assertTrue("$close missing from synchronous teardown admission", admissions.contains(close))
        }
    }

    @Test fun helperStagingReconciliationUsesTheSupervisedScopedPeriodicOwner() {
        val source = source("PaneldService.kt")
        val reconcile = source.substring(
            source.indexOf("private fun reconcileHelperInstallStaging()"),
            source.indexOf("override fun onBind", source.indexOf("private fun reconcileHelperInstallStaging()")),
        )

        assertTrue(reconcile.contains("scope.periodic("))
        assertTrue(reconcile.contains("intervalMs = INSTALL_RECONCILE_MS"))
        assertTrue(reconcile.contains("name = \"helper-install-staging\""))
        assertTrue(reconcile.contains("helper install staging reconciliation failed"))
        assertTrue(reconcile.contains("helper install staging: removed="))
        assertFalse(reconcile.contains("while (isActive)"))
    }

    @Test fun failedKioskRecoveryCannotBlockControlPlaneStartup() {
        val source = source("PaneldService.kt")
        val startupStart = source.indexOf("restartLease.awaitPredecessor()")
        val startup = source.substring(
            startupStart,
            source.indexOf("server.start()", startupStart) + "server.start()".length,
        )

        assertTrue(startup.contains("val kioskRecoveredAtStartup = kiosk.recoverPersistentState(config.kioskLock)"))
        assertFalse(startup.contains("while ("))
        assertTrue(startup.contains("server.start()"))
        assertTrue(source.contains("if (config.kioskLock || !kioskRecoveredAtStartup) scheduleKioskReassert()"))
        assertTrue(source.contains("if (config.kioskLock && !kiosk.isPersistentPolicyEligible())"))
    }

    @Test fun consolidatedManagementPrewarmRunsAfterCriticalStartup() {
        val source = source("PaneldService.kt")
        val startup = source.substring(
            source.indexOf("restartLease.awaitPredecessor()"),
            source.indexOf("\n        Thread({"),
        )
        val ordered = listOf(
            "BundledHelperInstaller.ensureCurrent",
            "server.start()",
            "adb.reassert()",
            "profileRegistry.markResolvedStartupHealthy()",
            "server.prewarm()",
        ).map(startup::indexOf)

        assertTrue("post-critical observation step missing: $ordered", ordered.all { it >= 0 })
        assertEquals(ordered.sorted(), ordered)
        val healthy = startup.indexOf("profileRegistry.markResolvedStartupHealthy()")
        val postCriticalLaunch = startup.indexOf("scope.launch(Dispatchers.IO)", healthy)
        assertTrue(postCriticalLaunch > healthy)
        val postCritical = startup.substring(postCriticalLaunch)
        assertTrue(postCritical.contains("server.prewarm()"))
        assertFalse(postCritical.contains("CompanionDb.serverUrl"))
    }

    @Test fun buttonLedWarmUpRunsInItsOwnCoroutineAfterCriticalStartup() {
        val source = source("PaneldService.kt")
        val onCreate = source.substring(source.indexOf("override fun onCreate()"), source.indexOf("override fun onStartCommand("))
        val startup = source.substring(
            source.indexOf("restartLease.awaitPredecessor()"),
            source.indexOf("\n        Thread({"),
        )
        val healthy = startup.indexOf("profileRegistry.markResolvedStartupHealthy()")
        val call = startup.indexOf("relay.warmUp()")

        assertFalse(onCreate.contains("relay.warmUp()"))
        assertTrue("button-LED warm-up missing after the healthy-startup proof", healthy in 0 until call)
        // Its own launch, so it neither waits for nor delays the management prewarm.
        val launch = startup.lastIndexOf("scope.launch(Dispatchers.IO) {", call)
        assertTrue(launch > startup.indexOf("server.prewarm()"))
        assertEquals("", startup.substring(launch + "scope.launch(Dispatchers.IO) {".length, call).trim())
    }

    @Test fun externalStateIsRestoredBeforeTheFreshProcessBoundary() {
        val source = source("PaneldService.kt")
        val destroy = source.substring(source.indexOf("override fun onDestroy()"), source.indexOf("if (!stopped)"))
        assertTrue(destroy.indexOf("ensureScreenExitRecovery()") < destroy.indexOf("stopMqttWatchdog"))
        assertTrue(destroy.indexOf("screenExitRecovery.get") < destroy.indexOf("runtime.shutdown"))

        val exit = source.substring(source.indexOf("private fun finishAfterExternalStateIsSafe("), source.indexOf("private fun scheduleKioskReassert()"))
        val screenRecovery = source.substring(source.indexOf("private fun ensureScreenExitRecovery("), source.indexOf("private fun requestSafeProcessBoundary("))
        assertTrue(exit.contains("kiosk.apply(false)"))
        assertTrue(exit.contains("kiosk.recoverPersistentState(config.kioskLock)"))
        assertTrue(exit.contains("shouldForceFreshProcessAfterExternalRecovery("))
        assertTrue(exit.contains("navbar.cleanup()"))
        assertTrue(exit.contains("navbar.recoverPersistentState()"))
        assertTrue(exit.contains("proveScreenSafeForBoundary(ensureScreenExitRecovery())"))
        assertTrue(screenRecovery.contains("screen.restoreAndEstablishExitSafety()"))
        assertTrue(screenRecovery.indexOf("screenExitRecoveryOwner.compareAndSet(null, completion)") < screenRecovery.indexOf("Thread {"))
        assertTrue(exit.contains("CdpRelay.stopAndVerifyForProcessExit"))
        assertTrue(exit.indexOf("runServiceBoundary(") < exit.indexOf("cancelKioskReassert()"))
        assertFalse(source.contains("externalStateRecoveryOwned"))
        val runner = source("ServiceBoundaryRunner.kt")
        assertTrue(runner.indexOf("recordCompletionAndClaimRecovery(completed)") < runner.indexOf("prepare()"))
        assertTrue(exit.indexOf("proveScreenSafeForBoundary") < exit.indexOf("exitProcess(0)"))
        assertTrue(exit.indexOf("CdpRelay.stopAndVerifyForProcessExit") < exit.indexOf("exitProcess(0)"))
    }

    @Test fun ordinaryDestroyDoesNotRequestAProcessExitAndFinalProofOwnsRelease() {
        val source = source("PaneldService.kt")
        val server = source("http/PaneldServer.kt")
        val destroy = source.substring(source.indexOf("override fun onDestroy()"), source.indexOf("private fun finishTeardownAsync("))
        val finalizer = source.substring(source.indexOf("private fun finishTeardownAsync("), source.indexOf("private fun runFinalizerStep("))

        assertFalse(destroy.contains("externalProcessBoundaryRequested.set(true)"))
        assertTrue(server.contains("scope.embeddedServer(CIO"))
        val httpStopStart = destroy.indexOf("httpOwnersStopped.set(")
        assertTrue(httpStopStart >= 0)
        val httpStopEnd = destroy.indexOf("// Kiosk writes", httpStopStart)
        assertTrue(httpStopEnd > httpStopStart)
        val httpStop = destroy.substring(httpStopStart, httpStopEnd)
        assertTrue(httpStop.contains("runCatching { server.stop() }"))
        assertTrue(httpStop.contains(".getOrDefault(false)"))
        assertFalse(httpStop.contains(".isSuccess"))
        val httpFailure = finalizer.indexOf("if (!httpOwnersStopped.get())")
        val releaseProof = finalizer.indexOf("finishTeardownAfterExternalStateIsSafe(completed = true")
        assertTrue(httpFailure >= 0)
        assertTrue(releaseProof >= 0)
        assertTrue(httpFailure < releaseProof)
        val scopeFailure = finalizer.indexOf("if (!scopeDrained)")
        assertTrue(scopeFailure >= 0)
        assertTrue(scopeFailure < releaseProof)
        val httpFailureEnd = finalizer.indexOf("if (::entityLearning.isInitialized", httpFailure)
        assertTrue(httpFailureEnd > httpFailure)
        assertTrue(finalizer.substring(httpFailure, httpFailureEnd).contains("completed = false"))
        assertTrue(finalizer.contains("finishTeardownAfterExternalStateIsSafe(completed = true"))
        assertTrue(source.contains("disposition == ServiceTeardownDisposition.EXIT"))
        assertTrue(source.contains("restartLease.completeTeardown()"))
    }

    @Test fun finalMqttPersistenceAndSharedDeadlineAreRequiredBeforeRelease() {
        val source = source("PaneldService.kt")
        val destroy = source.substring(source.indexOf("override fun onDestroy()"), source.indexOf("private fun finishTeardownAsync("))
        val finalizer = source.substring(source.indexOf("private fun finishTeardownAsync("), source.indexOf("private fun runFinalizerStep("))

        assertTrue(destroy.contains("val asyncTeardownDeadline = MonotonicDeadline(ASYNC_TEARDOWN_BUDGET_MS)"))
        assertTrue(destroy.contains("mqttFinalization.set(mqttRetirement.finalization)"))
        assertTrue(destroy.contains("audioDrained.set(audioStopped)"))
        assertTrue(destroy.contains("val ownerCleanup = ServiceOwnerCleanupTracker()"))
        assertTrue(destroy.contains("ownerCleanup.run(close)"))
        assertTrue(destroy.contains("ownerCleanup.record(result)"))
        assertTrue(finalizer.contains("if (!audioDrained.get())"))
        assertTrue(finalizer.contains("if (!ownerCleanup.isComplete())"))
        assertTrue(finalizer.contains("runtime.hasFailedShutdown()"))
        assertTrue(finalizer.contains("awaitFinalizerFuture(finalizerDeadline, mqttClose)"))
        assertTrue(finalizer.contains("awaitFinalizerFuture(finalizerDeadline, sensorClose)"))
        assertTrue(finalizer.indexOf("entityLearning.close()") < finalizer.lastIndexOf("AppState.flush(this, stateFlushMs)"))
        assertTrue(finalizer.indexOf("AppState.flush(this, stateFlushMs)") < finalizer.indexOf("completed = true"))
        assertFalse(finalizer.contains("MonotonicDeadline(ASYNC_TEARDOWN_BUDGET_MS)"))
    }

    @Test fun networkReplacementUsesOneDeadlineAndProvesRetirementBeforeBuild() {
        val source = source("PaneldService.kt")
        val reconfigure = source.substring(
            source.indexOf("private fun performNetworkReconfigure("),
            source.indexOf("private fun currentNetworkIdentity()"),
        )
        assertEquals(1, Regex("budgetMs = NETWORK_RECONFIGURE_BUDGET_MS")
            .findAll(source).count())
        assertTrue(source.contains("LatestOperationTimeoutPolicy("))
        assertTrue(source.contains("schedule = { task, delayMs -> mainHandler.postDelayed(task, delayMs) }"))
        assertTrue(source.contains("onTimeout = ::onNetworkReconfigureTimeout"))
        assertTrue(reconfigure.contains("val executionDeadline = mutation.deadline"))
        assertFalse(reconfigure.contains("runWithExecutionWatchdog("))
        assertTrue(reconfigure.contains("previous.mdns.retire(executionDeadline)"))
        assertTrue(reconfigure.contains("deadline = executionDeadline"))
        assertTrue(reconfigure.contains("ownersDrained.awaitTrue(executionDeadline)"))
        assertTrue(reconfigure.contains("finalization.awaitSuccessful(executionDeadline)"))
        assertTrue(reconfigure.contains("mdnsRetirement.awaitTrue(executionDeadline)"))
        assertTrue(reconfigure.contains("val completed = mutation.replace("))
        assertTrue(reconfigure.indexOf("check(mqttOwnersDrained") < reconfigure.indexOf("build = { previous ->"))
        assertFalse(reconfigure.contains("runtime.reconfigure("))
        assertFalse(source.contains("NETWORK_OWNER_STOP_TIMEOUT_MS"))
        assertFalse(source.contains("runBoundedOwnerAction"))
    }

    @Test fun networkConfigurationPublishesOneImmutableAppliedSnapshot() {
        val source = source("PaneldService.kt")
        val create = source.substring(source.indexOf("override fun onCreate()"), source.indexOf("private fun buildMqtt("))
        val snapshot = source.substring(
            source.indexOf("private fun currentNetworkConfigurationSnapshot()"),
            source.indexOf("private fun currentMqttProjection()"),
        )
        val refresh = source.substring(
            source.indexOf("private fun refreshLiveConfiguration("),
            source.indexOf("private data class ManagementControllerObservation"),
        )
        assertTrue(source.contains("private lateinit var appliedNetworkConfiguration: NetworkConfigurationSnapshot"))
        assertTrue(source.contains("val desired = currentNetworkConfigurationSnapshot()"))
        assertTrue(create.indexOf("config.attachProfile(profile)") < create.indexOf("appliedNetworkConfiguration ="))
        assertTrue(create.indexOf("appliedNetworkConfiguration =") < create.indexOf("runtime = ServiceRuntimeOwner("))
        assertTrue(snapshot.contains("config.synchronizedTransaction"))
        assertTrue(refresh.contains("appliedNetworkConfiguration = desired"))
        assertFalse(refresh.contains("currentMqttProjection()"))
        assertFalse(refresh.contains("currentHaLinkIdentity()"))
        assertFalse(source.contains("activeNetworkIdentity"))
        assertFalse(source.contains("activeMqttProjection"))
        assertFalse(source.contains("activeHaLinkIdentity"))
    }

    @Test fun runtimeLaneOwnsLatestNetworkAdmissionAndItsTerminalProof() {
        val source = source("PaneldService.kt")
        val destroy = source.substring(source.indexOf("override fun onDestroy()"), source.indexOf("if (!stopped)"))
        assertTrue(source.contains("when (runtime.requestLatest())"))
        assertTrue(source.contains("runtime.pendingLatestCount()"))
        assertFalse(source.contains("networkReconfigureWorker"))
        assertFalse(source.contains("networkWorkerDrained"))
        assertTrue(destroy.indexOf("runtime.closeAdmission()") < destroy.indexOf("runtime.shutdown("))
        assertTrue(destroy.contains("FeatureCosts.registry.setBacklog(FeatureCostOperation.NETWORK_RECONFIGURE, 0)"))
        val timeout = source.substring(
            source.indexOf("private fun onNetworkReconfigureTimeout()"),
            source.indexOf("private fun currentNetworkIdentity()"),
        )
        assertTrue(timeout.contains("runtime.pendingLatestCount()"))
        assertTrue(timeout.contains("requestNetworkRecovery()"))
    }

    @Test fun mdnsRetirementFencesEveryDirectJmDnsBrowseBeforeClose() {
        val mdns = source("MdnsAdvertiser.kt")
        val retire = mdns.substring(mdns.indexOf("internal fun retire("), mdns.indexOf("private fun stopResources("))
        assertTrue(retire.indexOf("ownerGate.closeAdmission()") < retire.indexOf("Thread {"))
        assertTrue(retire.contains("ownerGate.runExclusive { stopResources(deadline) }"))
        listOf("discoverHaIp", "discoverHaBaseUrl", "discoverHaInstanceUuid").forEachIndexed { index, method ->
            val start = mdns.indexOf("fun $method")
            val end = listOf("discoverHaIp", "discoverHaBaseUrl", "discoverHaInstanceUuid", "browsePeers")
                .drop(index + 1)
                .map { mdns.indexOf("fun $it", start + 1) }
                .first { it > start }
            assertTrue("$method bypasses the retirement gate",
                mdns.substring(start, end).contains("ownerGate.runIfOpen<String?>(null)"))
        }
    }

    @Test fun terminalNetworkMutationProofPrecedesDependentHardwareCleanup() {
        val source = source("PaneldService.kt")
        val destroy = source.substring(source.indexOf("override fun onDestroy()"), source.indexOf("if (!stopped)"))
        val mqttProof = destroy.indexOf("val mqttOwnersDrained")
        val proofGate = destroy.indexOf("if (!mqttOwnersDrained || !mdnsStopped || !lightPublisherDrained)")
        assertTrue(mqttProof >= 0)
        assertTrue(proofGate > mqttProof)
        assertTrue(destroy.indexOf("lightMqttPublisher.awaitTermination") in (mqttProof + 1)..<proofGate)
        listOf(
            "kiosk.apply(false)",
            "navbar.cleanup()",
            "audio.close(audioWaitMs)",
            "zigbeeHealth.stop()",
            "EvdevButtonClient.stop()",
            "sensors.stop()",
            "power.releaseAndVerify()",
        ).forEach { dependent ->
            assertTrue("$dependent runs before network mutation proof", destroy.indexOf(dependent) > proofGate)
        }
        assertTrue(destroy.contains("ownerCleanup.record(false)"))
    }

    @Test fun lightMqttPublicationCannotRunOnTheSensorCallbackThread() {
        val source = source("PaneldService.kt")
        val sensorStart = source.substring(source.indexOf("sensors.start("), source.indexOf("EvdevButtonClient.start"))

        assertTrue(sensorStart.contains("submitIlluminanceIfExposed("))
        assertTrue(sensorStart.contains("exposed = config.haExposed(\"illuminance\", true)"))
        assertTrue(sensorStart.contains("submit = lightMqttPublisher::submit"))
        assertTrue(sensorStart.contains("onLuxRaw = autoBright::submitLux"))
        assertFalse(sensorStart.contains("mqtt.publishLight"))
    }

    @Test fun bothTeardownPathsCancelAudioBeforeLaunchingBlockingCleanup() {
        val source = source("PaneldService.kt")
        val destroyStart = source.indexOf("override fun onDestroy()")
        val destroy = source.substring(destroyStart, source.indexOf("val screenExitRecovery", destroyStart))
        val requested = source.substring(source.indexOf("private fun requestSafeProcessBoundary("), source.indexOf("private fun finishTeardownAfterExternalStateIsSafe("))
        val admissions = source.substring(source.indexOf("private fun closeServiceAdmissions()"), destroyStart)

        assertTrue(admissions.contains("beginAudioTeardown(audio::closeAdmission, audio::cancelCurrent)"))
        assertTrue(destroy.contains("closeServiceAdmissions()"))
        assertTrue(requested.contains("closeServiceAdmissions()"))
        assertTrue(requested.contains("restartAfterInternalBoundary.set(true)"))
        assertTrue(requested.indexOf("closeServiceAdmissions()") < requested.indexOf("mainHandler.post { stopSelf() }"))
        assertFalse(requested.contains("Thread {"))
        assertFalse(requested.contains("server.stop()"))
    }

    @Test fun everyPaneldProcessBoundaryUsesTheExternalStateGate() {
        val source = source("PaneldService.kt")
        assertEquals(1, Regex("exitProcess\\(0\\)").findAll(source).count())
        assertTrue(source.contains("requestSafeProcessBoundary(\"activating staged profile\")"))
        assertTrue(source.contains("requestSafeProcessBoundary(\"bounded runtime recovery\")"))
        assertTrue(source.contains("requestSafeProcessBoundary(\"binding the \$verb WebView provider\")"))
        val onDestroy = source.substring(source.indexOf("override fun onDestroy()"), source.indexOf("private fun finishTeardownAsync("))
        val redirect = onDestroy.substring(0, onDestroy.indexOf("val teardownDeadline"))
        val destroy = onDestroy.substring(onDestroy.indexOf("val teardownDeadline"))
        val requested = source.substring(source.indexOf("private fun requestSafeProcessBoundary("), source.indexOf("private fun finishTeardownAfterExternalStateIsSafe("))
        val finalizer = source.substring(source.indexOf("private fun finishTeardownAsync("), source.indexOf("private fun runFinalizerStep("))
        assertTrue(redirect.contains("if (guardDbRedirect)"))
        val redirectSuper = redirect.indexOf("super.onDestroy()")
        val redirectReturn = redirect.indexOf("return")
        assertTrue(redirectSuper >= 0)
        assertTrue(redirectReturn >= 0)
        assertTrue(redirectSuper < redirectReturn)
        assertFalse(redirect.contains("exitProcess"))
        assertTrue(requested.contains("mainHandler.post { stopSelf() }"))
        assertTrue(requested.contains("restartAfterInternalBoundary.set(true)"))
        assertTrue(destroy.contains("if (restartAfterInternalBoundary.get())"))
        assertTrue(destroy.indexOf("finishTeardownAsync(") < destroy.indexOf("if (restartAfterInternalBoundary.get())"))
        // lastIndexOf: a generation that stood down inside a committed boundary returns through its
        // own super.onDestroy() long before this one, and it never re-arms anything.
        assertTrue(destroy.indexOf("if (restartAfterInternalBoundary.get())") < destroy.lastIndexOf("super.onDestroy()"))
        assertTrue(finalizer.contains("AppState.freezeForServiceShutdown(this)"))
        assertTrue(finalizer.contains("AppState.proveCleanServiceShutdown(this, proofMs)"))

        val activation = source.substring(source.indexOf("private suspend fun activateWebView"), source.indexOf("private suspend fun autoUpdateWebView"))
        val builtin = activation.substring(activation.indexOf("if (config.dashboardPackage"), activation.indexOf("system.reloadDashboard"))
        assertTrue(builtin.indexOf("requestSafeProcessBoundary") < builtin.indexOf("return"))
    }

    /**
     * Issue #107: activating an imported profile put the default profile back. stopSelf() clears
     * START_STICKY, so an app-internal boundary re-arms its own start request at the end of onDestroy,
     * and Android serves that request inside the still-live process. That generation used to run a full
     * onCreate — claiming the staged activation, opening the database and taking the hardware owners
     * alongside the outgoing runtime — and then died at exitProcess before it could prove any of it
     * healthy, leaving the fresh process an orphaned APPLYING to roll back. The ordering that keeps it
     * harmless lives in PaneldService's own lifecycle, which no unit test can construct, so it is pinned
     * here alongside the other service-lifecycle contracts.
     */
    @Test fun aCommittedProcessBoundaryStandsDownEveryLaterServiceGeneration() {
        val create = source("PaneldService.kt").let {
            it.substring(it.indexOf("override fun onCreate()"), it.indexOf("override fun onStartCommand("))
        }
        val promotion = create.indexOf("startForegroundCompat(nativeString(R.string.starting), silent = true)")
        val admission = create.indexOf(
            "PROCESS_BOUNDARY_COMMITMENT.admitServiceGeneration() == ServiceGenerationAdmission.STAND_DOWN",
        )
        val standDown = create.indexOf("standDownForCommittedProcessBoundary()")

        assertTrue("a stood-down generation still owes Android its foreground promotion", promotion >= 0)
        assertTrue("onCreate must decide generation admission", admission >= 0)
        assertTrue(promotion < admission)
        assertTrue(admission < standDown)
        listOf(
            "SERVICE_RESTART_BARRIER.enter()",
            "config = Config(this)",
            "config.migrateLiveStore()",
            "profileRegistry = RuntimeProfileRegistry(this)",
            "profileRegistry.resolveForStartup()",
        ).forEach { owner ->
            val at = create.indexOf(owner)
            assertTrue("$owner is missing from onCreate", at >= 0)
            assertTrue("$owner must not run before the generation-admission check", admission < at)
        }
        assertTrue(
            "the stand-down must return before any owner is constructed",
            create.substring(standDown, create.indexOf("SERVICE_RESTART_BARRIER.enter()")).contains("return"),
        )
    }

    @Test fun onlyTheAcceptedExplicitBoundaryCommitsThisProcessToExiting() {
        val source = source("PaneldService.kt")
        val requested = source.substring(
            source.indexOf("private fun requestSafeProcessBoundary("),
            source.indexOf("private fun finishTeardownAfterExternalStateIsSafe("),
        )
        val accepted = requested.indexOf("if (!teardownBoundary.requestExplicitBoundary()) return")
        val commit = requested.indexOf("PROCESS_BOUNDARY_COMMITMENT.commit()")

        assertEquals(1, Regex("PROCESS_BOUNDARY_COMMITMENT\\.commit\\(\\)").findAll(source).count())
        assertTrue(accepted >= 0)
        assertTrue(commit >= 0)
        // An ordinary clean stop RELEASEs a same-process successor and never exits. Committing there
        // would park that successor forever with nothing left alive to bring the panel back.
        assertTrue("only an accepted explicit boundary may commit the process", accepted < commit)
        assertTrue(commit < requested.indexOf("restartAfterInternalBoundary.set(true)"))
        val destroy = source.substring(
            source.indexOf("override fun onDestroy()"),
            source.indexOf("private fun finishTeardownAsync("),
        )
        assertFalse(destroy.contains("PROCESS_BOUNDARY_COMMITMENT.commit()"))
    }

    @Test fun aStoodDownGenerationKeepsItsStickyRecordAndTearsDownNothing() {
        val source = source("PaneldService.kt")
        val start = source.substring(
            source.indexOf("override fun onStartCommand("),
            source.indexOf("private fun startMqttWatchdog()"),
        )
        val standDownGuard = start.indexOf("if (standingDown) return START_STICKY")

        assertTrue(standDownGuard >= 0)
        assertTrue(standDownGuard < start.indexOf("if (started) return START_STICKY"))

        val standDown = source.substring(
            source.indexOf("private fun standDownForCommittedProcessBoundary()"),
            source.indexOf("private fun closeServiceAdmissions()"),
        )
        // The live started-service record is the whole point: it is what Android recreates in the fresh
        // process. Config was never constructed by this generation, so the notice cannot read it.
        assertFalse(standDown.contains("stopSelf()"))
        assertFalse(standDown.contains("config."))

        val destroy = source.substring(
            source.indexOf("override fun onDestroy()"),
            source.indexOf("private fun finishTeardownAsync("),
        )
        val guard = destroy.indexOf("if (standingDown) {")
        val markStopping = destroy.indexOf("teardownBoundary.markStopping()")
        assertTrue(guard >= 0)
        assertTrue("a generation that owns nothing must not run another generation's teardown", guard < markStopping)
        assertTrue(destroy.substring(guard, markStopping).contains("return"))
    }

    @Test fun relayTerminationCommandCannotConsumeAnUnboundedProcessExit() {
        val relay = source("control/CdpRelay.kt")
        val stop = relay.substring(relay.indexOf("private fun stopLocked()"), relay.indexOf("private fun probeExposure()"))

        assertTrue(stop.contains("runOutputIsolatedBounded"))
        assertTrue(stop.contains("timeoutMs = RELAY_STOP_TIMEOUT_MS"))
        assertFalse(stop.contains("Su.run("))
    }

    @Test fun presenceFeedNetworkProbeAndAutoSleepControllerShareTheElapsedRealtimeClock() {
        val service = source("PaneldService.kt")
        val owner = service.substring(
            service.indexOf("val haSocketClock: () -> Long"),
            service.indexOf("haAmbientLux = HaAmbientLuxSubscriber("),
        )
        val controller = source("control/AutoSleepController.kt")

        assertTrue(owner.contains("val haSocketClock: () -> Long = { android.os.SystemClock.elapsedRealtime() }"))
        val streamConstruction = owner
            .substringAfter("haExactEntityStream = HaExactEntityStreamOwner(", missingDelimiterValue = "")
            .substringBefore("\n        )", missingDelimiterValue = "")
        assertEquals(2, Regex("monotonicMillis = haSocketClock").findAll(streamConstruction).count())
        assertTrue(controller.contains("elapsedRealtime: () -> Long = SystemClock::elapsedRealtime"))
    }

    /**
     * The physical-wake reconciliation publishes INTO the MQTT runtime, so teardown must prove it
     * terminal before that runtime is retired: unregister the receiver first (no new posts), then
     * join its worker (nothing in flight), and only then stop MQTT. Any other order leaves a window
     * where a reconciliation publishes through a retired client. The receiver and worker are
     * lifecycle machinery of PaneldService, which no unit test can construct, so the order is pinned
     * here the same way the other service-lifecycle contracts in this file are.
     */
    @Test fun physicalWakeReconciliationIsTerminalBeforeMqttRetirement() {
        val destroy = source("PaneldService.kt").let { it.substring(it.indexOf("override fun onDestroy()")) }
        val unregister = destroy.indexOf("closeOwner(\"screen-on reconciliation\")")
        val join = destroy.indexOf("screenWakeWorker.closeAndJoin(")
        val retire = destroy.indexOf("activeRuntime.mqtt.stop(")
        assertTrue("teardown must unregister the screen-on receiver", unregister >= 0)
        assertTrue("teardown must join the screen reconcile worker", join >= 0)
        assertTrue("teardown must retire the MQTT runtime", retire >= 0)
        assertTrue("no new reconciliation may be posted while the worker drains", unregister < join)
        assertTrue(
            "a reconciliation in flight must not publish through a retired MQTT runtime",
            join < retire,
        )
    }

    private fun source(relative: String): String = locate("src/main/kotlin/io/github/maxlyth/hapaneld/$relative").readText()

    private fun locate(relative: String): File = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        .firstOrNull(File::isFile) ?: error("missing test input $relative")
}
