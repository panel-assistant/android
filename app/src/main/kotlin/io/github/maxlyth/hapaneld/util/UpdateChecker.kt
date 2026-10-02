package io.github.maxlyth.hapaneld.util

import android.content.Context
import android.os.SystemClock
import io.github.maxlyth.hapaneld.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Checks GitHub releases for available updates to ha-paneld and the installed HA Companion app. Catalog
 * state is cached, but every entry remains tied to the channel and device safety policy used to resolve it.
 */
object UpdateChecker {

    data class UpdateInfo(
        val label: String,
        val currentVersion: String,
        val latestVersion: String,
        val releaseUrl: String,
        /** Stable locale-neutral identity; [label] remains the exact compatibility and ignore-map key. */
        val component: String,
        /** Exact release tag from the cached resolver when that resolver retains one. Never a URL. */
        val tag: String? = null,
        /** Authoritative source-release classification; never infer stability from version text alone. */
        val prerelease: Boolean = false,
    ) {
        constructor(
            label: String,
            currentVersion: String,
            latestVersion: String,
            releaseUrl: String,
        ) : this(label, currentVersion, latestVersion, releaseUrl, component = "")
    }

    internal data class CompanionPolicy(val channel: String, val maxVersion: String?)

    internal data class RequestedPolicies(val paneldChannel: String, val companion: CompanionPolicy)

    internal sealed interface Resolution {
        data class Resolved(val update: UpdateInfo?) : Resolution
        data object Failed : Resolution
    }

    internal data class CacheReconciliation(
        val available: List<UpdateInfo>,
        val paneldCacheChannel: String?,
        val companionCachePolicy: CompanionPolicy?,
        val complete: Boolean,
    )

    private data class CacheKey(val paneldChannel: String, val companionChannel: String, val companionCap: String?)

    /**
     * The newest catalog release for one component, kept whether or not it is newer than what is
     * installed and whether or not the component is installed at all. [available] deliberately omits
     * up-to-date and absent components, so the MQTT update entities read this instead. Each target
     * records the policy that resolved it; a reader asking under any other channel or cap gets nothing.
     */
    internal data class ResolvedTarget(
        val version: String,
        val tag: String,
        val releaseUrl: String,
        val channel: String,
        val cap: String?,
        val capped: Boolean = false,
        val newestVersion: String? = null,
    )

    @Volatile var available: List<UpdateInfo> = emptyList()
        private set
    @Volatile private var resolvedCompanion: ResolvedTarget? = null

    /** Called after every completed [check]; the service republishes the update entities from it. */
    @Volatile var onChecked: (() -> Unit)? = null
    @Volatile private var lastCheckElapsedMs = -1L
    @Volatile private var cacheKey: CacheKey? = null
    @Volatile private var paneldCacheChannel: String? = null
    @Volatile private var companionCachePolicy: CompanionPolicy? = null
    private val checkMutex = Mutex()

    /** True when a monotonic cache stamp is absent, rolled back, expired, or belongs to another policy. */
    internal fun shouldCheck(nowMs: Long, lastMs: Long, staleMs: Long, samePolicy: Boolean): Boolean =
        !samePolicy || lastMs < 0L || nowMs < lastMs || nowMs - lastMs > staleMs

    /** Check only when the cache is stale or was resolved for different channels/cap. */
    suspend fun checkIfStale(
        context: Context,
        channel: String = "stable",
        staleMs: Long = 3_600_000L,
        companionChannel: String = "stable",
        companionMaxVersion: String?,
    ) {
        val key = CacheKey(channel, companionChannel, companionMaxVersion)
        if (shouldCheck(SystemClock.elapsedRealtime(), lastCheckElapsedMs, staleMs, cacheKey == key)) {
            check(context, channel, companionChannel, companionMaxVersion)
        }
    }

