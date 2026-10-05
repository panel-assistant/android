package io.panelassistant.android.mqtt

import io.panelassistant.android.util.BrokerEndpoint
import java.util.Locale

/**
 * One broker-scoped address-family choice, retained across the process boundary used for MQTT recovery.
 * The store holds one tuple rather than one key per broker, so changing broker resets the fallback and
 * cannot grow durable state. Credentials are deliberately absent from the identity.
 */
internal class MqttFamilyPreference(
    private val load: (brokerIdentity: String) -> Boolean?,
    private val persist: (brokerIdentity: String, preferIpv4: Boolean) -> Boolean,
    private val clear: () -> Boolean,
    private val onClearFailure: () -> Unit = {},
) {
    data class StageResult(
        val preferIpv4: Boolean,
        val durable: Boolean,
        /** False when a rejected owner admission retries the same baseline connection. */
        val changed: Boolean,
    )

    private data class StagedAlternate(
        val baselineConnectAttempt: Long,
        val previousPreferIpv4: Boolean,
        val result: StageResult,
    )

    private data class PendingRouteConfirmation(
        val connectAttempt: Long,
        val preferIpv4: Boolean,
    )

    private var brokerIdentity: String? = null
    private var stagedAlternate: StagedAlternate? = null
    private var pendingRouteConfirmation: PendingRouteConfirmation? = null

    @Volatile
    var preferIpv4: Boolean = false
        private set

    /** True only when this process restored a last-known route and has not yet observed broker progress.
     * The watchdog gives that route one full progress window before trying the alternate family. */
    @Volatile
    var awaitingProgress: Boolean = false
        private set

    @Synchronized
    fun select(brokerIdentity: String): Boolean {
        if (this.brokerIdentity == brokerIdentity) return preferIpv4
        this.brokerIdentity = brokerIdentity
        stagedAlternate = null
        pendingRouteConfirmation = null
        val retained = load(brokerIdentity)
        preferIpv4 = retained ?: false
        awaitingProgress = retained != null
        // A learned route belongs to one broker only. Selecting any other broker removes the old tuple,
        // so returning later starts with the ordinary IPv6-first policy rather than reviving stale state.
        if (retained == null && !clear()) onClearFailure()
        return preferIpv4
    }

    /** Select for a newly built client and forget any staged record that this newer attempt consumed. */
    @Synchronized
    fun selectForConnect(brokerIdentity: String, connectAttempt: Long): Boolean {
        val selected = select(brokerIdentity)
        stagedAlternate?.takeIf { it.baselineConnectAttempt != connectAttempt }?.let { staged ->
            // A durable staged choice is already retained. A failed write may still be consumed by an
            // unrelated fresh start; remember it until exact readiness can prove and persist that route.
            pendingRouteConfirmation = if (staged.result.durable) null else {
                PendingRouteConfirmation(connectAttempt, selected)
            }
            stagedAlternate = null
        }
        return selected
    }

    @Synchronized
    fun stageAlternate(brokerIdentity: String, baselineConnectAttempt: Long): StageResult {
        select(brokerIdentity)
        stagedAlternate?.takeIf {
            it.baselineConnectAttempt == baselineConnectAttempt
        }?.let { staged ->
            // A rejected owner admission must never oscillate the family. If the first durable write
            // failed, retry that exact choice until the process-boundary guarantee is true.
            val retried = if (staged.result.durable) {
                staged.result.copy(changed = false)
            } else {
                staged.result.copy(
                    durable = persist(brokerIdentity, staged.result.preferIpv4),
                    changed = false,
                )
            }
            stagedAlternate = staged.copy(result = retried)
            return retried
        }
        val previous = preferIpv4
        preferIpv4 = !previous
        awaitingProgress = false
        val result = StageResult(
            preferIpv4 = preferIpv4,
            durable = persist(brokerIdentity, preferIpv4),
            changed = true,
        )
        stagedAlternate = StagedAlternate(baselineConnectAttempt, previous, result)
        return result
    }

    /** Undo an alternate that queued recovery never consumed because the current connection recovered. */
    @Synchronized
    fun cancelStaged(brokerIdentity: String, baselineConnectAttempt: Long): Boolean {
        if (this.brokerIdentity != brokerIdentity) return true
        val staged = stagedAlternate?.takeIf { it.baselineConnectAttempt == baselineConnectAttempt }
            ?: return true
        if (!persist(brokerIdentity, staged.previousPreferIpv4)) return false
        preferIpv4 = staged.previousPreferIpv4
        awaitingProgress = false
        stagedAlternate = null
        pendingRouteConfirmation = null
        return true
    }

    /** Exact application readiness confirms the route this fresh-client attempt actually selected. */
    @Synchronized
    fun confirmConnectedRoute(
        brokerIdentity: String,
        connectAttempt: Long,
        selectedPreferIpv4: Boolean,
    ): Boolean {
        select(brokerIdentity)
        val repairsUnconsumedStage = stagedAlternate?.baselineConnectAttempt == connectAttempt
        val confirmsFailedWrite = pendingRouteConfirmation?.let {
            it.connectAttempt == connectAttempt && it.preferIpv4 == selectedPreferIpv4
        } == true
        val confirmsInPlaceFailover = preferIpv4 != selectedPreferIpv4
        if (!repairsUnconsumedStage && !confirmsFailedWrite && !confirmsInPlaceFailover) return true
        if (!persist(brokerIdentity, selectedPreferIpv4)) return false
        preferIpv4 = selectedPreferIpv4
        awaitingProgress = false
        stagedAlternate?.takeIf { it.baselineConnectAttempt == connectAttempt }
            ?.let { stagedAlternate = null }
        pendingRouteConfirmation = null
        return true
    }

    fun markBrokerProgress() {
        if (!awaitingProgress) return
        synchronized(this) { awaitingProgress = false }
    }
}

/** Stable route identity for family recovery; equivalent URL schemes share one choice. */
internal fun mqttFamilyBrokerIdentity(rawBroker: String): String? {
    val endpoint = BrokerEndpoint.endpoint(rawBroker) ?: return null
    val host = endpoint.host.lowercase(Locale.ROOT).let { if (':' in it) "[$it]" else it }
    return "${if (endpoint.tls) "tls" else "tcp"}://$host:${endpoint.port}"
}
