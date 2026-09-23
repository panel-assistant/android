package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.sensors.DashboardHaApiSessionProvider
import io.github.maxlyth.hapaneld.sensors.HaAmbientTransport
import io.github.maxlyth.hapaneld.sensors.HaApiSession
import io.github.maxlyth.hapaneld.sensors.HaApiSessionProvider
import io.github.maxlyth.hapaneld.sensors.HaAuthenticationException
import io.github.maxlyth.hapaneld.sensors.KtorHaAmbientTransport
import io.github.maxlyth.hapaneld.util.HaTransportEvidence
import io.github.maxlyth.hapaneld.util.HaTransportFault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI

/** The V2-only built-in-renderer floor selected for ha-paneld 0.9.6. */
internal data class HomeAssistantVersion(val year: Int, val month: Int, val patch: Int) : Comparable<HomeAssistantVersion> {
    override fun compareTo(other: HomeAssistantVersion): Int =
        compareValuesBy(this, other, HomeAssistantVersion::year, HomeAssistantVersion::month, HomeAssistantVersion::patch)

    companion object {
        fun parse(raw: String?): HomeAssistantVersion? {
            val match = VERSION.matchEntire(raw?.trim().orEmpty()) ?: return null
            val values = match.groupValues.drop(1).take(3).map { it.toIntOrNull() ?: return null }
            if (values[0] < 2020 || values[1] !in 1..12) return null
            return HomeAssistantVersion(values[0], values[1], values[2])
        }

        private val VERSION = Regex("^(\\d{4})\\.(\\d{1,2})\\.(\\d{1,3})$")
    }
}

internal object DashboardV2Compatibility {
    val MINIMUM_HA_VERSION = HomeAssistantVersion(2026, 4, 2)

    fun eligible(serverVersion: String?, webMessageListenerSupported: Boolean): Boolean =
        webMessageListenerSupported &&
            (HomeAssistantVersion.parse(serverVersion)?.let { it >= MINIMUM_HA_VERSION } == true)
}

/** Per-document evidence for stopping a loaded frontend that never exposes the required V2 bridge. */
internal class V2HandshakeGate(private val missingDocumentLimit: Int = 2) {
    init { require(missingDocumentLimit > 0) }

    private var current: ExternalBusController.Session? = null
    private var observed = false
    private var finished = false
    private var missingDocuments = 0

    @Synchronized fun begin(session: ExternalBusController.Session) {
        current = session
        observed = false
        finished = false
    }

    @Synchronized fun observe(session: ExternalBusController.Session) {
        if (current != session) return
        observed = true
        missingDocuments = 0
    }

    @Synchronized fun finish(session: ExternalBusController.Session) {
        if (current == session) finished = true
    }

    @Synchronized fun onTimeout(session: ExternalBusController.Session): Boolean {
        if (current != session || !finished || observed) return false
        return ++missingDocuments >= missingDocumentLimit
    }

    @Synchronized fun reset() {
        current = null
        observed = false
        finished = false
        missingDocuments = 0
    }
}

internal sealed interface DashboardV2ProbeResult {
    data class Compatible(val version: String) : DashboardV2ProbeResult
    data class UnsupportedHa(val version: String) : DashboardV2ProbeResult
    data class Unverifiable(val version: String?) : DashboardV2ProbeResult
    data object AuthenticationFailed : DashboardV2ProbeResult
    /** [detail] is the raw platform/server text shown ON the panel, which can embed the configured
     *  host; [evidence] is the same failure classified from its exception type and is what leaves the
     *  panel through diagnostics. */
    data class Unavailable(
        val detail: String,
        val evidence: HaTransportEvidence = HaTransportEvidence.UNKNOWN,
    ) : DashboardV2ProbeResult
}

internal sealed interface DashboardV2Admission {
    data class Compatible(val version: String, val live: Boolean) : DashboardV2Admission
    data class Blocked(val result: DashboardV2ProbeResult) : DashboardV2Admission