    /** Resolve both components under one serialized cache transaction. A failed component lookup keeps its
     *  last-known result and leaves the cache stale so a later stale check can retry; a successful lookup
     *  authoritatively adds or removes that component's update. */
    suspend fun check(
        context: Context,
        channel: String = "stable",
        companionChannel: String = "stable",
        companionMaxVersion: String?,
    ) = withContext(Dispatchers.IO) {
        checkMutex.withLock {
            val previous = available
            val current = BuildConfig.VERSION_NAME
            // Panel Assistant owns panel-app offers; this local catalogue retains Companion only.
            val paneldResolution = Resolution.Resolved(null)

            val companion = installedCompanion(context)
            // Resolved even when no Companion is installed: absent is an installable state for the
            // update entity. The banner's `available` projection below still ignores an absent app.
            val companionResolved = CompanionInstaller.target(companionChannel, companionMaxVersion)
            val companionResolution = if (companion == null) {
                Resolution.Resolved(null)
            } else {
                ComponentUpdater.resolveUpdate(companion.second, installedNormalize = ::stripVariant) {
                    companionResolved?.let { ComponentUpdater.Target(it.version, it.apkUrl, it.releaseUrl) }
                }.toResolution { target ->
                    UpdateInfo(
                        COMPANION_LABEL,
                        companion.second,
                        target.version,
                        target.releaseUrl,
                        "companion",
                    )
                }
            }

            val requestedPolicies = RequestedPolicies(channel, CompanionPolicy(companionChannel, companionMaxVersion))
            val reconciled = reconcileCache(
                previous = previous,
                requested = requestedPolicies,
                paneldCachedChannel = paneldCacheChannel,
                companionCachedPolicy = companionCachePolicy,
                paneldResolution = paneldResolution,
                companionResolution = companionResolution,
            )
            available = reconciled.available
            paneldCacheChannel = reconciled.paneldCacheChannel
            companionCachePolicy = reconciled.companionCachePolicy
            // A failed lookup keeps the previous target; readers only accept one resolved under their
            // exact policy, so a target from another channel or cap can never be reused.
            companionResolved?.let {
                resolvedCompanion = ResolvedTarget(
                    version = it.version,
                    tag = it.tag,
                    releaseUrl = it.releaseUrl,
                    channel = companionChannel,
                    cap = companionMaxVersion,
                    capped = it.capped,
                    newestVersion = it.newestVersion,
                )
            }
            if (reconciled.complete && companionResolved != null) {
                cacheKey = CacheKey(channel, companionChannel, companionMaxVersion)
                lastCheckElapsedMs = SystemClock.elapsedRealtime()
            } else {
                cacheKey = null
                lastCheckElapsedMs = -1L
            }
        }
        onChecked?.let { runCatching { it() } }
        Unit
    }

    /** Panel Assistant owns panel-app offers, including after a process restart. */
    internal fun paneldTarget(channel: String): ResolvedTarget? = null

    /** The Companion target resolved under exactly [channel] and [cap], or null. Never triggers a lookup. */
    internal fun companionTarget(channel: String, cap: String?): ResolvedTarget? =
        samePolicy(resolvedCompanion, channel, cap)

    /** A target is reusable only under the exact channel and safety cap that resolved it. */
    internal fun samePolicy(target: ResolvedTarget?, channel: String, cap: String?): ResolvedTarget? =
        target?.takeIf { it.channel == channel && it.cap == cap }

    /**
     * Restore the Companion target from an earlier process. Historical panel-app targets are ignored:
     * remembered catalogue data has no authority over the live Panel Assistant's update policy.
     */
    internal fun restoreTargets(paneld: String, companion: String) {
        if (resolvedCompanion == null) resolvedCompanion = decodeTarget(companion)
    }

    /** The current targets encoded for [restoreTargets]; blank when a component has none. */
    internal fun persistableTargets(): Pair<String, String> =
        "" to (resolvedCompanion?.let(::encodeTarget) ?: "")

    internal fun encodeTarget(target: ResolvedTarget): String = JSONObject()
        .put("version", target.version)
        .put("tag", target.tag)
        .put("release_url", target.releaseUrl)
        .put("channel", target.channel)
        .put("cap", target.cap ?: JSONObject.NULL)
        .put("capped", target.capped)
        .put("newest", target.newestVersion ?: JSONObject.NULL)
        .toString()

    /** Persisted input is re-validated as strictly as a fresh resolution; anything odd is absence. */
    internal fun decodeTarget(raw: String): ResolvedTarget? {
        if (raw.isBlank() || raw.length > MAX_PERSISTED_TARGET_CHARS) return null
        return runCatching {
            val json = JSONObject(raw)
            fun stringField(key: String): String? = json.opt(key).takeIf { it is String } as String?
            val version = stringField("version")?.takeIf { compareVersions(it, it) != null } ?: return null
            val tag = stringField("tag")?.takeIf(ReleaseCatalog::validTag) ?: return null
            val releaseUrl = stringField("release_url")?.takeIf { it.startsWith("https://") } ?: return null
            val channel = stringField("channel")?.takeIf { it == "stable" || it == "prerelease" } ?: return null
            ResolvedTarget(
                version = version,
                tag = tag,
                releaseUrl = releaseUrl,
                channel = channel,
                cap = stringField("cap"),
                capped = json.optBoolean("capped", false),
                newestVersion = stringField("newest"),
            )
        }.getOrNull()
    }

