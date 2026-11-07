package io.panelassistant.android

import android.content.SharedPreferences
import io.panelassistant.android.Config.Companion.CONFIG_LOCK
import io.panelassistant.android.config.SettingValue
import io.panelassistant.android.config.Validation
import io.panelassistant.android.dashboard.HomeDashboardLaunchCache
import io.panelassistant.android.util.HaLink

// The Home Assistant connection: address, handover, credentials and their ownership snapshots, connection
// routes, and the cached links and launch path owned by that connection.

/** Cached HA device-settings URL. [haDeviceLinkTarget] records which configured HA endpoint and
 *  panel identity produced the random device-registry id so a renderer/server change cannot leave
 *  "Open in HA" pointing at a device which does not exist on the new server. */
val Config.haDeviceUrl: String get() = prefs.getString("ha_device_url", "")!!
val Config.haDeviceLinkTarget: String get() = prefs.getString("ha_link_target", "")!!
/** When [haDeviceUrl] was last resolved — drives a periodic re-resolve so a link left stale by an HA
 *  device delete+recreate (the id changes without a panel_id change) self-heals. */
val Config.haLinkResolvedAt: Long get() = prefs.getLong("ha_link_at", 0L)
fun Config.setHaDeviceUrl(url: String, target: String) {
    prefs.edit()
        .putString("ha_device_url", url)
        .putString("ha_link_target", target)
        .putLong("ha_link_at", System.currentTimeMillis())
        .apply()
}

/** A pre-ownership (legacy) cache is deliberately stale so the first upgraded start repairs it. */
fun Config.haDeviceLinkIsFresh(target: String, nowMs: Long, ttlMs: Long): Boolean =
    haDeviceUrl.isNotBlank() && haDeviceLinkTarget == target && nowMs - haLinkResolvedAt in 0 until ttlMs

/** HA's frontend URL as the Companion knows it (its internal/external_url), resolved from the Companion
 *  DB + cached. The header "Open in HA" button falls back to this when [haDeviceUrl] (the panel's own
 *  device page) hasn't resolved — e.g. a remote panel over a tunnel — so the button is always present. */
val Config.haBaseUrl: String get() = prefs.getString("ha_base_url", "")!!
fun Config.setHaBaseUrl(url: String) { prefs.edit().putString("ha_base_url", url).apply() }

/** Best "Open in HA" target. Never expose an unowned legacy cache or a device id resolved for a
 * different native endpoint or Panel Assistant entry while its asynchronous repair is still running. */
val Config.haLinkUrl: String get() {
    val nativeTarget = HaConnectionRoutes.panelAssistantEntryId(this)?.let { HaLink.resolutionTarget(haUrl, it) }
    val ownedDeviceUrl = haDeviceUrl.takeIf {
        haDeviceLinkTarget.isNotBlank() && (haUrl.isBlank() || haDeviceLinkTarget == nativeTarget)
    }
    return ownedDeviceUrl ?: haUrl.ifBlank { haBaseUrl }
}

/** Home Assistant base URL for the built-in dashboard renderer, e.g. "http://homeassistant.local:8123".
 *  Empty => the built-in renderer is unavailable (external renderers unaffected). */
val Config.haUrl: String get() = stringPref("ha_url")

/** True when the Panel Assistant integration installed or adopted this panel and said so.
 *
 *  This is the single definition of "Home Assistant deployed this panel". Every setup step that
 *  skips a question Home Assistant can already answer reads THIS, never the presence of a value
 *  Home Assistant happened to supply: a handed-over value that failed verification still came from
 *  Home Assistant.
 *  Deriving provenance from a populated field instead would re-collapse that distinction. */
val Config.haSetupHandover: Boolean get() = prefs.getBoolean("ha_setup_handover", false)

fun Config.setHaSetupHandover(value: Boolean) {
    edit { putBoolean("ha_setup_handover", value) }
}

/** The Home Assistant address the integration handed this panel, kept only while it has not been
 *  accepted. A verified address is promoted into [haUrl] and this is cleared; a failed one stays
 *  here so the wizard can show what was tried. */
val Config.haUrlHandover: String get() = prefs.getString("ha_url_handover", "")!!