    companion object {
        fun resolve(result: DashboardV2ProbeResult, cachedVersion: String?): DashboardV2Admission = when (result) {
            is DashboardV2ProbeResult.Compatible -> Compatible(result.version, live = true)
            is DashboardV2ProbeResult.Unavailable -> if (
                DashboardV2Compatibility.eligible(cachedVersion, webMessageListenerSupported = true)
            ) Compatible(requireNotNull(cachedVersion), live = false) else Blocked(result)
            else -> Blocked(result)
        }
    }
}

internal data class DashboardV2CompatibilityOwner(
    val normalizedUrl: String,
    val authOwner: HaAuthOwner,
)

/** Owns the one compatibility attempt whose result may currently admit a renderer. */
internal class DashboardV2AttemptGate {
    data class Ticket(val epoch: Long, val owner: DashboardV2CompatibilityOwner)

    @Volatile private var current: Ticket? = null
    private var nextEpoch = 0L

    @Synchronized fun start(owner: DashboardV2CompatibilityOwner): Ticket =
        Ticket(++nextEpoch, owner).also { current = it }

    fun owns(ticket: Ticket, currentOwner: DashboardV2CompatibilityOwner): Boolean =
        current == ticket && ticket.owner == currentOwner

    @Synchronized fun invalidate() {
        current = null
    }
}

/** One bounded authenticated `/api/config` read; it owns no worker, cache, retry loop, or lifecycle. */
internal class DashboardV2CompatibilityProbe(
    private val auth: HaApiSessionProvider,
    private val transport: HaAmbientTransport,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        config: Config,
        workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
        stillCurrent: () -> Boolean = { true },
    ) : this(
        DashboardHaApiSessionProvider(config, stillCurrent),
        KtorHaAmbientTransport(),
        workerDispatcher,
    )

    suspend fun check(): DashboardV2ProbeResult = withContext(workerDispatcher) {
        try {
            var session = auth.resolve(force = false)
            blockedBy(session)?.let { return@withContext it }
            val response = try {
                transport.config(session.baseUrl, requireNotNull(session.accessToken))
            } catch (_: HaAuthenticationException) {
                session = auth.resolve(force = true)
                blockedBy(session)?.let { return@withContext it }
                transport.config(session.baseUrl, requireNotNull(session.accessToken))
            }
            val rawVersion = (response.opt("version") as? String)?.trim()?.take(MAX_VERSION_CHARS)
            val parsed = HomeAssistantVersion.parse(rawVersion)
                ?: return@withContext DashboardV2ProbeResult.Unverifiable(rawVersion)
            if (parsed >= DashboardV2Compatibility.MINIMUM_HA_VERSION) {
                DashboardV2ProbeResult.Compatible(requireNotNull(rawVersion))
            } else DashboardV2ProbeResult.UnsupportedHa(requireNotNull(rawVersion))
        } catch (_: HaAuthenticationException) {
            DashboardV2ProbeResult.AuthenticationFailed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            unavailable(error.message, HaTransportFault.evidenceOf(error))
        }
    }

    /** A missing token is an authentication verdict only when the server refused it or none is
     *  configured. When minting failed in TRANSPORT (certificate, DNS, timeout, 5xx) the credential
     *  was never judged, so the honest verdict is unavailability naming the fault — which also lets a
     *  previously verified version admit the renderer instead of parking the panel.
     *
     *  A session that was never ATTEMPTED is the same kind of non-verdict and must be ordered ahead of
     *  the terminal default. On a cold boot the probe can run before the configuration store has
     *  loaded, so the URL reads blank and no credential is judged; the panel can then show a credential
     *  screen it cannot clear even though a later retry succeeds with the same credential. */
    private fun blockedBy(session: HaApiSession): DashboardV2ProbeResult? = when {
        session.rejected -> DashboardV2ProbeResult.AuthenticationFailed
        !session.accessToken.isNullOrBlank() -> null
        // A session reporting a transient detail HAS failed in transport, so its evidence can never
        // be `NONE` here however it was constructed — an unclassified failure degrades to UNKNOWN.
        session.transientDetail != null ->
            unavailable(session.transientDetail, session.transientEvidence.orUnclassified())
        // Evidence is NONE rather than UNKNOWN on purpose: nothing was attempted, so there is no
        // transport failure to classify, and claiming an unknown fault would invent one.
        session.notAttempted ->
            unavailable("Home Assistant connection is not ready yet", HaTransportEvidence.NONE)
        else -> DashboardV2ProbeResult.AuthenticationFailed
    }

    private fun unavailable(
        detail: String?,
        evidence: HaTransportEvidence,
    ): DashboardV2ProbeResult.Unavailable =
        DashboardV2ProbeResult.Unavailable(
            detail?.replace(Regex("[\\r\\n\\t]+"), " ")?.trim()?.take(240)
                ?.takeIf(String::isNotBlank) ?: "Home Assistant version is unavailable",
            evidence,
        )

    private companion object {
        const val MAX_VERSION_CHARS = 64
    }
}

