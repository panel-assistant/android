package io.panelassistant.android.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Checks GitHub releases for available updates to the installed HA Companion app. Panel Assistant owns
 * offers for the panel app itself. Catalog state is cached, but every entry remains tied to the channel
 * and device safety policy used to resolve it.
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

    internal sealed interface Resolution {
        data class Resolved(val update: UpdateInfo?) : Resolution
        data object Failed : Resolution
    }

    internal data class CacheReconciliation(
        val available: List<UpdateInfo>,
        val companionCachePolicy: CompanionPolicy?,
    )

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
    @Volatile private var companionCachePolicy: CompanionPolicy? = null
    private val checkMutex = Mutex()

    /** Resolve the Companion under one serialized cache transaction. A failed lookup keeps its last-known
     *  result; a successful lookup authoritatively adds or removes the update. */
    suspend fun check(
        context: Context,
        companionChannel: String = "stable",
        companionMaxVersion: String?,
    ) = withContext(Dispatchers.IO) {
        checkMutex.withLock {
            val previous = available
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

            val requested = CompanionPolicy(companionChannel, companionMaxVersion)
            val reconciled = reconcileCache(
                previous = previous,
                requested = requested,
                companionCachedPolicy = companionCachePolicy,
                companionResolution = companionResolution,
            )
            available = reconciled.available
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
        }
        onChecked?.let { runCatching { it() } }
        Unit
    }

    /** The Companion target resolved under exactly [channel] and [cap], or null. Never triggers a lookup. */
    internal fun companionTarget(channel: String, cap: String?): ResolvedTarget? =
        samePolicy(resolvedCompanion, channel, cap)

    /** A target is reusable only under the exact channel and safety cap that resolved it. */
    internal fun samePolicy(target: ResolvedTarget?, channel: String, cap: String?): ResolvedTarget? =
        target?.takeIf { it.channel == channel && it.cap == cap }

    /** Restore the Companion target from an earlier process. */
    internal fun restoreTargets(companion: String) {
        if (resolvedCompanion == null) resolvedCompanion = decodeTarget(companion)
    }

    /** The current targets encoded for [restoreTargets], in the stored pair whose retired panel-app half
     *  stays blank; blank when the Companion has none. */
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

    /** Pure cache transaction: a failure preserves only an entry resolved for the exact requested policy,
     *  while a successful null result authoritatively clears it. */
    internal fun reconcileCache(
        previous: List<UpdateInfo>,
        requested: CompanionPolicy,
        companionCachedPolicy: CompanionPolicy?,
        companionResolution: Resolution,
    ): CacheReconciliation {
        val companion = when (companionResolution) {
            is Resolution.Resolved -> companionResolution.update
            Resolution.Failed -> previous.firstOrNull { it.label == COMPANION_LABEL }
                .takeIf { companionCachedPolicy == requested }
        }
        return CacheReconciliation(
            available = listOfNotNull(companion),
            companionCachePolicy = if (companionResolution is Resolution.Resolved) requested else companionCachedPolicy,
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

    private const val COMPANION_LABEL = "HA Companion"
}