fun Config.setHaUrlHandover(raw: String) {
    edit { putString("ha_url_handover", raw.trim()) }
}

/** Why [haUrlHandover] did not answer, or blank when none was tried or it verified. Written only by
 *  the panel's own probe; `/api/v1/config` refuses it from the network. */
val Config.haUrlHandoverReason: String get() = prefs.getString("ha_url_handover_reason", "")!!

fun Config.setHaUrlHandoverReason(raw: String) {
    edit { putString("ha_url_handover_reason", raw.trim()) }
}

/** The built-in renderer is only ready once the URL and an actual auth route exist. */
internal fun Config.builtInRendererReady(): Boolean =
    haUrl.isNotBlank() && (haToken.isNotBlank() || haRefreshToken.isNotBlank())

/** Last authoritative HA server version observed for this exact renderer endpoint. This is only a
 * transient-outage fallback for the V2 compatibility preflight; a successful probe always wins. */
internal fun Config.cachedHaServerVersion(url: String): String? {
    val owner = url.trim().trimEnd('/')
    return prefs.getString("dashboard_ha_version_owner", null)
        ?.takeIf { it == owner }
        ?.let { prefs.getString("dashboard_ha_version", null) }
}

internal fun Config.setHaServerVersionIfOwned(url: String, version: String): Boolean = synchronized(CONFIG_LOCK) {
    val owner = url.trim().trimEnd('/')
    if (haUrl.trim().trimEnd('/') != owner) return@synchronized false
    durableCommit {
        putString("dashboard_ha_version_owner", owner)
        putString("dashboard_ha_version", version.take(64))
    }
}

/** The persisted launch cache's live owner: endpoint + stable credential + configured
 *  home_dashboard, one-way hashed (see [HomeDashboardLaunchCache.ownerFingerprint]). */
internal fun Config.homeDashboardLaunchOwner(): String = synchronized(CONFIG_LOCK) {
    HomeDashboardLaunchCache.ownerFingerprint(haAuthSnapshot().stableOwner(), homeDashboard)
}

/**
 * Last home-dashboard path a live, list-validated resolution produced for the CURRENT owner.
 * A relaunch accelerator only, the dashboard analogue of [cachedHaServerVersion]: the renderer
 * may navigate to it immediately while the authenticated resolution re-runs, and that live
 * answer always supersedes it. Any HA instance, account or explicit-path change makes the row
 * unreadable by owner mismatch; a corrupt stored value fails closed to a live resolution.
 */
internal fun Config.cachedHomeDashboardLaunchPath(): String? = synchronized(CONFIG_LOCK) {
    prefs.getString("dashboard_launch_path_owner", null)
        ?.takeIf { it == homeDashboardLaunchOwner() }
        ?.let { HomeDashboardLaunchCache.sanitizedStoredPath(prefs.getString("dashboard_launch_path", null)) }
}

/** Persist only while [ownerFingerprint] still names the live owner, so a resolution that raced
 *  a credential/endpoint/setting change cannot stamp the new identity with the old answer. */
internal fun Config.setHomeDashboardLaunchPathIfOwned(ownerFingerprint: String, path: String): Boolean =
    synchronized(CONFIG_LOCK) {
        if (homeDashboardLaunchOwner() != ownerFingerprint) return@synchronized false
        // Store the resolved route verbatim or not at all. Truncating an over-long path would
        // manufacture a DIFFERENT route that Home Assistant's dashboard list never validated,
        // and the next launch would navigate straight to it; declining to accelerate that launch
        // is the safe outcome, since the live resolution still produces the real target.
        if (path.length > HomeDashboardLaunchCache.MAX_STORED_PATH_CHARS) return@synchronized false
        if (prefs.getString("dashboard_launch_path_owner", null) == ownerFingerprint &&
            prefs.getString("dashboard_launch_path", null) == path
        ) return@synchronized true
        durableCommit {
            putString("dashboard_launch_path_owner", ownerFingerprint)
            putString("dashboard_launch_path", path)
        }
    }

/** A CONFIRMED no-legal-dashboards answer is the one case that must erase the accelerator —
 *  otherwise every relaunch replays a path the completed list read just proved unreachable.
 *  Transient failures never reach here. */