/**
 * One active auth resolution plus one conflated successor. Completion is ticketed so work invalidated
 * by a document rotation cannot clear or consume a newer document's request.
 */
internal class BoundedAuthQueue<T>(
    private val sameOwner: (T, T) -> Boolean,
    private val forced: (T) -> Boolean,
) {
    data class Work<T>(val ticket: Long, val request: T)

    private var nextTicket = 0L
    private var active: Work<T>? = null
    private var pending: T? = null

    @Synchronized fun offer(request: T): Work<T>? {
        if (active == null) return newWork(request).also { active = it }
        val queued = pending
        pending = if (queued != null && sameOwner(queued, request) && forced(queued) && !forced(request)) {
            queued
        } else {
            request
        }
        return null
    }

    @Synchronized fun complete(work: Work<T>, isCurrent: (T) -> Boolean): Work<T>? {
        if (active?.ticket != work.ticket) return null
        val next = pending.also { pending = null }
        if (next == null || !isCurrent(next)) {
            active = null
            return null
        }
        return newWork(next).also { active = it }
    }

    @Synchronized fun clear() {
        active = null
        pending = null
    }

    @Synchronized fun retainedCount(): Int = (if (active == null) 0 else 1) + (if (pending == null) 0 else 1)

    private fun newWork(request: T): Work<T> = Work(++nextTicket, request)
}

