package io.github.maxlyth.hapaneld

import java.io.File
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MdnsHealthTest {
    private fun health(
        advertising: Boolean = true,
        boundIp: String? = "192.0.2.109",
        lanIp: String? = "192.0.2.109",
    ) = MdnsHealth(advertising, boundIp, lanIp)

    @Test fun healthyAdvertiserWarnsAboutNothing() {
        assertNull(mdnsHealthWarning(health()))
    }

    @Test fun noLanAddressDoesNotAddASecondNetworkWarning() {
        assertNull(mdnsHealthWarning(health(advertising = false, boundIp = null, lanIp = null)))
    }

    @Test fun stoppedAdvertiserWarnsTheOperator() {
        val warning = mdnsHealthWarning(health(advertising = false, boundIp = null))
        assertNotNull(warning)
        assertTrue(warning!!.contains("not running"))
    }

    @Test fun staleBindReportsBothAddresses() {
        val warning = mdnsHealthWarning(health(boundIp = "127.0.0.1", lanIp = "192.0.2.109"))
        assertNotNull(warning)
        assertTrue(warning!!.contains("127.0.0.1"))
        assertTrue(warning.contains("192.0.2.109"))
    }

    @Test fun existingAdvertiserStaysWhenItsLanBindIsCurrent() {
        assertFalse(mdnsRebindRequired("192.0.2.109", "192.0.2.109", browsing = true))
    }

    @Test fun existingAdvertiserRebindsForDhcpAddressChangeOrStoppedBrowse() {
        assertTrue(mdnsRebindRequired("192.0.2.109", "192.0.2.110", browsing = true))
        assertTrue(mdnsRebindRequired("192.0.2.109", "192.0.2.109", browsing = false))
    }

    @Test fun defaultNetworkAddressIgnoresLoopbackAndOtherAddressFamilies() {
        assertTrue(
            defaultNetworkIpv4(
                listOf(
                    InetAddress.getByName("::1"),
                    InetAddress.getByName("127.0.0.1"),
                    InetAddress.getByName("192.0.2.110"),
                ),
            ) == "192.0.2.110",
        )
    }

    @Test fun networkCallbacksFeedTheStartupSafeMdnsReconcilerIndependentlyOfMqttState() {
        val service = File("src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt").readText()
        val available = service.substring(
            service.indexOf("override fun onAvailable"),
            service.indexOf("override fun onLinkPropertiesChanged"),
        )
        val linkChange = service.substring(
            service.indexOf("override fun onLinkPropertiesChanged"),
            service.indexOf("override fun onCapabilitiesChanged"),
        )
        val lost = service.substring(
            service.indexOf("override fun onLost"),
            service.indexOf("runCatching { cm.registerDefaultNetworkCallback"),
        )
        val running = service.substring(
            service.indexOf("ServiceStartupDisposition.RUNNING ->"),
            service.indexOf("ServiceStartupDisposition.PROFILE_ACTIVATION_ROLLBACK ->"),
        )
        val replacementComplete = service.substring(
            service.indexOf("if (!completed) {"),
            service.indexOf("} catch (e: InterruptedException)"),
        )

        assertTrue(available.contains("mdnsNetworkChanged(cm.getLinkProperties(network)?.linkAddresses.orEmpty())"))
        assertTrue(available.indexOf("mdnsNetworkChanged(") < available.indexOf("runtime.observe() ?: return"))
        assertTrue(linkChange.contains("if (network != defaultNetwork) return"))
        assertTrue(linkChange.contains("mdnsNetworkChanged(linkProperties.linkAddresses)"))
        assertTrue(linkChange.indexOf("mdnsNetworkChanged(") < linkChange.indexOf("runtime.observe() ?: return"))
        val feed = service.substring(
            service.indexOf("private fun mdnsNetworkChanged("),
            service.indexOf("private fun registerNetworkCallback()"),
        )
        assertTrue(feed.contains("mdnsRuntimeReconciler.networkChanged("))
        assertTrue(feed.contains("linkAddresses.map { it.address }"))
        assertTrue(feed.contains("it.flags and android.system.OsConstants.IFA_F_TEMPORARY != 0"))
        assertTrue(lost.contains("mdnsRuntimeReconciler.networkLost()"))
        assertTrue(running.contains("mdnsRuntimeReconciler.runtimeRunning()"))
        assertTrue(replacementComplete.contains("mdnsRuntimeReconciler.runtimeRunning()"))
        assertTrue(service.contains("mdnsRuntimeReconciler = MdnsRuntimeReconciler(runtime, ::revalidateMdns)"))
        assertTrue(service.contains("current.value.mdns.start(request.addresses?.primary, request.addresses?.secondary)"))
        assertTrue(service.contains("LatestDispatcher.singleSlot<MdnsRevalidation>"))
        assertFalse(service.contains("it.mdns.start()\n                            it.mqtt.reconnect()"))
    }

    @Test fun statusEndpointIncludesLiveMdnsWarning() {
        val server = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()

        assertTrue(server.contains("private val mdnsWarningProjection: () -> Pair<String?, InstallPresentation?>"))
        assertTrue(server.contains("val mdns = runCatching(mdnsWarningProjection).getOrNull()"))
        assertTrue(server.contains("addWarning(mdns?.first, mdns?.second)"))
        val service = File("src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt").readText()
        assertTrue(service.contains("mdnsWarningProjection = {"))
        assertTrue(service.contains("mdnsHealthWarning(health) to mdnsHealthPresentation(health)"))
        assertTrue(service.contains("\"mDNS\" to mdns.statusPublic()"))
    }

    @Test fun threeConsecutiveMissingSelfQueriesAdmitOneRecovery() {
        val policy = MdnsLivenessPolicy(deadSweeps = 3)

        assertNull(policy.observeSelf(visible = false, nowMs = 1_000L))
        assertNull(policy.observeSelf(visible = false, nowMs = 2_000L))
        assertEquals(0L, policy.observeSelf(visible = false, nowMs = 3_000L)?.delayMs)

        val state = policy.snapshot(3_000L)
        assertEquals(MdnsLivenessState.RECOVERING, state.state)
        assertEquals(1, state.recoveryAttempts)
        assertEquals(0, state.consecutiveMisses)
    }

    @Test fun observingOwnServiceResetsFailuresAndRecoveryBudget() {
        val policy = MdnsLivenessPolicy(deadSweeps = 1)
        assertEquals(0L, policy.observeSelf(visible = false, nowMs = 1_000L)?.delayMs)
        policy.recoveryFinished()

        assertNull(policy.observeSelf(visible = true, nowMs = 2_000L))

        assertEquals(MdnsLivenessSnapshot(), policy.snapshot(2_000L))
    }

    @Test fun recoveryUsesCooldownAndStopsAtItsAttemptCap() {
        val policy = MdnsLivenessPolicy(
            deadSweeps = 1,
            maxAttempts = 3,
            backoffMs = longArrayOf(0L, 100L, 200L),
        )

        assertEquals(0L, policy.observeSelf(visible = false, nowMs = 0L)?.delayMs)
        policy.recoveryFinished()
        assertEquals(100L, policy.observeSelf(visible = false, nowMs = 0L)?.delayMs)
        policy.recoveryFinished()
        assertEquals(200L, policy.observeSelf(visible = false, nowMs = 100L)?.delayMs)
        policy.recoveryFinished()
        assertNull(policy.observeSelf(visible = false, nowMs = 300L))

        val state = policy.snapshot(300L)
        assertEquals(MdnsLivenessState.EXHAUSTED, state.state)
        assertEquals(3, state.recoveryAttempts)
    }

    @Test fun terminalDelegateFailureUsesTheSameBoundedCircuit() {
        val policy = MdnsLivenessPolicy(maxAttempts = 1)

        assertEquals(0L, policy.observeTerminalFailure(10L, "socket recovery failed")?.delayMs)
        policy.recoveryFinished()
        assertNull(policy.observeTerminalFailure(20L, "socket recovery failed again"))

        assertEquals(MdnsLivenessState.EXHAUSTED, policy.snapshot(20L).state)
    }

    @Test fun failureBurstsDoNotConsumeTheRetryBudgetWhileWorkIsPending() {
        val policy = MdnsLivenessPolicy(maxAttempts = 3)

        assertEquals(0L, policy.observeTerminalFailure(10L, "first")?.delayMs)
        assertNull(policy.observeTerminalFailure(11L, "duplicate"))

        assertEquals(1, policy.snapshot(11L).recoveryAttempts)
    }

    @Test fun healthyObservationCancelsAnAlreadyReservedDelayedRecovery() {
        val policy = MdnsLivenessPolicy(deadSweeps = 1, backoffMs = longArrayOf(100L))
        val reservation = checkNotNull(policy.observeSelf(visible = false, nowMs = 0L))
        assertTrue(policy.isCurrent(reservation.token))

        policy.observeSelf(visible = true, nowMs = 50L)

        assertFalse(policy.isCurrent(reservation.token))
        assertEquals(MdnsLivenessState.HEALTHY, policy.snapshot(100L).state)
    }

    @Test fun ordinaryStopCancelsAnAlreadyReservedRecovery() {
        val policy = MdnsLivenessPolicy(deadSweeps = 1, backoffMs = longArrayOf(100L))
        val reservation = checkNotNull(policy.observeSelf(visible = false, nowMs = 0L))

        policy.cancelPending()

        assertFalse(policy.isCurrent(reservation.token))
    }

    @Test fun staleCompletionCannotCancelNewerRecoveryReservation() {
        val policy = MdnsLivenessPolicy(deadSweeps = 1)
        val old = checkNotNull(policy.observeSelf(false, 0))
        policy.onStarted(resetBudget = true)
        val current = checkNotNull(policy.observeSelf(false, 1))

        policy.recoveryFinished(old.token)

        assertTrue(policy.isCurrent(current.token))
    }

    @Test fun staleFailureCannotReserveRetryAgainstNewerTopologyBudget() {
        val policy = MdnsLivenessPolicy(deadSweeps = 1, backoffMs = longArrayOf(0L, 100L))
        val old = checkNotNull(policy.observeSelf(false, 0))
        policy.onStarted(resetBudget = true)
        val current = checkNotNull(policy.observeSelf(false, 1))

        assertNull(policy.recoveryFailed(old.token, 2, "obsolete recreation failed"))

        assertTrue(policy.isCurrent(current.token))
        assertEquals(1, policy.snapshot(2).recoveryAttempts)
    }

    @Test fun currentFailureAtomicallyReservesTheNextBoundedRetry() {
        val policy = MdnsLivenessPolicy(deadSweeps = 1, backoffMs = longArrayOf(0L, 100L))
        val current = checkNotNull(policy.observeSelf(false, 0))

        val retry = checkNotNull(policy.recoveryFailed(current.token, 5, "recreation failed"))

        assertEquals(100L, retry.delayMs)
        assertTrue(policy.isCurrent(retry.token))
        assertEquals(2, policy.snapshot(5).recoveryAttempts)
    }

    @Test fun retryDeadlineAdditionSaturatesInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, saturatingAdd(Long.MAX_VALUE - 5L, 10L))
    }

    @Test fun exhaustedRecoveryHasAnOperatorVisibleWarning() {
        val warning = mdnsHealthWarning(
            health().copy(
                liveness = MdnsLivenessSnapshot(
                    state = MdnsLivenessState.EXHAUSTED,
                    recoveryAttempts = 3,
                    lastReason = "own advertisement missing",
                ),
            ),
        )

        assertNotNull(warning)
        assertTrue(warning!!.contains("automatic recovery stopped after 3 attempts"))
    }

    @Test fun recoveryMechanismUsesSupportedDelegateAndRetirementFence() {
        val advertiser = File("src/main/kotlin/io/github/maxlyth/hapaneld/MdnsAdvertiser.kt").readText()

        assertTrue(advertiser.contains("dns.setDelegate"))
        assertTrue(advertiser.contains("ScheduledThreadPoolExecutor(1)"))
        assertTrue(advertiser.contains("recoveryScheduler.cancel()"))
        assertTrue(advertiser.contains("mdnsRecoveryStillCurrent("))
        assertTrue(advertiser.contains("recoveryScheduler.closeAndJoin(deadline.remainingMs())"))
        assertTrue(advertiser.contains("if (!browsing) return emptyList()"))
        assertTrue(advertiser.contains("generationProbeToken"))
        assertFalse(advertiser.contains("services.any { it.name == advertisedInstanceName }"))
    }
}