internal fun Config.clearHomeDashboardLaunchPathIfOwned(ownerFingerprint: String): Boolean =
    synchronized(CONFIG_LOCK) {
        if (homeDashboardLaunchOwner() != ownerFingerprint) return@synchronized false
        durableCommit {
            remove("dashboard_launch_path_owner")
            remove("dashboard_launch_path")
        }
    }

/** Access token the built-in renderer hands the HA frontend via the external-auth bridge. Either a
 *  long-lived access token (set once at provisioning) OR the current short-lived access token kept
 *  fresh from [haRefreshToken]. Never typed on the panel. */
val Config.haToken: String get() = stringPref("ha_token")

/** OAuth refresh token (from a provisioning login). When set, [haToken] is a short-lived access
 *  token the app refreshes on demand — so no 10-year token lives on the panel. Blank => [haToken] is
 *  treated as a static long-lived token that never needs refreshing. */
val Config.haRefreshToken: String get() = stringPref("ha_refresh_token")

/** Epoch-seconds expiry of the current [haToken] (refresh model only). 0 => unknown → refresh now. */
val Config.haTokenExpiry: Long get() = longPref("ha_token_expiry")

/** OAuth client_id to use when refreshing [haToken]. Blank => the HA origin (`<ha_url>/`, what the
 *  frontend uses). Set it to match the client the refresh token was issued for — e.g.
 *  `https://home-assistant.io/android` to reuse an HA Companion refresh token. */
val Config.haClientId: String get() = stringPref("ha_client_id")

internal val Config.haEffectiveUrl: String get() = HaConnectionRoutes.current(this).url

internal fun Config.haConnectionRecord(): String = prefs.getString(HaConnectionRoutes.RECORD_KEY, "").orEmpty()
internal fun Config.haRouteEpoch(): Long = prefs.getLong(HaConnectionRoutes.EPOCH_KEY, 0L)
internal fun Config.commitHaConnectionRecord(expected: HaConnectionRoute, value: String, changedRoute: Boolean): Boolean =
    synchronized(CONFIG_LOCK) {
        if (!HaConnectionRoutes.isCurrent(this, expected)) return@synchronized false
        durableCommit {
            putString(HaConnectionRoutes.RECORD_KEY, value)
            if (changedRoute) putLong(HaConnectionRoutes.EPOCH_KEY, expected.epoch + 1)
        }
    }

/** Whether [posted] as `ha_url` moves the panel to another Home Assistant, which clears the stored credentials.
 * A credential stored while no address was set can only belong to the first address, so setting that address
 * keeps it: Configure sends each field as it is committed, and a token can arrive first. */
internal fun haOriginChanges(previousUrl: String, posted: String?): Boolean =
    posted != null && previousUrl.isNotBlank() && posted.trimEnd('/') != previousUrl.trimEnd('/')

/** The sign-in left after [incoming] moves the panel from [previousUrl] to another Home Assistant: each
 * credential [incoming] carries, and a blank for every one it does not, so the previous account's tokens never
 * reach the new address. Null when the origin does not change. The one rule for the Configure door and for
 * every import and restore. */
internal fun haSignInAfterOriginChange(previousUrl: String, incoming: Map<String, String>): HaAuthSnapshot? {
    val url = incoming["ha_url"]
    if (url == null || !haOriginChanges(previousUrl, url)) return null
    return HaAuthSnapshot(
        url = url,
        accessToken = incoming["ha_token"].orEmpty(),
        refreshToken = incoming["ha_refresh_token"].orEmpty(),
        tokenExpiry = incoming["ha_token_expiry"]?.toLongOrNull() ?: 0L,
        clientId = incoming["ha_client_id"].orEmpty(),
    )
}

internal fun Config.forgetHaRoutes(editor: SharedPreferences.Editor) {
    editor.remove(HaConnectionRoutes.RECORD_KEY)
    // A new binding reopens the REST routes until its Panel Assistant grants both UI families (section 20.8).
    editor.remove(Config.UI_ROUTES_CLOSED_PREF)
    editor.putLong(HaConnectionRoutes.EPOCH_KEY, haRouteEpoch() + 1)
}