    private const val MAX_PERSISTED_TARGET_CHARS = 4_096

    /** Map a component's resolve -> compare -> decide [ComponentUpdater.Outcome] onto this checker's cache
     *  [Resolution]: an unresolved lookup is a [Resolution.Failed] (preserve last-known), an up-to-date
     *  component authoritatively resolves to no update, and an available update resolves to its [UpdateInfo]. */
    private inline fun ComponentUpdater.Outcome.toResolution(update: (ComponentUpdater.Target) -> UpdateInfo): Resolution =
        when (this) {
            ComponentUpdater.Outcome.Unresolved -> Resolution.Failed
            ComponentUpdater.Outcome.UpToDate -> Resolution.Resolved(null)
            is ComponentUpdater.Outcome.Update -> Resolution.Resolved(update(target))
        }

    /** Pure cache transaction: failures preserve only an entry resolved for the exact requested policy, while a successful null result authoritatively clears that component. A transaction is fresh only when both lookups resolved. */
    internal fun reconcileCache(
        previous: List<UpdateInfo>,
        requested: RequestedPolicies,
        paneldCachedChannel: String?,
        companionCachedPolicy: CompanionPolicy?,
        paneldResolution: Resolution,
        companionResolution: Resolution,
    ): CacheReconciliation {
        val previousPaneld = previous.firstOrNull { it.label == PANELD_LABEL }
        val previousCompanion = previous.firstOrNull { it.label == COMPANION_LABEL }
        val paneld = when (paneldResolution) {
            is Resolution.Resolved -> paneldResolution.update
            Resolution.Failed -> previousPaneld.takeIf { paneldCachedChannel == requested.paneldChannel }
        }
        val companion = when (companionResolution) {
            is Resolution.Resolved -> companionResolution.update
            Resolution.Failed -> previousCompanion.takeIf { companionCachedPolicy == requested.companion }
        }
        return CacheReconciliation(
            available = listOfNotNull(paneld, companion),
            paneldCacheChannel = if (paneldResolution is Resolution.Resolved) requested.paneldChannel else paneldCachedChannel,
            companionCachePolicy = if (companionResolution is Resolution.Resolved) requested.companion else companionCachedPolicy,
            complete = paneldResolution is Resolution.Resolved && companionResolution is Resolution.Resolved,
        )
    }

    /** Available updates minus any the user dismissed at their current latest version. */
    fun visible(ignored: Map<String, String>): List<UpdateInfo> = filterIgnored(available, ignored)

    /** Revalidate the cache against the current install state and version. This drops a stale Companion
     *  entry after uninstall or successful install even when the following network refresh failed. */
    fun current(context: Context, ignored: Map<String, String> = emptyMap()): List<UpdateInfo> {
        val companion = installedCompanion(context)
        return filterCurrent(filterIgnored(available, ignored), companion?.second)
    }

    /** The only cached ha-paneld release observation Panel Assistant may surface.
     *
     * This is deliberately a projection of [current], not a release resolver: it never calls
     * [check], [checkIfStale], GitHub, or the installer. A cache entry is usable only when it is the
     * ha-paneld component, a strictly newer stable target, and carries the exact tag that resolved it.
     * Anything incomplete, malformed, pre-release, ambiguous, or otherwise outside the bounded grammar
     * is represented as absence instead of being guessed or exposed.
     */
    internal data class PanelAssistantUpdate(
        val currentVersion: String,
        val targetVersion: String,
        val tag: String,
    )

    internal fun panelAssistantUpdate(current: List<UpdateInfo>): PanelAssistantUpdate? {
        val update = current.singleOrNull { it.component == "paneld" } ?: return null
        val currentVersion = update.currentVersion
        val targetVersion = update.latestVersion
        val tag = update.tag ?: return null
        if (update.prerelease ||
            !PANEL_ASSISTANT_CURRENT_VERSION.matches(currentVersion) ||
            !PANEL_ASSISTANT_STABLE_VERSION.matches(targetVersion) ||
            !ReleaseCatalog.validTag(tag) ||
            tag.removePrefix("v") != targetVersion ||
            !isNewer(targetVersion, currentVersion)
        ) return null
        return PanelAssistantUpdate(currentVersion, targetVersion, tag)
    }

    /** Bounded JSON for the additive `panel_assistant_update` status object. `none` means no safe
     * cached stable target is available; it does not claim that a release lookup was performed. */
    internal fun panelAssistantUpdateJson(current: List<UpdateInfo>): String =
        panelAssistantUpdate(current)?.let { update ->
            "{\"state\":\"available\",\"current_version\":${JSONObject.quote(update.currentVersion)}," +
                "\"target_version\":${JSONObject.quote(update.targetVersion)},\"tag\":${JSONObject.quote(update.tag)}}"
        } ?: "{\"state\":\"none\"}"