/** Exact effective-origin comparison used after WebView's allowed-origin rule has already matched. */
internal fun sameDashboardOrigin(sourceOrigin: String?, currentUrl: String?): Boolean {
    fun parse(raw: String?): Triple<String, String, Int>? {
        val uri = runCatching { URI(raw?.trim().orEmpty()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (scheme !in setOf("http", "https")) return null
        val port = when {
            uri.port >= 0 -> uri.port
            scheme == "https" -> 443
            else -> 80
        }
        return Triple(scheme, host, port)
    }
    return parse(sourceOrigin)?.let { it == parse(currentUrl) } == true
}

/** Typed, bounded V2 envelope for HA's reserved `externalAppV2` message listener. */
internal object ExternalAppV2Protocol {
    const val MAX_MESSAGE_CHARS = ExternalBusProtocol.MAX_MESSAGE_CHARS + 1024

    sealed interface Incoming {
        data class GetExternalAuth(val payload: String) : Incoming
        data class RevokeExternalAuth(val payload: String) : Incoming
        data class ExternalBus(val payload: String) : Incoming
        data class Unknown(val type: String?) : Incoming
        data class Malformed(val reason: String) : Incoming
    }

    fun parse(raw: String?): Incoming {
        if (raw == null) return Incoming.Malformed("missing-data")
        if (raw.length > MAX_MESSAGE_CHARS) return Incoming.Malformed("oversized")
        if (!jsonNestingWithinLimit(raw)) return Incoming.Malformed("too-deep")
        val envelope = runCatching { JSONObject(raw) }.getOrNull()
            ?: return Incoming.Malformed("invalid-json")
        val type = envelope.opt("type") as? String
            ?: return Incoming.Malformed("missing-type")
        val payload = envelope.optJSONObject("payload")
        return when (type) {
            "getExternalAuth" -> payload?.let { Incoming.GetExternalAuth(it.toString()) }
                ?: Incoming.Malformed("invalid-auth-payload")
            "revokeExternalAuth" -> payload?.let { Incoming.RevokeExternalAuth(it.toString()) }
                ?: Incoming.Malformed("invalid-revoke-payload")
            "externalBus" -> payload?.let { Incoming.ExternalBus(it.toString()) }
                ?: Incoming.Malformed("invalid-bus-payload")
            else -> Incoming.Unknown(type.take(128))
        }
    }
}

/** Separate V2 namespace for ha-paneld's document-start telemetry; never extends HA's protocol. */
internal object HaPaneldV2Protocol {
    const val OBJECT_NAME = "haPaneldV2"
    const val MAX_MESSAGE_CHARS = EntityBridgePayloadLimits.MAX_MESSAGE_CHARS

    sealed interface Incoming {
        data object EntityFilterSubscriptionModified : Incoming
        data class EntityFilterTrafficMetrics(val payload: String) : Incoming
        data class EntityLearningAccesses(val payload: String) : Incoming
        data class EntityLearningMetrics(val payload: String) : Incoming
        data class Unknown(val type: String?) : Incoming
        data class Malformed(val reason: String) : Incoming
    }

    fun parse(raw: String?): Incoming {
        if (raw == null) return Incoming.Malformed("missing-data")
        if (raw.length > MAX_MESSAGE_CHARS) return Incoming.Malformed("oversized")
        if (!jsonNestingWithinLimit(raw)) return Incoming.Malformed("too-deep")
        val envelope = runCatching { JSONObject(raw) }.getOrNull()
            ?: return Incoming.Malformed("invalid-json")
        val type = envelope.opt("type") as? String
            ?: return Incoming.Malformed("missing-type")
        return when (type) {
            "entityFilterSubscriptionModified" -> Incoming.EntityFilterSubscriptionModified
            "entityFilterTrafficMetrics" -> (envelope.opt("payload") as? String)
                ?.let(Incoming::EntityFilterTrafficMetrics) ?: Incoming.Malformed("invalid-traffic-payload")
            "entityLearningAccesses" -> envelope.optJSONObject("payload")?.toString()
                ?.let(Incoming::EntityLearningAccesses) ?: Incoming.Malformed("invalid-learning-payload")
            "entityLearningMetrics" -> envelope.optJSONObject("payload")?.toString()
                ?.let(Incoming::EntityLearningMetrics) ?: Incoming.Malformed("invalid-learning-payload")
            else -> Incoming.Unknown(type.take(128))
        }
    }
}

private object EntityBridgePayloadLimits {
    // The existing learning bridge permits a 64 KiB batch; leave bounded room for the typed envelope.
    const val MAX_MESSAGE_CHARS = 65 * 1024
}

/** Cheap pre-parse ceiling so hostile nesting never reaches org.json recursion. */
private fun jsonNestingWithinLimit(raw: String, maxDepth: Int = 32): Boolean {
    var depth = 0
    var quoted = false
    var escaped = false
    raw.forEach { char ->
        if (escaped) escaped = false
        else if (quoted && char == '\\') escaped = true
        else if (char == '"') quoted = !quoted
        else if (!quoted && (char == '{' || char == '[')) {
            if (++depth > maxDepth) return false
        } else if (!quoted && (char == '}' || char == ']')) {
            if (--depth < 0) return false
        }
    }
    return !quoted && depth == 0
}