/** One immutable durable-state generation for renderer authentication and refresh. */
internal fun Config.haAuthSnapshot(): HaAuthSnapshot = synchronized(CONFIG_LOCK) {
    val all = prefs.all
    HaAuthSnapshot(
        url = all["ha_url"] as? String ?: "",
        accessToken = all["ha_token"] as? String ?: "",
        refreshToken = all["ha_refresh_token"] as? String ?: "",
        tokenExpiry = (all["ha_token_expiry"] as? Number)?.toLong() ?: 0L,
        clientId = all["ha_client_id"] as? String ?: "",
    )
}

internal fun Config.beginHaOAuthAttempt(): HaOAuthAttemptAuthority = synchronized(CONFIG_LOCK) {
    HaOAuthAttemptAuthority(
        owner = haAuthSnapshot().stableOwner(),
        epoch = haOAuthAttemptEpoch.incrementAndGet(),
    )
}

internal fun Config.isHaOAuthAttemptCurrent(epoch: Long): Boolean = synchronized(CONFIG_LOCK) {
    haOAuthAttemptEpoch.get() == epoch
}

/** [url] as the `ha_url` spec stores it, or null when the spec refuses it. */
private fun Config.admittedHaUrl(url: String): String? =
    (SettingValue.validate(specOf("ha_url"), url) as? Validation.Ok)?.normalized

/** Persist the built-in renderer connection (HTTP config page / provisioning). A null token leaves
 *  it unchanged. A URL the `ha_url` spec refuses stores nothing and returns false. */
fun Config.setHaConnection(url: String, token: String?): Boolean {
    val normalized = admittedHaUrl(url) ?: return false
    edit {
        if (normalized != haUrl.trim().trimEnd('/') || (token != null && token != haToken)) forgetHaRoutes(this)
        putString("ha_url", normalized)
        stageDashboardEntityHaUrlChange(this, normalized)
        if (token != null) putString("ha_token", token)
    }
    return true
}

/** Persist a refresh result only while the complete auth generation used to obtain it is still
 * current. This prevents a late OAuth response from overwriting credentials replaced by an admin. */
internal fun Config.setHaRefreshedTokenIfOwned(
    expected: HaAuthSnapshot,
    access: String,
    expiryEpochSec: Long,
): Boolean = synchronized(CONFIG_LOCK) {
    if (haAuthSnapshot() != expected) return false
    durableCommit {
        putString("ha_token", access)
        putLong("ha_token_expiry", expiryEpochSec)
    }
}

/** Set (or clear, with "") the OAuth refresh token — provisioning path. */
fun Config.setHaRefreshToken(refresh: String) { edit { if (refresh != haRefreshToken) forgetHaRoutes(this); putString("ha_refresh_token", refresh) } }

/** Set the current access-token expiry (epoch seconds) — provisioning path. */
fun Config.setHaTokenExpiry(epochSec: Long) { edit { putLong("ha_token_expiry", epochSec) } }

/** Set (or clear, with "") the OAuth client_id used for token refresh. */
fun Config.setHaClientId(clientId: String) { edit { if (clientId != haClientId) forgetHaRoutes(this); putString("ha_client_id", clientId) } }

/** Atomically persist every value borrowed from the Companion for a built-in renderer switch. */
fun Config.setBorrowedRendererSettings(
    url: String,
    accessToken: String,
    refreshToken: String,
    tokenExpiry: Long,
    clientId: String,
    zoom: Int?,
): Boolean = admittedHaUrl(url) != null && applyBatch {
    setHaConnection(url, accessToken)
    setHaRefreshToken(refreshToken)
    setHaTokenExpiry(tokenExpiry)
    setHaClientId(clientId)
    zoom?.let { borrowed ->
        val spec = specOf("dashboard_zoom")
        setDashboardZoom(borrowed.coerceIn(requireNotNull(spec.min).toInt(), requireNotNull(spec.max).toInt()))
    }
    edit { putBoolean("renderer_launch_pending", true) }
}

internal fun Config.clearHaDeviceLink(editor: SharedPreferences.Editor) {
    editor.remove("ha_device_url").remove("ha_link_target").remove("ha_link_at")
}