    private fun installedCompanion(context: Context): Pair<String, String>? = COMPANION_PKGS.firstNotNullOfOrNull { pkg ->
        runCatching {
            val version = context.packageManager.getPackageInfo(pkg, 0).versionName ?: ""
            pkg to version
        }.getOrNull()
    }

    /** Pure core of [current]. */
    internal fun filterCurrent(list: List<UpdateInfo>, companionVersion: String?): List<UpdateInfo> = list.mapNotNull { info ->
        if (info.label != COMPANION_LABEL) return@mapNotNull info
        val installed = companionVersion?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (ComponentUpdater.isUpdate(info.latestVersion, installed, installedNormalize = ::stripVariant)) {
            info.copy(currentVersion = installed)
        } else null
    }

    internal val COMPANION_PKGS = listOf(
        CompanionInstaller.FULL_PKG,
        CompanionInstaller.MINIMAL_PKG,
    )

    internal fun filterIgnored(list: List<UpdateInfo>, ignored: Map<String, String>): List<UpdateInfo> =
        list.filterNot { ignored[it.label] == it.latestVersion }

    /** HA Companion version names carry a variant suffix that is not a prerelease marker. */
    internal fun stripVariant(v: String): String = Regex("-(?:full|minimal|wear)$").replace(v.trim(), "")

    private data class ParsedVersion(val numeric: List<Int>, val suffix: String)

    private fun parseVersion(value: String): ParsedVersion? {
        val normalized = value.trim().removePrefix("v")
        val base = normalized.substringBefore('-')
        if (base.isEmpty()) return null
        val numeric = base.split('.').map { it.toIntOrNull() ?: return null }
        return ParsedVersion(numeric, normalized.substringAfter('-', "").lowercase())
    }

    /** Compare dotted versions with stable > prerelease and alpha < beta < rc ordering. Null means one
     *  input was malformed, allowing safety callers to fail closed instead of treating junk as version 0. */
    internal fun compareVersions(candidate: String, current: String): Int? {
        val left = parseVersion(candidate) ?: return null
        val right = parseVersion(current) ?: return null
        for (i in 0 until maxOf(left.numeric.size, right.numeric.size)) {
            val compared = left.numeric.getOrElse(i) { 0 }.compareTo(right.numeric.getOrElse(i) { 0 })
            if (compared != 0) return compared
        }
        if (left.suffix == right.suffix) return 0
        if (left.suffix.isEmpty()) return 1
        if (right.suffix.isEmpty()) return -1
        return comparePrerelease(left.suffix, right.suffix)
    }

    private fun comparePrerelease(left: String, right: String): Int {
        fun parts(value: String): Pair<String, Int> {
            val label = Regex("[a-z]+").find(value)?.value.orEmpty()
            val number = Regex("\\d+").findAll(value).lastOrNull()?.value?.toIntOrNull() ?: 0
            return label to number
        }
        fun rank(label: String): Int = when (label) {
            "alpha" -> 0
            "beta" -> 1
            "rc" -> 2
            else -> -1
        }
        val (leftLabel, leftNumber) = parts(left)
        val (rightLabel, rightNumber) = parts(right)
        if (leftLabel != rightLabel) {
            val leftRank = rank(leftLabel)
            val rightRank = rank(rightLabel)
            if (leftRank != rightRank) return leftRank.compareTo(rightRank)
            return leftLabel.compareTo(rightLabel)
        }
        return leftNumber.compareTo(rightNumber)
    }

    internal fun isNewer(candidate: String, current: String): Boolean = compareVersions(candidate, current)?.let { it > 0 } == true

    private const val PANELD_LABEL = "ha-paneld"
    private const val COMPANION_LABEL = "HA Companion"
    private const val PANEL_ASSISTANT_VERSION_PART = "(?:0|[1-9][0-9]{0,7})"
    private val PANEL_ASSISTANT_STABLE_VERSION = Regex(
        "^$PANEL_ASSISTANT_VERSION_PART\\.$PANEL_ASSISTANT_VERSION_PART\\.$PANEL_ASSISTANT_VERSION_PART$",
    )
    private val PANEL_ASSISTANT_CURRENT_VERSION = Regex(
        "^$PANEL_ASSISTANT_VERSION_PART\\.$PANEL_ASSISTANT_VERSION_PART\\.$PANEL_ASSISTANT_VERSION_PART" +
            "(?:-(?:alpha|beta|rc)$PANEL_ASSISTANT_VERSION_PART)?$",
    )
}
