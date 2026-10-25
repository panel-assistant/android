package io.panelassistant.android

import android.content.Context
import android.content.ContentResolver
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.security.SecureRandom
import io.panelassistant.android.camera.CameraResolution
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.Migrations
import io.panelassistant.android.config.SettingSpec
import io.panelassistant.android.config.SettingType
import io.panelassistant.android.config.SettingValue
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.config.Validation
import io.panelassistant.android.config.navbarModeDefault
import io.panelassistant.android.config.isLiteralNullAreaName
import io.panelassistant.android.config.normalizedHaAreaName
import io.panelassistant.android.config.panelIdSlug
import io.panelassistant.android.dashboard.HomeDashboardLaunchCache
import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.persistence.AppState
import io.panelassistant.android.persistence.DurableVisibilityPreferences
import io.panelassistant.android.util.DashboardTheme
import io.panelassistant.android.util.HaLink
import io.panelassistant.android.util.LogShipEndpoint
import io.panelassistant.android.util.SystemProps
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal enum class AutoSleepWriteResult { UNCHANGED, COMMITTED, FAILED }

internal data class HaAuthSnapshot(
    val url: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenExpiry: Long,
    val clientId: String,
)

/** Credential ownership that remains stable while a refresh token rotates its current access token. */
internal data class HaAuthOwner(
    val url: String,
    val refreshToken: String,
    val clientId: String,
    val staticAccessToken: String,
) {
    /** Carried inside snapshots that may be printed; the credential never is. */
    override fun toString(): String = "HaAuthOwner(redacted)"
}

/** Process-local authority for one administrator-browser Home Assistant login. The epoch makes a
 * newer Connect request supersede an older callback even when that callback already consumed state. */
internal data class HaOAuthAttemptAuthority(
    val owner: HaAuthOwner,
    val epoch: Long,
)

internal fun HaAuthSnapshot.stableOwner(): HaAuthOwner = HaAuthOwner(
    url = url.trim().trimEnd('/'),
    refreshToken = refreshToken,
    clientId = clientId,
    staticAccessToken = accessToken.takeIf { refreshToken.isBlank() }.orEmpty(),
)

internal data class DashboardEntityBackupState(
    val instanceKey: String,
    val instanceOrigin: String,
    val instanceUuid: String,
    val dashboardPath: String,
    val filterIds: String,
    val filterEnabled: Boolean,
    val filterOwner: String,
    val learningApplied: Boolean,
    val appliedOwner: String,
    val overrides: String,
    val overrideOwner: String,
    val initialActivationPending: Boolean,
)

/** One-time migration input for the adaptive proximity learner. */
internal data class ProximityLearningSeed(
    val nearRaw: Float,
    val farRaw: Float,
)

/**
 * Runtime configuration. Production state is transactional SQLite; the SharedPreferences-shaped
 * interface remains as a test and typed-caller compatibility boundary.
 *
 * This file holds the store, its transactions, the registry-driven generic access, import and restore
 * staging and the live-store migration. Self-contained areas are extensions in their own files:
 * ConfigHaConnection, ConfigDashboardEntity, ConfigSetupJourney, ConfigAutoSleep and ConfigNavbar.
 */
class Config private constructor(
    internal val prefs: SharedPreferences,
    private val contentResolver: ContentResolver?,
    private val calibrationPrefs: SharedPreferences,
    private val deviceStatePrefs: SharedPreferences,
    internal val resources: Resources?,
) {
    enum class SecurityMode { RELAXED, HARDENED }

    private val wakeOnWaveEpoch = AtomicLong()
    internal val haOAuthAttemptEpoch = AtomicLong()
    @Volatile private var logShipAbsentProtocolFallback: String? = null

    constructor(context: Context) : this(
        AppState.preferences(context, "config"),
        context.applicationContext.contentResolver,
        AppState.preferences(context, "profile-calibration"),
        AppState.preferences(context, "power-safety-acknowledgement"),
        context.applicationContext.resources,
    )

    /** JVM-test seam; identity defaults that consult Android settings require the production constructor. */
    internal constructor(prefs: SharedPreferences) : this(prefs, null, prefs, prefs, null)

    /** JVM-test seam that keeps configuration and non-transferable sensor calibration separate. */
    internal constructor(prefs: SharedPreferences, calibrationPrefs: SharedPreferences) :
        this(prefs, null, calibrationPrefs, prefs, null)

    /** Activity-scoped observation of live settings without exposing the persistence implementation. */
    internal fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    internal fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /**
     * One-shot transition to the TCP fresh-install default.
     *
     * The previous build resolved an absent `log_ship_protocol` as UDP. Changing the registry default
     * alone would therefore retarget every upgraded absent-key configuration. An entirely empty store is
     * a genuinely fresh install and takes the new TCP default; any pre-existing store keeps its prior
     * effective UDP value by materialising it before the marker is committed. Explicit values are never
     * rewritten. A failed commit leaves the marker absent so the migration retries next process start.
     */
    internal fun migrateLogShipTcpDefault() = synchronized(CONFIG_LOCK) {
        if (prefs.getBoolean(LOG_SHIP_TCP_DEFAULT_MIGRATION_PREF, false)) return@synchronized
        val preExisting = prefs.all.isNotEmpty()
        val preserveUdp = preExisting && !prefs.contains("log_ship_protocol")
        // Establish process truth before attempting durability. A failed SQLite/preferences commit must
        // not silently retarget this running upgraded panel to TCP while the absent marker waits to retry.
        if (preserveUdp) logShipAbsentProtocolFallback = LogShipEndpoint.SYSLOG_UDP
        val committed = prefs.edit()
            .putBoolean(LOG_SHIP_TCP_DEFAULT_MIGRATION_PREF, true)
            .apply {
                if (preserveUdp) {
                    putString("log_ship_protocol", LogShipEndpoint.SYSLOG_UDP)
                }
            }
            .commit()
        if (committed) logShipAbsentProtocolFallback = null
        Unit
    }

    val httpPort: Int get() = prefs.getInt("http_port", DEFAULT_PORT)

    /**
     * Device-local control-plane posture. This key deliberately lives outside SettingsRegistry, so a
     * remote config import, backup restore, HTTP request, or Home Assistant command cannot enable or disable it.
     * The non-exported native settings activity is the only UI that changes it.
     */
    val securityMode: SecurityMode
        get() = if (prefs.getBoolean(SECURITY_MODE_PREF, false)) SecurityMode.HARDENED else SecurityMode.RELAXED

    /** Hardened mode and ha-paneld-owned network ADB are mutually exclusive. The check and mode
     * commit share [CONFIG_LOCK] with network-ADB ownership so concurrent local/network requests
     * cannot cross and leave both durable authorities enabled. */
    fun setSecurityMode(mode: SecurityMode): Boolean = synchronized(CONFIG_LOCK) {
        if (mode == SecurityMode.HARDENED && networkAdbEnabled) return@synchronized false
        durableCommit { putBoolean(SECURITY_MODE_PREF, mode == SecurityMode.HARDENED) }
    }

    val hardenedSecurityEnabled: Boolean get() = securityMode == SecurityMode.HARDENED

    /**
     * Whether this panel's light sensor has delivered a reading on this install. Once true, the
     * illuminance entity stays described through any later failed or missing reading; never true, it
     * is not described at all. Device-local: a restore onto other hardware proves nothing about it.
     */
    var lightSensorReported: Boolean
        get() = prefs.getBoolean(LIGHT_SENSOR_REPORTED_PREF, false)
        set(value) = synchronized(CONFIG_LOCK) {
            prefs.edit().putBoolean(LIGHT_SENSOR_REPORTED_PREF, value).commit()
            Unit
        }

    /** The address-family policy for every Home Assistant connection. */
    val addressFamily: String get() = stringPref(SettingsRegistry.ADDRESS_FAMILY_KEY)

    /** Stable per-panel id used in entity ids. A fresh install persists its generated identity before the
     *  service starts mDNS or the Panel Assistant session, so a later Android device-name change cannot
     *  silently rename the panel. The fallback remains for callers that run before service startup. */
    val panelId: String
        get() = prefs.getString("panel_id", profile?.settingDefaults?.get("panel_id")) ?: defaultPanelId()

    /** Materialize the valid generated identity once. Explicit identities always win, and a failed
     *  durable write leaves the same generated fallback available without claiming it is missing. */
    internal fun ensurePanelId(defaultValue: () -> String = ::defaultPanelId): String = synchronized(CONFIG_LOCK) {
        prefs.getString("panel_id", null)?.takeIf { it.isNotBlank() }?.let { return@synchronized it }
        val generated = defaultValue().takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("generated panel id is blank")
        if (!durableCommit { putString("panel_id", generated) }) {
            Log.w(TAG, "could not persist generated panel id; retaining runtime fallback")
        }
        generated
    }

    private fun defaultPanelId(): String {
        val resolver = requireNotNull(contentResolver) { "Android settings unavailable" }
        val name = Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME)
        // A meaningful, non-generic device name → use it; else model + a short id suffix.
        return if (!name.isNullOrBlank() && !name.equals(Build.MODEL, ignoreCase = true)) slug(name)
        else slug(Build.MODEL) + "_" + defaultPanelIdSuffix()
    }

    /** The disambiguating suffix a generated panel id carries when the device name is generic. */
    private fun defaultPanelIdSuffix(): String = panelIdSuffix(androidId, deviceUid)

    // --- atomic batch writes -----------------------------------------------------------------------
    // Setters normally each fire their own async prefs.edit().apply(). Inside [applyBatch] they instead
    // stage into one shared editor, committed once, so a multi-field write (e.g. the /config form apply)
    // is all-or-nothing and durable before we return — a power loss can't leave a half-applied config.
    @Volatile private var batchEditor: SharedPreferences.Editor? = null
    @Volatile private var batchThread: Thread? = null

    /** Run [block] with every [edit]-based setter write staged into one editor and committed atomically
     *  when it returns. SQLite-backed production state does not publish values or listeners until the
     *  commit is durable; plain SharedPreferences test doubles retain their ordinary commit behaviour.
     *  Confined to the calling thread: a setter fired on another thread during the batch takes its normal
     *  immediate-apply path (SharedPreferences.Editor isn't thread-safe). Non-nesting; the /config apply
     *  that uses it is single-threaded per request. */
    fun applyBatch(afterCommit: () -> Unit = {}, block: () -> Unit): Boolean = synchronized(CONFIG_LOCK) {
        val committed = durableCommit {
            runBatch(this, block)
        }
        committed.also { if (it) afterCommit() }
    }

    /** Commit a synchronous mutation without publishing a SQLite-backed candidate before it is durable. */
    internal fun durableCommit(mutation: SharedPreferences.Editor.() -> Unit): Boolean =
        (prefs as? DurableVisibilityPreferences)?.commitWithDurableVisibility(mutation)
            ?: prefs.edit().run {
                mutation()
                commit()
            }

    private fun runBatch(editor: SharedPreferences.Editor, block: () -> Unit) {
        batchEditor = editor
        batchThread = Thread.currentThread()
        try {
            block()
        } finally {
            batchEditor = null
            batchThread = null
        }
    }

    /** Serialize a compound config operation across every Config wrapper in this process. */
    internal fun <T> synchronizedTransaction(block: () -> T): T = synchronized(CONFIG_LOCK, block)

    /** Write helper: stage into the active [applyBatch] editor (same thread) or, outside a batch, apply
     *  immediately exactly as before. Setters call this instead of prefs.edit()…apply() so they compose. */
    internal fun edit(block: SharedPreferences.Editor.() -> Unit) {
        synchronized(CONFIG_LOCK) {
            val b = batchEditor
            if (b != null && batchThread === Thread.currentThread()) {
                b.block()
            } else {
                val e = prefs.edit()
                e.block()
                e.apply()
            }
        }
    }

    /** Synchronous variant for writes that must survive an immediate reboot. Still composes into a batch. */
    private fun editCommit(block: SharedPreferences.Editor.() -> Unit): Boolean =
        synchronized(CONFIG_LOCK) {
            val b = batchEditor
            if (b != null && batchThread === Thread.currentThread()) {
                b.block()
                true
            } else {
                durableCommit(block)
            }
        }

    /** Persist a new panel id (used by the HTTP config page). */
    fun setPanelId(id: String) {
        edit { this@Config.stagePanelId(this, id) }
    }

    /** Stage [id] and its dependent HA-link invalidation into a caller-owned transaction. */
    fun stagePanelId(editor: SharedPreferences.Editor, id: String) {
        // The panel_id is the HA device identifier, so only an ACTUAL change invalidates the cached device
        // link. The config form resubmits panel_id on every save (unchanged), so clearing unconditionally
        // would drop the link on every save.
        val changed = id != prefs.getString("panel_id", null)
        editor.putString("panel_id", id)
        if (changed) clearHaDeviceLink(editor)
    }

    /** Apply secondary-key semantics for a validated bundle import using the SAME transaction as its
     *  primary values. These mirror the config form: clearing an identity also clears its credential,
     *  and a replacement HA access token supersedes an old refresh session unless a new refresh token
     *  is supplied beside it. Call after staging the accepted values so these safety clears win. */
    internal fun stageImportDependencies(editor: SharedPreferences.Editor, accepted: Map<String, String>) {
        // The sink's three fields must always describe one destination; see LogShipEndpoint.canonicalUpdate
        // for the precedence and why an embedded address wins. Reconciled here rather than in the caller's
        // per-key loop for two reasons: it reads the whole accepted map, so the result cannot depend on
        // iteration order, and every applyAccepted path (import, revision rollback, restore) reaches it.
        // Fallbacks read committed preferences, which is correct precisely for the keys this update does
        // not carry — a staged read-back would be pre-commit and is never used here.
        LogShipEndpoint.canonicalUpdate(accepted, logShipHost, logShipPort, logShipProtocol)?.let { fields ->
            editor.putString("log_ship_host", fields.getValue("log_ship_host"))
            editor.putInt("log_ship_port", fields.getValue("log_ship_port").toInt())
            editor.putString("log_ship_protocol", fields.getValue("log_ship_protocol"))
        }

        // stage pins the effective source when it stores auto_sleep; an imported source must still win,
        // whichever order the bundle staged the two keys in.
        accepted["auto_sleep_source"]?.takeIf { it in specOf("auto_sleep_source").options }
            ?.let { editor.putString("auto_sleep_source", it) }

        accepted["dashboard_package"]?.let { next ->
            if (next != dashboardPackage) editor.putBoolean("renderer_launch_pending", true)
        }
        accepted["home_dashboard"]?.let { next ->
            val nextPath = dashboardEntityScopePath(next)
            if (nextPath != "/" && nextPath != dashboardEntityScopePath(homeDashboard)) {
                editor.putString("dashboard_entity_dashboard_path", nextPath)
            }
        }

        val oldAuth = haAuthSnapshot()
        if (accepted.any { (key, value) -> when (key) {
                "ha_url" -> value.trim().trimEnd('/') != oldAuth.url.trim().trimEnd('/')
                "ha_refresh_token" -> value != oldAuth.refreshToken
                "ha_client_id" -> value != oldAuth.clientId
                "ha_token" -> value != oldAuth.accessToken
                else -> false
            } }) forgetHaRoutes(editor)
        val haUrl = accepted["ha_url"]
        if (haUrl != null) {
            val normalized = haUrl.trimEnd('/')
            editor.putString("ha_url", normalized)
            stageDashboardEntityHaUrlChange(editor, normalized)
        }
        if (haUrl?.isEmpty() == true) {
            editor.putString("ha_token", "")
            editor.putString("ha_refresh_token", "")
            editor.putString("ha_client_id", "")
            editor.putLong("ha_token_expiry", 0L)
        } else {
            haSignInAfterOriginChange(oldAuth.url, accepted)?.let { moved ->
                editor.putString("ha_token", moved.accessToken)
                editor.putString("ha_refresh_token", moved.refreshToken)
                editor.putLong("ha_token_expiry", moved.tokenExpiry)
                editor.putString("ha_client_id", moved.clientId)
            }
            val access = accepted["ha_token"]
            val explicitAccess = access != null && access.isNotEmpty()
            if ("ha_token_expiry" !in accepted &&
                ("ha_token" in accepted || "ha_refresh_token" in accepted)
            ) {
                // Compatibility with backups/config bundles written before expiry joined the registry:
                // never retain an unrelated target panel's expiry beside restored credential material.
                // Unknown expiry forces DashboardAuth to refresh before trusting the restored access token.
                editor.putLong("ha_token_expiry", 0L)
            }
            if (explicitAccess && "ha_refresh_token" !in accepted && haRefreshToken.isNotEmpty()) {
                editor.putString("ha_refresh_token", "")
                editor.putLong("ha_token_expiry", 0L)
            }
        }
    }

    /** Update versions the user dismissed from the dashboard banner, label -> ignored latestVersion.
     *  Stored as newline-joined "label\tversion" rows (component labels + semver never contain \t or \n).
     *  Only the dashboard banner honours this — the Install tab always lists every available update. The
     *  suppression is version-exact ([UpdateChecker.visible]), so a newer release re-surfaces the banner. */
    val ignoredUpdates: Map<String, String>
        get() = prefs.getString("update_ignored", "")!!.lineSequence()
            .mapNotNull { row -> row.indexOf('\t').let { i -> if (i <= 0) null else row.substring(0, i) to row.substring(i + 1) } }
            .toMap()

    /** Record that [label]'s update to [version] was dismissed (merges with any existing ignores). */
    fun ignoreUpdate(label: String, version: String) {
        val raw = ignoredUpdates.toMutableMap().apply { put(label, version) }
            .entries.joinToString("\n") { "${it.key}\t${it.value}" }
        edit { putString("update_ignored", raw) }
    }

    /** Whether the Install tab may install an arbitrary user-uploaded APK over root. Default ON (the turnkey
     *  LAN-trust posture — see the Install-tab security note), but flippable per-panel so a hardened
     *  deployment can disable this high-impact, unauthenticated capability until token auth lands. */
    val apkUploadAllowed: Boolean get() = prefs.getBoolean("allow_apk_upload", true)
    fun setApkUploadAllowed(on: Boolean) { edit { putBoolean("allow_apk_upload", on) } }

    /**
     * HA device display name (`device.name` in discovery). Defaults to the device's own name —
     * the same source the HA Companion app uses for its default device name. (ha-paneld can't read
     * the Companion app's private device_id across the Android sandbox, so it mirrors the heuristic
     * rather than the exact registration id.)
     */
    val friendlyName: String
        get() = stringPref("friendly_name").takeIf { it.isNotBlank() } ?: deviceName()

    /** The name the owner set, or null while [friendlyName] is only the device's own name or the model. */
    val chosenFriendlyName: String?
        get() = prefs.getString("friendly_name", null)?.takeIf { it.isNotBlank() }
    /**
     * Settings.Secure.ANDROID_ID, reported as the Home Assistant device `serial_number`.
     *
     * NOT an identity key, and never again one. Panels flashed from a single factory image share the
     * Android 8.1 SSAID seed, so this value is duplicated across a whole fleet (#155). The panel's
     * identity is [deviceUid].
     */
    val androidId: String
        get() = Settings.Secure.getString(requireNotNull(contentResolver), Settings.Secure.ANDROID_ID) ?: ""

    /**
     * This installation's own device identity: minted once, at random, derived from nothing.
     *
     * Home Assistant merges two devices whenever ANY identifier matches, so an identity that is not
     * unique silently collapses distinct panels into one device — losing per-panel areas, device pages
     * and device automations, and naming one panel's entities after another. [androidId] is exactly
     * such a value on a cloned factory image, which is what #155 reports.
     *
     * Minted rather than read from the hardware because no readable hardware value is dependable
     * across this project's panels: `ro.serialno` needs shell or privileged access that not every
     * panel grants, and its uniqueness on the affected images is unproven.
     *
     * Deliberately NOT a [SettingsRegistry] spec, which is what keeps it out of the settings export
     * and out of a restore: `projectConfigSnapshot` only ever writes registered specs, and
     * `planRestoreSettings` refuses an unregistered key outright. That exclusion cannot be delegated
     * to an archive's identity claim: a settings import must never replace the receiving installation.
     *
     * Blank until [ensureDeviceUid] has durably committed the identity.
     */
    val deviceUid: String
        get() = prefs.getString(DEVICE_UID_PREF, null)?.takeIf { it.isNotBlank() } ?: ""

    /** Retains a minted candidate after a failed write; startup retries must commit it before advertising. */
    @Volatile private var runtimeDeviceUid: String? = null

    /**
     * Materialize [deviceUid] once. Runs before [ensurePanelId], because a generated panel id takes its
     * suffix from the minted identity, and before mDNS or the Panel Assistant session read either.
     */
    internal fun ensureDeviceUid(mint: () -> String = ::mintDeviceUid): String = synchronized(CONFIG_LOCK) {
        prefs.getString(DEVICE_UID_PREF, null)?.takeIf { it.isNotBlank() }?.let { return@synchronized it }
        val minted = (runtimeDeviceUid ?: mint()).takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("minted device id is blank")
        runtimeDeviceUid = minted
        val committed = durableCommit { putString(DEVICE_UID_PREF, minted) }
        if (!committed) {
            throw IllegalStateException("could not persist installation identity")
        }
        minted
    }

    /** The device's configured name (Companion's default-name source), else the model. */
    private fun deviceName(): String =
        (Settings.Global.getString(requireNotNull(contentResolver), Settings.Global.DEVICE_NAME)
            ?: Build.MODEL).ifBlank { Build.MODEL }

    /** Dashboard renderer selection. Empty means the automatic built-in renderer. */
    val dashboardPackage: String get() = stringPref("dashboard_package")

    fun setDashboardPackage(pkg: String) {
        require(specOf("dashboard_package").validate(pkg) is Validation.Ok) { "invalid dashboard package" }
        val changed = pkg != dashboardPackage
        edit {
            putString("dashboard_package", pkg)
            if (changed) putBoolean("renderer_launch_pending", true)
        }
    }

    /** Durable handoff between a renderer config commit and its required launch side-effect. */
    val rendererLaunchPending: Boolean get() = prefs.getBoolean("renderer_launch_pending", false)

    /** Clear the handoff only after the configured renderer has been launched. */
    fun completeRendererLaunch(): Boolean = applyBatch {
        edit { putBoolean("renderer_launch_pending", false) }
    }

    /** Dark mode for panels WITHOUT a system dark/light setting (Android 9-; default true — wall panels
     *  live in dark rooms): themes ha-paneld's own native activities (DayNight) and sets the built-in
     *  renderer's dashboard default. Android 10+ panels follow the OS setting instead (the Display-card
     *  toggle is hidden there); the `:8888` web UI always follows the viewing browser's preference. */
    val darkMode: Boolean get() = boolPref("dark_mode")

    /** Launcher package the Launcher button brings forward. Empty => auto-pick a non-default home. */
    val launcherPackage: String get() = stringPref("launcher_package")
    fun setLauncherPackage(pkg: String) {
        edit { putString("launcher_package", pkg) }
    }

    /** Hand-back: drop the tame blocklist and the launcher choice in one durable write; false publishes neither. */
    internal fun clearHandBackDesiredState(): Boolean = applyBatch {
        setTameVendorPackages("")
        setLauncherPackage("")
    }

    /**
     * Opt-in blocklist of intrusive vendor packages to **tame** on boot (force-stop + disable the
     * boot-relaunch + strip the floating-overlay permission). The non-empty list IS the opt-in — nothing
     * is ever touched unless the user deliberately adds a package here; the default is empty, so a stock
     * panel is never modified. A confirmed example HA users add is `com.eWeLinkControlPanel` (the
     * eWeLink/Sonoff control-panel app that relaunches on boot and draws a widget over the dashboard).
     * Critical system packages are refused regardless (see [control.TameController] + the daemon backstop).
     */
    val tameVendorPackages: List<String>
        get() = stringPref("tame_vendor_packages")
            .split(Regex("[\\s,]+")).map { it.trim() }.filter { it.isNotEmpty() }
    /** Raw user-set value (whitespace/comma-separated) — for the Configure form's input value. */
    val tameVendorPackagesRaw: String get() = stringPref("tame_vendor_packages")
    /** Stores [raw] through the registry spec, so it is normalized and refused exactly as an import would
     *  be; an invalid selection throws [IllegalArgumentException] and leaves the stored one unchanged. */
    fun setTameVendorPackages(raw: String) = setRaw(specOf("tame_vendor_packages"), raw)

    /**
     * Extra hostnames allowed in the HTTP `Host` header — the anti-DNS-rebinding allowlist for the
     * `:8888` guard ([http.OriginGuard.hostAllowed]). The guard always allows IP literals, `localhost`,
     * and `*.local` (mDNS), which covers reaching a panel by IP or its mDNS name, so this is only needed
     * when a panel is fronted by a **custom DNS name** (e.g. `device-a.example.lan`). Set it via the
     * always-allowed IP path (`POST /config` to `http://<ip>:8888`) or provisioning — never a lockout.
     * Whitespace/comma-separated; matched case-insensitively; default empty.
     */
    val httpAllowedHosts: Set<String>
        get() = prefs.getString("http_allowed_hosts", "")!!
            .split(Regex("[\\s,]+")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    /** Raw user-set value — for the Configure form's input value. */
    val httpAllowedHostsRaw: String get() = prefs.getString("http_allowed_hosts", "")!!
    fun setHttpAllowedHosts(raw: String) {
        edit { putString("http_allowed_hosts", raw.trim()) }
    }

    // Wake locally after the learner confirms a deliberate far-to-near-to-far gesture.
    // Opt-in where a proximity sensor exists; touch-to-wake remains available until explicitly enabled.
    val wakeOnWave: Boolean get() = boolPref("wake_on_wave")
    val wakeOnWaveGeneration: Long get() = wakeOnWaveEpoch.get()
    fun setWakeOnWave(on: Boolean) {
        edit { putBoolean("wake_on_wave", on) }
        wakeOnWaveEpoch.incrementAndGet()
    }

    // Prevent the vendor firmware idle-dimming the backlight at the screen-off timeout (it drops the
    // hardware backlight to ~1% after the timeout even while the OS keeps the screen on). On by default —
    // these are mains-powered wall panels; turn it off to restore the firmware's own dimming behaviour.
    val preventIdleDim: Boolean get() = boolPref("prevent_idle_dim")
    fun setPreventIdleDim(on: Boolean) {
        edit { putBoolean("prevent_idle_dim", on) }
    }

    // Hold a partial wakelock so the SoC + network never suspend (screen still free to sleep). ON by
    // default: a mains wall panel must never Doze into an unreachable state where its Home Assistant
    // connection freezes and dies half-open. Toggle off only for a battery panel.
    val keepAwake: Boolean get() = boolPref("keep_awake")
    fun setKeepAwake(on: Boolean) {
        edit { putBoolean("keep_awake", on) }
    }

    // App watchdog: poll the dashboard app and self-heal it — relaunch if its process dies, and return
    // to it if it's been backgrounded too long. Opt-in (off by default): a stock panel never auto-acts.
    val watchdogEnabled: Boolean get() = boolPref("watchdog_enabled")
    fun commitWatchdogEnabled(on: Boolean): Boolean = commitRaw(specOf("watchdog_enabled"), on.toString())

    // Touch sound: an audible tap, owned as persisted intent rather than read back from the platform.
    //
    // The Android `SOUND_EFFECTS_ENABLED` global has other writers — firmware and any vendor app holding
    // `WRITE_SETTINGS` — so a value read from it drifts with no user action, and a reporting surface that
    // followed it turned every unrelated Configure save into a touch-sound edit that queued an apply
    // nobody requested. This is now the sole authority every surface reports; the platform flag is only
    // ever actuated.
    val touchSound: Boolean get() = boolPref("touch_sound")

    /**
     * The recorded intent, or null when this store has never held one.
     *
     * `contains` is the migration marker, exactly as it is for the schema-6 response key: it is the one
     * thing that separates a value somebody chose from the registry default standing in for one. Startup
     * resolves the null case once and commits the answer, after which this can never be null again and
     * the panel's reported touch sound is immune to anything that happens to the platform flag.
     */
    val touchSoundIntent: Boolean? get() = if (prefs.contains("touch_sound")) touchSound else null

    /** Durably record the touch-sound intent. Persist-before-actuation, like every other live setting. */
    fun commitTouchSound(on: Boolean): Boolean = commitRaw(specOf("touch_sound"), on.toString())

    // Camera trial: the master switch and the hard caps that clamp every stream/snapshot request. The
    // HTTP config route persists all five through the registry writer; only the master switch needs a
    // typed commit, used by the Panel Assistant command before it is acknowledged.
    val cameraEnabled: Boolean get() = boolPref("camera_enabled")
    fun commitCameraEnabled(on: Boolean): Boolean = commitRaw(specOf("camera_enabled"), on.toString())
    val cameraResolution: CameraResolution
        get() = CameraResolution.parse(enumPref("camera_resolution")) ?: CameraResolution.P720
    val cameraFps: Int get() = intPrefInRange("camera_fps")
    val cameraKbps: Int get() = intPrefInRange("camera_kbps")

    /** Exposure bias in stops; the owner clamps it to whatever range the sensor advertises. */
    val cameraExposureEv: Float get() = floatPref("camera_exposure").coerceIn(-2f, 2f)

    // Experimental Android dashboard lock: hide system bars and return from other apps/Recents so a casual
    // user does not remain away from the dashboard. Persisted, off by default, with recovery routes and a
    // startup escape window before enforcement is reasserted (see KioskController and PaneldService).
    val kioskLock: Boolean get() = boolPref("kiosk_lock")
    fun commitKioskLock(on: Boolean): Boolean = synchronized(CONFIG_LOCK) {
        durableCommit { putBoolean("kiosk_lock", on) }
    }

    // Voice assistant: on-panel wake-word listener + Home Assistant Assist pipeline routing. Off by
    // default (no microphone-capable panel exists yet); the actual pipeline runtime is a separate lane.
    // A durable commit (not the fire-and-forget async `edit`) — the bridge's handleVoiceEnabled must be
    // able to tell a genuine persistence failure apart from success, the same reason kiosk_lock is written
    // through commitKioskLock, so an ON acknowledgement is never reported before it durably held.
    val voiceEnabled: Boolean get() = boolPref("voice_enabled")
    fun commitVoiceEnabled(on: Boolean): Boolean = synchronized(CONFIG_LOCK) {
        durableCommit { putBoolean("voice_enabled", on) }
    }

    /** JSON array of configured wake-word model ids — SettingsRegistry validates and canonicalizes it
     *  before it is ever committed here, so a stored value is always well-formed. */
    val voiceWakeWords: String get() = stringPref("voice_wake_words")

    /** JSON object of wake-word model id to Home Assistant Assist pipeline id (blank = preferred). */
    val voicePipelines: String get() = stringPref("voice_pipelines")

    val voiceAudioSource: String get() = stringPref("voice_audio_source")

    val voiceSensitivity: String get() = stringPref("voice_sensitivity")

    val voiceMicGainDb: Int get() = intPrefInRange("voice_mic_gain_db")

    val voiceStopWord: Boolean get() = boolPref("voice_stop_word")

    /** Device-local acknowledgement of the built-in launch screen for one exact app version. This is
     * outside SettingsRegistry, so config export/import cannot suppress an intro on another panel. */
    val lastLaunchScreenVersionCode: Long?
        get() = if (prefs.contains(LAST_LAUNCH_SCREEN_VERSION_PREF)) {
            prefs.getLong(LAST_LAUNCH_SCREEN_VERSION_PREF, Long.MIN_VALUE)
        } else {
            null
        }

    /** Commit only after MainActivity has actually selected and installed the intro view. */
    fun commitLaunchScreenVersionShown(versionCode: Long): Boolean = synchronized(CONFIG_LOCK) {
        durableCommit { putLong(LAST_LAUNCH_SCREEN_VERSION_PREF, versionCode) }
    }

    /** Device-local suppression for one exact healthy manual-only power caution. This deliberately sits
     * outside SettingsRegistry, so exports/imports cannot transfer a hardware acknowledgement. */
    val powerSafetyAcknowledgementFingerprint: String
        get() = deviceStatePrefs.getString(POWER_SAFETY_ACKNOWLEDGEMENT_PREF, "").orEmpty()

    fun commitPowerSafetyAcknowledgement(fingerprint: String): Boolean = synchronized(CONFIG_LOCK) {
        if (!fingerprint.matches(Regex("[0-9a-f]{64}"))) return false
        (deviceStatePrefs as? DurableVisibilityPreferences)?.commitWithDurableVisibility {
            putString(POWER_SAFETY_ACKNOWLEDGEMENT_PREF, fingerprint)
        } ?: deviceStatePrefs.edit().putString(POWER_SAFETY_ACKNOWLEDGEMENT_PREF, fingerprint).commit()
    }

    /** Language used by ha-paneld's own interface; `auto` delegates to the locale resolver. */
    val uiLanguage: String get() = stringPref("ui_language")
    // Network-adb persist INTENT (the switch). ha-paneld re-asserts adb-tcp at boot/reconnect when this
    // is true (some firmwares strip persist.adb.tcp.port at boot), and only tears adb down on OFF if it
    // was ha-paneld that turned it on — never disabling adb another mechanism started.
    val networkAdbEnabled: Boolean
        get() = boolPref("network_adb")
    fun setNetworkAdbEnabled(on: Boolean): Boolean = synchronized(CONFIG_LOCK) {
        if (on && prefs.getBoolean(SECURITY_MODE_PREF, false)) return@synchronized false
        durableCommit { putBoolean("network_adb", on) }
    }

    // The last catalog targets (UpdateChecker.persistableTargets), kept only so a restart reports them.
    val softwareUpdateTargets: Pair<String, String> get() =
        (prefs.getString("update_target_paneld", "") ?: "") to (prefs.getString("update_target_companion", "") ?: "")
    fun setSoftwareUpdateTargets(targets: Pair<String, String>) {
        if (targets == softwareUpdateTargets) return
        prefs.edit()
            .putString("update_target_paneld", targets.first)
            .putString("update_target_companion", targets.second)
            .apply()
    }
    /** An accepted session, an enrollment refusal or a setup handover proves Panel Assistant manages this panel. */
    val panelAssistantConnectionSeen: Boolean
        get() = haSetupHandover || prefs.getBoolean(PANEL_ASSISTANT_CONNECTION_SEEN_PREF, false)

    fun markPanelAssistantConnected() {
        prefs.edit().putBoolean(PANEL_ASSISTANT_CONNECTION_SEEN_PREF, true).apply()
    }

    /**
     * Whether the `:8888` listener serves only the stub (protocol section 20.8): true once a hello from the
     * bound Panel Assistant granted `rest_retired`, its promise that it makes no REST call to this panel. It
     * persists through outages and restarts; a later accepted hello that does not grant it reopens the REST routes,
     * and forgetting the Home Assistant binding reopens them until the new binding grants it.
     */
    val uiRoutesClosed: Boolean get() = prefs.getBoolean(UI_ROUTES_CLOSED_PREF, false)

    /** Record the capabilities an accepted hello granted; the one writer of [uiRoutesClosed]. */
    fun recordPanelAssistantGrant(capabilities: Collection<String>): Boolean {
        val closed = io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol.CAPABILITY_REST_RETIRED in capabilities
        if (closed == uiRoutesClosed) return true
        return durableCommit { putBoolean(UI_ROUTES_CLOSED_PREF, closed) }
    }

    // Per-panel intended "home" dashboard path (e.g. "/lovelace/0"). When set, a reload keeps the hard
    // restart but re-navigates HERE once the frontend is back up, instead of leaving the Companion on its
    // user-default view. Empty = keep current behaviour (cold-start to the Companion default).
    /**
     * The panel's REQUESTED Home Assistant area, by name. Home Assistant's value is canonical over
     * ADOPTED values, but a person's explicit choice is a deliberate local override that adoption must
     * not undo — a panel whose own HA area holds no motion entities may have its area
     * deliberately set to a neighbouring room for auto-sleep sources. [haAreaUserOverride]
     * records which kind of value this is. Setter is a plain pref write on purpose — adoption must not
     * re-trigger the write-back side effect.
     */
    var haArea: String
        get() = synchronized(CONFIG_LOCK) {
            val stored = stringPref("ha_area")
            val invalid = isLiteralNullAreaName(stored)
            if (invalid) editCommit {
                putString("ha_area", "")
                putBoolean(HA_AREA_USER_OVERRIDE_PREF, false)
            }
            if (invalid) "" else stored
        }
        set(value) = synchronized(CONFIG_LOCK) {
            durableCommit { putString("ha_area", normalizedHaAreaName(value)) }
            Unit
        }

    /**
     * True while [haArea] holds a value a PERSON chose, as opposed to one adopted from Home Assistant.
     * Set when a user saves a non-blank area, cleared when they save blank ("follow Home Assistant") and
     * when Home Assistant comes to agree — an override that matches HA is no longer overriding anything.
     * Without this bit the two kinds of value are indistinguishable, and the convergence pass reverted
     * every deliberate divergence seconds after it was saved.
     */
    var haAreaUserOverride: Boolean
        get() = synchronized(CONFIG_LOCK) {
            haArea // Repair a legacy literal and its stale override before either can be reported.
            prefs.getBoolean(HA_AREA_USER_OVERRIDE_PREF, false)
        }
        set(value) = synchronized(CONFIG_LOCK) {
            durableCommit { putBoolean(HA_AREA_USER_OVERRIDE_PREF, value) }
            Unit
        }

    /** Persist the requested Area and its ownership bit as one live-setting authority. */
    internal fun commitHaArea(value: String, userOverride: Boolean): Boolean = synchronized(CONFIG_LOCK) {
        val normalized = normalizedHaAreaName(value)
        durableCommit {
            putString("ha_area", normalized)
            putBoolean(HA_AREA_USER_OVERRIDE_PREF, userOverride && normalized.isNotBlank())
        }
    }

    val homeDashboard: String get() = stringPref("home_dashboard")
    fun setHomeDashboard(p: String) {
        edit {
            val normalized = p.trim()
            stageHomeDashboard(this, normalized, homeDashboard)
        }
    }

    private fun stageHomeDashboard(editor: SharedPreferences.Editor, normalized: String, previous: String) {
        editor.putString("home_dashboard", normalized)
        val nextPath = dashboardEntityScopePath(normalized)
        if (nextPath != "/" && nextPath != dashboardEntityScopePath(previous)) {
            editor.putString("dashboard_entity_dashboard_path", nextPath)
        }
    }

    /** Built-in renderer: minutes of no touch before it navigates back to [homeDashboard] (0 = off). */
    val dashboardIdleReturnMin: Int get() = intPrefInRange("dashboard_idle_return_min")

    /** Built-in renderer: hide the system status + navigation bars (immersive edge-to-edge kiosk).
     *  Swipe-from-edge still transiently reveals them, so an admin is never locked out. */
    val dashboardFullscreen: Boolean get() = boolPref("dashboard_fullscreen")

    /** Visibility of the panel's dashboard network warning; diagnostic checks stay independent. */
    val dashboardNetworkWarning: Boolean get() = boolPref("dashboard_network_warning")

    /** Ask Home Assistant's own frontend to enter its native kiosk mode. This is independent of
     * Android fullscreen/dashboard lock and does not inject CSS into the dashboard. */
    val dashboardNativeKiosk: Boolean get() = boolPref("dashboard_native_kiosk")

    /** Built-in renderer: who owns the dashboard's light/dark choice — [DashboardTheme.FOLLOW] (Home
     *  Assistant does, which is what [darkMode] supplies a default for) or an explicit Dark/Light this
     *  panel imposes. A stored value this build no longer declares resolves to the profile's default rather
     *  than leaving the renderer with a policy it cannot act on. */
    val dashboardTheme: String get() = enumPref("dashboard_theme")

    /** The policy the renderer and every themed screen act on: [dashboardTheme] with Ambient resolved
     *  to the room's verdict, and to Follow while auto-brightness (the model that produces it) is off.
     *  See [DashboardTheme.effective]. */
    val dashboardThemeEffective: String
        get() = DashboardTheme.effective(dashboardTheme, dashboardAmbientDark, ambientModelRunning = autoBrightness)

    /** The Ambient theme's last verdict from the room's light (runtime state, not a setting: it
     *  describes this room, so it never travels in an export). Null until the model has given one. */
    val dashboardAmbientDark: Boolean?
        get() = if (prefs.contains("dashboard_theme_ambient_dark")) prefs.getBoolean("dashboard_theme_ambient_dark", true) else null
    fun setDashboardAmbientDark(dark: Boolean) {
        prefs.edit().putBoolean("dashboard_theme_ambient_dark", dark).apply()
    }

    /** Built-in renderer: dashboard page zoom %. 100 matches the HA Companion's default sizing (which
     *  scales the page by device density), so a switched-over panel keeps its layout. */
    val dashboardZoom: Int get() = intPref("dashboard_zoom")
    fun setDashboardZoom(pct: Int) { edit { putInt("dashboard_zoom", pct) } }

    /** Exact owner-scoped filter state for full backup and local config revisions. Derived SQLite
     * evidence remains rebuildable and is intentionally excluded. */
    internal fun dashboardEntityBackupState(): DashboardEntityBackupState = synchronized(CONFIG_LOCK) {
        val all = prefs.all
        fun string(key: String) = all[key] as? String ?: ""
        DashboardEntityBackupState(
            instanceKey = string("dashboard_entity_instance"),
            instanceOrigin = string("dashboard_entity_instance_origin"),
            instanceUuid = string("dashboard_entity_instance_uuid"),
            dashboardPath = string("dashboard_entity_dashboard_path"),
            filterIds = string("dashboard_entity_filter_ids"),
            filterEnabled = all["dashboard_entity_filter_enabled"] as? Boolean ?: false,
            filterOwner = string("dashboard_entity_filter_instance"),
            learningApplied = all["dashboard_entity_learning_applied"] as? Boolean ?: false,
            appliedOwner = string("dashboard_entity_applied_instance"),
            overrides = string("dashboard_entity_overrides"),
            overrideOwner = string("dashboard_entity_override_instance"),
            initialActivationPending = all[DASHBOARD_ENTITY_INITIAL_ACTIVATION_PENDING_KEY] as? Boolean ?: false,
        )
    }

    /** Stage a prevalidated backup snapshot into the caller's existing config transaction. */
    internal fun stageDashboardEntityBackupState(
        editor: SharedPreferences.Editor,
        state: DashboardEntityBackupState,
    ) {
        editor.putString("dashboard_entity_instance", state.instanceKey)
            .putString("dashboard_entity_instance_origin", state.instanceOrigin)
            .putString("dashboard_entity_instance_uuid", state.instanceUuid)
            .putString("dashboard_entity_dashboard_path", state.dashboardPath)
            .putString("dashboard_entity_filter_ids", state.filterIds)
            .putBoolean("dashboard_entity_filter_enabled", state.filterEnabled)
            .putString("dashboard_entity_filter_instance", state.filterOwner)
            .putBoolean("dashboard_entity_learning_applied", state.learningApplied)
            .putString("dashboard_entity_applied_instance", state.appliedOwner)
            .putString("dashboard_entity_overrides", state.overrides)
            .putString("dashboard_entity_override_instance", state.overrideOwner)
            .putBoolean(DASHBOARD_ENTITY_INITIAL_ACTIVATION_PENDING_KEY, state.initialActivationPending)
    }

    /** Restore the exact owner-scoped preference state if the transactional SQLite evidence reset
     * fails. Identity fields are intentionally excluded: reset never owns or mutates the target. */
    internal fun restoreDashboardEntityEvidencePreferences(state: DashboardEntityBackupState): Boolean =
        applyBatch { edit {
            putString("dashboard_entity_filter_ids", state.filterIds)
            putBoolean("dashboard_entity_filter_enabled", state.filterEnabled)
            putString("dashboard_entity_filter_instance", state.filterOwner)
            putBoolean("dashboard_entity_learning_applied", state.learningApplied)
            putString("dashboard_entity_applied_instance", state.appliedOwner)
            putString("dashboard_entity_overrides", state.overrides)
            putString("dashboard_entity_override_instance", state.overrideOwner)
            putBoolean(DASHBOARD_ENTITY_INITIAL_ACTIVATION_PENDING_KEY, state.initialActivationPending)
        } }

    // The HA brightness level ha-paneld last wrote through a profile's setting-route backlight curve, with
    // the setting value it produced ("level:setting"). The setting alone cannot say which of the levels the
    // curve collapses together Home Assistant asked for, so this is what makes the read-back exact.
    internal val ownedBacklightLevel = object : io.panelassistant.android.control.BacklightScale.OwnedLevelStore {
        override fun load(): io.panelassistant.android.control.BacklightScale.OwnedLevel? =
            prefs.getString("owned_backlight_level", null)?.split(':')?.takeIf { it.size == 2 }?.let { (level, setting) ->
                val l = level.toIntOrNull() ?: return null
                val s = setting.toIntOrNull() ?: return null
                io.panelassistant.android.control.BacklightScale.OwnedLevel(l, s)
            }

        override fun save(owned: io.panelassistant.android.control.BacklightScale.OwnedLevel) {
            prefs.edit().putString("owned_backlight_level", "${owned.level}:${owned.setting}").apply()
        }
    }

    // The screen-off timeout (ms) seen before we first raised it, so disabling preventIdleDim can restore
    // the firmware default. -1 = not yet captured.
    var savedScreenOffTimeout: Int
        get() = prefs.getInt("saved_screen_off_timeout", -1)
        set(v) {
            prefs.edit().putInt("saved_screen_off_timeout", v).apply()
        }

    // The screen-off timeout (ms) the screen-timeout screen-off route displaced while the panel sleeps;
    // restored on wake and at the next service start. -1 = nothing displaced. Not [savedScreenOffTimeout]:
    // that one is the firmware default from before prevent-idle-dim raised it.
    var displacedScreenOffTimeout: Int
        get() = prefs.getInt("displaced_screen_off_timeout", -1)
        set(v) {
            prefs.edit().putInt("displaced_screen_off_timeout", v).commit()
        }

    // The STAY_ON_WHILE_PLUGGED_IN mask the screen-timeout route cleared while the panel sleeps (it
    // keeps Android awake whatever the timeout says); restored with [displacedScreenOffTimeout].
    // -1 = nothing displaced.
    var displacedStayOnWhilePluggedIn: Int
        get() = prefs.getInt("displaced_stay_on_while_plugged_in", -1)
        set(v) {
            prefs.edit().putInt("displaced_stay_on_while_plugged_in", v).commit()
        }

    // Silence the firmware startup chime by zeroing the ring/notification volume via Settings.System.
    // Default on: fresh panels should reboot silently; an explicit saved choice remains authoritative.
    val silenceBootChime: Boolean get() = boolPref("silence_boot_chime")
    fun setSilenceBootChime(on: Boolean) { edit { putBoolean("silence_boot_chime", on) } }

    /** Last measured DashboardActivity-start → Android default-network wait. Used only to make the
     * next boot's launch progress honest for this particular panel; zero means no learned estimate. */
    val lastNetworkWaitMs: Long get() = prefs.getLong("last_network_wait_ms", 0L)
    fun setLastNetworkWaitMs(ms: Long) {
        prefs.edit().putLong("last_network_wait_ms", ms.coerceIn(1_000L, 300_000L)).apply()
    }

    /** Last light/dark choice observed from HA's own `localStorage.selectedTheme`. Null until the
     * renderer has seen an explicit HA preference; the launch screen then follows the OS/config. */
    val dashboardThemeDark: Boolean?
        get() = if (prefs.contains("dashboard_theme_dark")) prefs.getBoolean("dashboard_theme_dark", true) else null
    fun setDashboardThemeDark(dark: Boolean) {
        prefs.edit().putBoolean("dashboard_theme_dark", dark).apply()
    }

    /** Forget the observed Home Assistant theme, because the renderer has just observed that there is
     *  no longer one to observe. Without this the key is write-only: a panel that forced Dark and then
     *  handed the choice back kept reporting the forced value to every native screen forever, which is
     *  precisely the ha-paneld-owned residue that returning to Follow is supposed to remove. */
    fun clearDashboardThemeDark() {
        if (prefs.contains("dashboard_theme_dark")) prefs.edit().remove("dashboard_theme_dark").apply()
    }

    // --- remote log shipping (opt-in) --------------------------------------------------------------
    // Forward ha-paneld's OWN process logcat (its Log.* output + the Ktor SLF4J library logs,
    // all emitted by this app's uid → readable with no READ_LOGS and no root) to a central aggregator
    // (any syslog- or HTTP-ingesting log collector) for fleet-wide debugging without per-panel `adb logcat`. OFF by default
    // with an EMPTY host — a stock panel ships nothing until deliberately configured. LAN-only by intent;
    // lines are redacted (tokens/passwords/URL secrets) before they leave the device. No on-panel UI:
    // set via the HTTP /config endpoint (provision.sh --log-* flags). See logship/LogShipper.
    val logShipEnabled: Boolean get() = boolPref("log_ship_enabled")
    /** Full Android system log is a separate opt-in; existing process-log subscriptions stay unchanged. */
    val logShipSystemEnabled: Boolean get() = boolPref("log_ship_system_enabled")
    /** Sink host (the log collector to ship to). Empty => shipping stays inert regardless of the flag. */
    val logShipHost: String get() = stringPref("log_ship_host")
    val logShipPort: Int get() = intPref("log_ship_port")
    /**
     * Transport: "syslog-tcp" (RFC5424 over a stream, the default because it reports its own failures),
     * "syslog-udp" (RFC5426, what a stock collector listens for on 514) or "http" (NDJSON POST). Normalized on read so a value
     * stored by an older build — where "syslog" meant TCP — resolves to one of the canonical three.
     */
    val logShipProtocol: String
        get() = LogShipEndpoint.protocol(
            prefs.getString("log_ship_protocol", null)
                ?: effectiveDefault(specOf("log_ship_protocol")),
        )
    fun setLogShipping(enabled: Boolean, host: String, port: Int, protocol: String) {
        // A user who types a scheme or a host:port into the host box means it, so resolve the three
        // fields together rather than storing "udp://collector" as a literal hostname.
        val endpoint = LogShipEndpoint.resolve(host, port, protocol)
        edit {
            putBoolean("log_ship_enabled", enabled)
            putString("log_ship_host", endpoint.host)
            putInt("log_ship_port", endpoint.port)
            putString("log_ship_protocol", endpoint.protocol)
        }
    }
    // Desired Zigbee-router state, persisted so the gateway can be auto-started on boot when nothing
    // else launches it (the NSPanel Pro gateway is not init-started — verified 2026-06-08). Default off.
    val zigbeeRouterEnabled: Boolean
        get() = boolPref("zigbee_router")
    // Reconcile an explicit user choice or a profile's default ON. A default OFF never claims an
    // untouched vendor gateway, so only a stored user choice may stop it at startup.
    val zigbeeRouterConfigured: Boolean
        get() = prefs.getBoolean("zigbee_router_configured", false) ||
            (!prefs.contains("zigbee_router") && effectiveDefault(specOf("zigbee_router")) == "true")
    fun setZigbeeRouterEnabled(on: Boolean) {
        edit { putBoolean("zigbee_router", on); putBoolean("zigbee_router_configured", true) }
    }

    // Optional on-panel auto-brightness engine (see control/AutoBrightnessController). Default OFF →
    // ha-paneld stays a pure brightness actuator; HA drives the screen. When ON, the engine maps a lux
    // stream (panel ALS where present, else HA-fed) to the backlight.
    val autoBrightness: Boolean get() = boolPref("auto_brightness")
    fun setAutoBrightness(on: Boolean) {
        edit { putBoolean("auto_brightness", on) }
    }

    /**
     * Percent of the deviation from the learned ambient baseline that is applied to the screen, once the
     * engine has admitted the deviation as real. Not a promise that the screen tracks raw lux: positive
     * excursions are gated by boost admission and release, and while the pattern is still being learned
     * the effective level follows the smoothed measurement regardless of this value.
     */
    val autoBrightnessResponsePercent: Int
        get() = intPrefInRange(SettingsRegistry.RESPONSE_PERCENT_KEY)
    fun setAutoBrightnessResponsePercent(value: Int) {
        edit { putInt(SettingsRegistry.RESPONSE_PERCENT_KEY, clampToSpec(SettingsRegistry.RESPONSE_PERCENT_KEY, value)) }
    }

    /** Lowest automatic target in user-facing percent; the actuator's visible floor remains absolute. */
    val autoBrightnessMinimumPercent: Int
        get() = intPrefInRange("auto_brightness_minimum_percent")
    fun setAutoBrightnessMinimumPercent(value: Int) {
        edit { putInt("auto_brightness_minimum_percent", clampToSpec("auto_brightness_minimum_percent", value)) }
    }

    /** Highest automatic target; 100 preserves the uncapped curve. Manual authority is separate. */
    val autoBrightnessMaximumPercent: Int
        get() = intPrefInRange("auto_brightness_maximum_percent")
    fun setAutoBrightnessMaximumPercent(value: Int) {
        edit { putInt("auto_brightness_maximum_percent", clampToSpec("auto_brightness_maximum_percent", value)) }
    }

    /** Exact HA illuminance entity selected instead of the local ALS; blank uses the panel sensor. */
    val autoBrightnessHaEntity: String
        get() = stringPref("auto_brightness_ha_entity")
    fun setAutoBrightnessHaEntity(entityId: String) {
        edit { putString("auto_brightness_ha_entity", entityId.trim()) }
    }

    /**
     * HA device card manufacturer/model. The OS Build props are the generic SoC platform
     * (e.g. `rockchip`/`px30_evb`), not the product, so these are configurable — set e.g.
     * "Sonoff" / "NSPanel Pro 120". Default to the generic agent identity; ha-paneld's own version
     * is reported separately as the device `sw_version`.
     */
    // The active device profile, attached once at service startup; supplies per-panel manufacturer/
    // model defaults when the user hasn't set them. Null before attach (resolution falls back to Build).
    @Volatile internal var profile: DeviceProfile? = null
        private set
    @Volatile private var proximityCalibrationPrefix: String? = null
    @Volatile private var proximityCalibrationProfilePrefix: String? = null
    fun attachProfile(p: DeviceProfile) {
        // Retain the old profile namespace only long enough for the adaptive learner to consume a
        // complete user-captured near/far pair. New proximity state is owned by the learner.
        proximityCalibrationProfilePrefix = "profile_calibration.${sanitizeProfileIdentity(p.id)}"
        proximityCalibrationPrefix = "${proximityCalibrationProfilePrefix}.${sanitizeProfileIdentity(p.revision)}"
        migrateLegacyButtonBacklight(p)
        profile = p
    }

    private fun profileStateSuffix(profile: DeviceProfile): String =
        "${sanitizeProfileIdentity(profile.id)}.${sanitizeProfileIdentity(profile.revision)}"

    private fun sanitizeProfileIdentity(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]"), "-")

    internal fun buttonBacklightKey(profile: DeviceProfile): String =
        "last_button_backlight.${profileStateSuffix(profile)}"

    private fun migrateLegacyButtonBacklight(profile: DeviceProfile) {
        if (!profile.hasButtonBacklight || !prefs.contains("last_button_backlight")) return
        val target = buttonBacklightKey(profile)
        val editor = prefs.edit()
        if (!prefs.contains(target)) editor.putInt(target, prefs.getInt("last_button_backlight", -1))
        editor.remove("last_button_backlight").apply()
    }

    /** Raw user-set values (empty if unset) — for the Configure form's input value. */
    val manufacturerRaw: String get() = stringPref("manufacturer")
    val modelRaw: String get() = stringPref("model")

    /** Resolved HA device-card manufacturer: user value → profile default → inferred from Build. */
    val manufacturer: String
        get() = manufacturerRaw.ifBlank { null }
            ?: profile?.manufacturer
            ?: Build.MANUFACTURER.replaceFirstChar { it.titlecase(Locale.ROOT) }.ifBlank { "Unknown" }

    /** Resolved HA device-card model. User value is used verbatim; otherwise the profile/inferred name
     *  gets a " (ha-paneld)" suffix so this device is distinguishable from a co-installed integration
     *  managing the same hardware (HA shows the model in the device list). */
    val model: String
        get() = modelRaw.ifBlank { null }
            ?: ((profile?.model ?: inferredModel()) + " (ha-paneld)")

    private fun inferredModel(): String =
        listOf(Build.MODEL, Build.DEVICE, Build.PRODUCT).firstOrNull { !it.isNullOrBlank() } ?: "panel"

    // --- legacy proximity calibration retirement -----------------------------------------------

    /**
     * Returns one complete user-captured near/far pair for the adaptive learner, then synchronously
     * removes every old threshold, polarity, endpoint, sensitivity, and migration key. The active
     * profile revision wins, followed by the earlier profile-id namespace and unscoped legacy state.
     * Incomplete or non-finite captures are discarded with the rest of the retired calibration state.
     */
    internal fun consumeLegacyProximityLearningSeed(): ProximityLearningSeed? = synchronized(CONFIG_LOCK) {
        val currentPrefix = proximityCalibrationPrefix
        val profilePrefix = proximityCalibrationProfilePrefix

        fun seedIn(store: SharedPreferences, prefix: String): ProximityLearningSeed? {
            val separator = if (prefix.isEmpty()) "" else "$prefix."
            val near = store.all["${separator}prox_near_raw"] as? Float ?: return null
            val far = store.all["${separator}prox_far_raw"] as? Float ?: return null
            return ProximityLearningSeed(near, far).takeIf { near.isFinite() && far.isFinite() }
        }

        fun namespacesIn(store: SharedPreferences): List<String> = buildList {
            currentPrefix?.let(::add)
            profilePrefix?.let(::add)
            if (profilePrefix != null) {
                store.all.keys.asSequence()
                    .filter { it.startsWith("$profilePrefix.") && it.endsWith(".prox_near_raw") }
                    .map { it.removeSuffix(".prox_near_raw") }
                    // A revision is one sanitized segment. This avoids borrowing a capture from a
                    // different dotted profile id that happens to extend the active profile's id.
                    .filter { !it.removePrefix("$profilePrefix.").contains('.') }
                    .sorted()
                    .forEach(::add)
            }
            add("")
        }.distinct()

        val seed = sequence {
            for (namespace in namespacesIn(calibrationPrefs)) yield(seedIn(calibrationPrefs, namespace))
            for (namespace in namespacesIn(prefs)) yield(seedIn(prefs, namespace))
        }.filterNotNull().firstOrNull()

        fun keysToRetire(store: SharedPreferences): List<String> = store.all.keys.filter { key ->
            key == PROXIMITY_PROFILE_MIGRATED || LEGACY_PROXIMITY_KEYS.any { legacy ->
                key == legacy || (key.startsWith("profile_calibration.") && key.endsWith(".$legacy"))
            }
        }

        // Clear the backup-eligible store first. If that commit fails, the isolated calibration store
        // still retains the seed for a later retry; only report consumption after both commits succeed.
        val configKeys = keysToRetire(prefs)
        val configCleared = prefs.edit().also { editor -> configKeys.forEach(editor::remove) }.commit()
        if (!configCleared) return@synchronized null
        val calibrationKeys = keysToRetire(calibrationPrefs)
        val calibrationCleared = calibrationPrefs.edit().also { editor -> calibrationKeys.forEach(editor::remove) }.commit()
        if (!calibrationCleared) return@synchronized null
        seed
    }

    /** Effective CHT8305 room-temperature calibration offset (°C): the profile's characterised self-heat
     *  baseline PLUS the user's `room_temp_offset` trim (both default 0 → no correction). Additive so either
     *  the profile or the API can move it independently, and a future schema migration writing the config
     *  default (0) can't wipe the profile baseline. */
    val roomTempOffsetC: Float
        get() = (profile?.roomTempOffsetC ?: 0f) + floatPref("room_temp_offset")

    /** Persist the user's `room_temp_offset` trim (°C), clamped to the registry range. Uses [edit] so it
     *  composes with the /config POST batch — without this the key had a [SettingSpec] and rendered a form
     *  field but no persistence path, so a posted value was silently dropped. */
    // --- last-known actuator state, re-applied/published on (re)connect so HA reflects reality ---

    /** Last navigated URL (published as the navigate state on connect; empty if never set). */
    var lastNavigate: String
        get() = prefs.getString("last_navigate", "")!!
        set(v) { prefs.edit().putString("last_navigate", v).apply() }

    /** Camera trial: true after the user declined the Android camera permission for the current enable
     *  of `camera_enabled`. Cleared by the next fresh enable. Durable across process restarts so a
     *  declined panel is not re-asked on every boot. */
    var cameraPermissionDeclined: Boolean
        get() = prefs.getBoolean("camera_permission_declined", false)
        set(v) { prefs.edit().putBoolean("camera_permission_declined", v).apply() }

    /** Last LED state packed as "on,br,r,g,b" (e.g. "1,255,255,0,0"); empty if never set. */
    var lastLed: String
        get() = prefs.getString("last_led", "")!!
        set(v) { prefs.edit().putString("last_led", v).apply() }

    /** Last active LED effect name (HA `effect_list` member, e.g. "strobe"); empty = solid colour. Kept
     *  separate from [lastLed] (which stays the base colour) so the effect resumes on reconnect/reboot. */
    var lastLedEffect: String
        get() = prefs.getString("last_led_effect", "")!!
        set(v) { prefs.edit().putString("last_led_effect", v).apply() }

    /** Last requested button-backlight level (0=off, 1..255=on, -1=never commanded/readable). */
    var lastButtonBacklight: Int
        get() = profile?.takeIf { it.hasButtonBacklight }?.let { prefs.getInt(buttonBacklightKey(it), -1) } ?: -1
        set(v) {
            profile?.takeIf { it.hasButtonBacklight }?.let {
                prefs.edit().putInt(
                    buttonBacklightKey(it),
                    v.coerceIn(-1, 255),
                ).apply()
            }
        }

    // --- registry-driven generic access (HTTP config API + bundles + revisions) -----------------
    // These read/write a setting by its [SettingSpec] so the config API, form generation, export and
    // import all go through one typed path instead of ~35 bespoke getters. Typed convenience getters
    // above remain for callers; they read the same durable keys.

    // The [SettingsRegistry] SettingSpec owns the vocabulary for every registry-backed key's default:
    // the typed convenience accessors above read their default through these helpers rather than each
    // re-declaring a literal that must be kept coherent with the spec by hand. A key with no spec is a
    // programming error (surfaces at test time), never a silent fallback to some other literal.
    internal fun specOf(key: String): SettingSpec =
        SettingsRegistry.spec(key) ?: error("no SettingSpec registered for '$key'")

    /** Default for the active profile; reads never materialize it as a user preference. */
    fun effectiveDefault(spec: SettingSpec, caps: Capabilities? = null): String {
        val declared = profile?.settingDefaults?.get(spec.key)
        return when (spec.key) {
            "navbar_mode" -> resolveNavbarMode(declared, caps ?: navbarCapabilities())
            "log_ship_protocol" -> declared ?: logShipAbsentProtocolFallback ?: spec.default
            // A store that predates the source setting keeps the HA policy it had; every later
            // auto_sleep write materialises the effective source (see [stage]).
            "auto_sleep_source" -> if (prefs.contains("auto_sleep")) "home_assistant" else declared ?: spec.default
            "network_adb" -> if (hardenedSecurityEnabled) "false" else declared ?: spec.default
            // A panel whose profile declares a microphone is a voice satellite out of the box.
            "voice_enabled" -> declared ?: if (profile?.microphoneDeclared == true) "true" else spec.default
            "auto_brightness_minimum_percent", "auto_brightness_maximum_percent" ->
                profileBrightnessDefault(spec, declared)
            else -> declared ?: spec.default
        }
    }

    /** A stored opposing bound wins over a conflicting profile range; absent keys stay unpersisted. */
    private fun profileBrightnessDefault(spec: SettingSpec, declared: String?): String {
        if (declared == null) return spec.default
        val minimum = specOf("auto_brightness_minimum_percent")
        val maximum = specOf("auto_brightness_maximum_percent")
        val bounds = listOf(minimum, maximum).associate { bound ->
            bound.key to prefs.getInt(
                bound.key, profile?.settingDefaults?.get(bound.key)?.toInt() ?: bound.default.toInt(),
            ).toString()
        }
        return if (SettingsRegistry.automaticBrightnessBoundsError(
                bounds, minimum.default.toInt(), maximum.default.toInt(),
            ) == null) declared else spec.default
    }

    internal fun boolPref(key: String): Boolean = prefs.getBoolean(key, effectiveDefault(specOf(key)).toBoolean())
    private fun intPref(key: String): Int = prefs.getInt(key, effectiveDefault(specOf(key)).toIntOrNull() ?: 0)
    internal fun longPref(key: String): Long = prefs.getLong(key, effectiveDefault(specOf(key)).toLongOrNull() ?: 0L)
    private fun floatPref(key: String): Float {
        val spec = specOf(key)
        val default = effectiveDefault(spec).toFloatOrNull() ?: 0f
        val persisted = prefs.getFloat(key, default)
        val normalized = (SettingValue.validate(spec, persisted.toString()) as? Validation.Ok)?.normalized
            ?: (SettingValue.validate(spec, default.toString()) as? Validation.Ok)?.normalized
            ?: default.toString()
        return normalized.toFloatOrNull() ?: default
    }
    internal fun stringPref(key: String): String {
        val default = effectiveDefault(specOf(key))
        return prefs.getString(key, default) ?: default
    }
    /** An ENUM setting as one of its declared options: a stored value this build does not declare reads as
     *  the active profile's default, through the same validator every write uses. */
    internal fun enumPref(key: String): String {
        val spec = specOf(key)
        fun declared(raw: String?) = raw?.let { (SettingValue.validate(spec, it) as? Validation.Ok)?.normalized }
        return declared(prefs.getString(key, null)) ?: declared(effectiveDefault(spec)) ?: spec.default
    }
    /** An INT setting clamped to its spec's range, so the range is declared once, in the registry. */
    internal fun intPrefInRange(key: String): Int = clampToSpec(key, intPref(key))
    private fun clampToSpec(key: String, value: Int): Int = specOf(key).let { spec ->
        value.coerceIn(requireNotNull(spec.min).toInt(), requireNotNull(spec.max).toInt())
    }

    /** Current raw string value for a registry key (the active profile's default if unset). */
    fun getRaw(spec: SettingSpec): String = when (spec.type) {
        SettingType.BOOL -> boolPref(spec.key).toString()
        SettingType.INT -> intPref(spec.key).toString()
        SettingType.LONG -> longPref(spec.key).toString()
        SettingType.FLOAT -> {
            // Legacy stores and restored databases can bypass current write admission. [floatPref]
            // gives both typed runtime accessors and this raw projection the same validated effective
            // value; read-back deliberately does not mutate persistence.
            (SettingValue.validate(spec, floatPref(spec.key).toString()) as Validation.Ok).normalized
        }
        else -> when (spec.key) {
            "ha_area" -> haArea
            "auto_sleep_source" -> autoSleepSource
            "navbar_mode" -> navbarMode
            // A pre-UDP panel has the retired "syslog" spelling on disk. Canonicalize here so the
            // Configure select, the export bundle and the revision diff all see a declared option.
            "log_ship_protocol" -> logShipProtocol
            // Same reason: an alias spelling ("follow") or a value from a build that declared a
            // different option set must present as one of THIS build's declared options.
            "dashboard_theme" -> dashboardTheme
            "camera_resolution" -> cameraResolution.wire
            else -> stringPref(spec.key)
        }
    }

    /** Stage a typed write into [editor] (no commit) — used by the transactional bundle import. */
    fun stage(editor: SharedPreferences.Editor, spec: SettingSpec, normalized: String) {
        if (spec.key in setOf("ha_url", "ha_token", "ha_refresh_token", "ha_client_id") && normalized != getRaw(spec)) {
            forgetHaRoutes(editor)
        }
        if (spec.key == "auto_sleep_source" && normalized != autoSleepSource)
            editor.putLong(AUTO_SLEEP_GENERATION_PREF, nextAutoSleepGenerationLocked())
        // Storing auto_sleep would turn an absent source into the legacy "home_assistant", so the
        // first write pins the source the panel was effectively using.
        if (spec.key == "auto_sleep" && !prefs.contains("auto_sleep_source"))
            editor.putString("auto_sleep_source", autoSleepSource)
        when (spec.type) {
            SettingType.BOOL -> editor.putBoolean(spec.key, normalized.toBoolean())
            SettingType.INT -> editor.putInt(spec.key, normalized.toIntOrNull() ?: 0)
            SettingType.LONG -> editor.putLong(spec.key, normalized.toLongOrNull() ?: 0L)
            SettingType.FLOAT -> editor.putFloat(spec.key, normalized.toFloatOrNull() ?: 0f)
            else -> editor.putString(spec.key, normalized)
        }
    }

    /** Stage one already-normalized registry value through the same batch-aware writer as bespoke
     * Config setters. Direct HTTP uses this as its catalogue-wide persistence floor; specialized
     * setters still add their secondary-key and post-commit semantics in the same transaction. */
    internal fun setRaw(spec: SettingSpec, normalized: String) {
        val value = (SettingValue.validate(spec, normalized) as? Validation.Ok)?.normalized
            ?: throw IllegalArgumentException("${spec.key}: invalid normalized value")
        edit { stage(this, spec, value) }
    }

    /** Synchronously persist one validated registry value before a low-frequency live actuator is
     * acknowledged. Transient inputs and publish-only sensors have no durable configuration value. */
    internal fun commitRaw(spec: SettingSpec, normalized: String): Boolean = synchronized(CONFIG_LOCK) {
        if (spec.transient || spec.readOnly) return false
        val value = (SettingValue.validate(spec, normalized) as? Validation.Ok)?.normalized ?: return false
        val autoSleepChanged = spec.key == "auto_sleep" && boolPref("auto_sleep") != value.toBoolean()
        val committed = durableCommit {
            when (spec.key) {
                "home_dashboard" -> stageHomeDashboard(this, value, homeDashboard)
                "ha_area" -> {
                    stage(this, spec, value)
                    putBoolean(HA_AREA_USER_OVERRIDE_PREF, value.isNotBlank())
                }
                // A committed choice is the owner's, so the router is reconciled to it from the same commit.
                "zigbee_router" -> {
                    stage(this, spec, value)
                    putBoolean("zigbee_router_configured", true)
                }
                else -> stage(this, spec, value)
            }
            if (autoSleepChanged) putLong(AUTO_SLEEP_GENERATION_PREF, nextAutoSleepGenerationLocked())
        }
        if (committed && spec.key == "wake_on_wave") wakeOnWaveEpoch.incrementAndGet()
        committed
    }

    /** A new editor for staging a batch of registry writes. SQLite-backed commits publish only once
     * the complete candidate is durable, matching [applyBatch] and one-value live-setting commits. */
    fun editor(): SharedPreferences.Editor =
        if (prefs is DurableVisibilityPreferences) DurableStagingEditor() else prefs.edit()

    /** Commit a caller-staged transaction under the same lock as grouped credential mutation and OAuth
     * refresh ownership checks. */
    internal fun commit(
        editor: SharedPreferences.Editor,
        afterCommit: () -> Unit = {},
    ): Boolean = synchronized(CONFIG_LOCK) {
        editor.commit().also { committed -> if (committed) afterCommit() }
    }

    private inner class DurableStagingEditor : SharedPreferences.Editor {
        private val operations = mutableListOf<(SharedPreferences.Editor) -> Unit>()

        override fun putString(key: String, value: String?) = add { it.putString(key, value) }
        override fun putStringSet(key: String, values: Set<String>?) =
            add { it.putStringSet(key, values?.toSet()) }
        override fun putInt(key: String, value: Int) = add { it.putInt(key, value) }
        override fun putLong(key: String, value: Long) = add { it.putLong(key, value) }
        override fun putFloat(key: String, value: Float) = add { it.putFloat(key, value) }
        override fun putBoolean(key: String, value: Boolean) = add { it.putBoolean(key, value) }
        override fun remove(key: String) = add { it.remove(key) }
        override fun clear() = add { it.clear() }

        override fun commit(): Boolean = synchronized(CONFIG_LOCK) {
            durableCommit { operations.forEach { operation -> operation(this) } }
        }

        override fun apply() {
            commit()
        }

        private fun add(operation: (SharedPreferences.Editor) -> Unit): SharedPreferences.Editor = apply {
            operations += operation
        }
    }

    /** Schema the live store was last written at. Absent → 1 (the original shape, before this tracking),
     *  so an app upgrade that bumps [SettingsRegistry.SCHEMA] triggers a one-time on-device migration. */
    private val storedSchema: Int get() = prefs.getInt("config_schema", 1)

    /**
     * Migrate the live config store to the current [SettingsRegistry.SCHEMA] when it was
     * written by an older shape, using the same [Migrations] chain as bundle import. When a key is renamed
     * or retyped its persisted value must be carried forward, not silently reset to its default.
     * A current store is a no-op unless it contains retired records from a legacy restore; call once at
     * startup before the store is read. Committed synchronously so a reboot can't race the write.
     */
    /**
     * Re-root the stored entity-filter owners onto the dashboard root.
     *
     * The owner key used to embed the whole configured route and now embeds only its root, so a panel
     * that stored `…:key/lovelace/kiosk` computes `…:key/lovelace` after an upgrade, the ownership test
     * fails, and the filter reads as unowned. The panel then opens Home Assistant with no interception
     * and subscribes to the entire catalogue — the load the filter exists to prevent.
     *
     * The binding path already re-roots owners, but every route to it runs through entity learning, and
     * committing a MANUAL filter sets `dashboard_entity_learning` false. So the repair could never run
     * on exactly the panels that needed it.
     *
     * Shortening a stored owner to its own root is lossless and cannot adopt a different dashboard:
     * learning fetches per dashboard root and extracts from the whole document, so a view and its
     * dashboard yield the same set. A different root is left alone, because that is a genuine dashboard
     * change and failing closed so it re-learns is the intended behaviour. Idempotent, so it runs on
     * every start rather than only on a schema change — a panel that already booted an affected build
     * is repaired too.
     */
    private fun rerootDashboardEntityOwners() = synchronized(CONFIG_LOCK) {
        val editor = prefs.edit()
        var changed = false
        for (preference in listOf("dashboard_entity_filter_instance", "dashboard_entity_applied_instance")) {
            val owner = prefs.getString(preference, "").orEmpty()
            if (owner.isBlank()) continue
            val instance = dashboardEntityInstanceOf(owner)
            if (instance.isBlank()) continue
            val rerooted = dashboardEntityTargetKey(instance, dashboardEntityPathOf(owner))
            if (rerooted.isBlank() || rerooted == owner) continue
            editor.putString(preference, rerooted)
            changed = true
        }
        if (changed) editor.apply()
    }

    /**
     * Canonicalize a `home_dashboard` stored before the setting had a validator.
     *
     * v0.9.6 stored whatever it was given, so an upgraded panel can hold `https://ha.example/lovelace/0`.
     * The renderer canonicalizes as it reads and never noticed, but the Companion reload path now uses the
     * stored value verbatim, on the premise that it is canonical by construction — true of anything this
     * build wrote, false of anything an earlier one did. It then builds `homeassistant://navigate` plus a
     * whole URL, and the reload lands nowhere.
     *
     * Applies exactly the rule the validator applies, so a stored value becomes what saving it today would
     * have produced. A value that cannot be canonicalized is left alone: it is no worse than it already
     * is, and silently rewriting it would change which dashboard the panel opens.
     */
    private fun canonicalizeStoredHomeDashboard() = synchronized(CONFIG_LOCK) {
        val stored = prefs.getString("home_dashboard", "").orEmpty()
        if (stored.isNotBlank()) {
            // An absolute route has to lose its origin before the path rule can read it — the canonicalizer
            // refuses a URL scheme outright, which is correct for a WRITE and useless here, where the value
            // is already stored and the alternative is leaving it broken. Only this panel's own Home
            // Assistant is stripped. A route naming a DIFFERENT origin is left exactly as it is: rewriting
            // it would silently retarget the panel at a dashboard on another server.
            val candidate = sameOriginDashboardRoute(stored) ?: stored
            // The spec's own rule, without the write-time length gate: a stored value is repaired, not refused.
            val canonical = (specOf("home_dashboard").validate(candidate) as? Validation.Ok)?.normalized
            if (canonical != null && canonical != stored) {
                prefs.edit().putString("home_dashboard", canonical).apply()
            }
        }
    }

    private fun sameOriginDashboardRoute(raw: String): String? =
        sameOriginDashboardRoute(raw, canonicalHaOrigin(haUrl))

    /**
     * Remove [retired] keys, among them the legacy broker address and credentials. A panel whose last session
     * was accepted, or whose integration claimed its update entity, keeps that as connection evidence.
     */
    private fun SharedPreferences.Editor.retireStoredKeys(retired: List<String>) {
        val all = prefs.all
        if (all["panel_assistant_authority"] in setOf("shadow", "native") ||
            (all["panel_assistant_update_owner_seen_ms"] as? Long ?: 0L) > 0L
        ) {
            putBoolean(PANEL_ASSISTANT_CONNECTION_SEEN_PREF, true)
        }
        // A controller's old storage key carries its value onto the setting's own key. It is what the
        // panel acted on, so it wins over a registry write the controller had not followed.
        SettingsRegistry.LEGACY_CONTROLLER_KEYS.forEach { (legacy, key) ->
            (all[legacy] as? Boolean)?.let { putBoolean(key, it) }
        }
        retired.forEach(::remove)
    }

    fun migrateLiveStore(): Boolean = synchronized(CONFIG_LOCK) {
        rerootDashboardEntityOwners()
        canonicalizeStoredHomeDashboard()
        val from = storedSchema
        val retired = prefs.all.keys.filter(SettingsRegistry::isRetired)
        if (from == SettingsRegistry.SCHEMA) {
            if (retired.isEmpty()) return@synchronized true
            return@synchronized durableCommit {
                retireStoredKeys(retired)
                putInt("config_schema", SettingsRegistry.SCHEMA)
            }
        }
        val specs = SettingsRegistry.SPECS.filterNot { it.readOnly || it.transient }
        val current = specs.associate { it.key to getRaw(it) }
        val (migrated, warnings) = Migrations.migrate(from, current)
        warnings.forEach { Log.w(TAG, "live-store migration: $it") }
        val ed = prefs.edit()
        for (spec in specs) {
            val next = migrated[spec.key] ?: continue
            if (next != current[spec.key]) stage(ed, spec, next)
        }
        if (from < 11 && !prefs.contains("dashboard_network_warning")) {
            ed.putBoolean("dashboard_network_warning", true)
        }
        // Schema 6 moves the adaptive response to a new key, so the chain cannot carry it here: the
        // retired key is no longer a registered setting and never appears in the map built above. This
        // is the one place that can read it, and the one place that can tell a stored value from a
        // defaulted one.
        //
        // The schema marker decides whether there is anything to carry. A store at schema 2 or later has
        // booted a build that shipped the setting, so its response — chosen or defaulted — must survive.
        // A store still on the schema-1 sentinel is either genuinely fresh or predates the setting
        // entirely (v0.9.4 shipped schema 1 with no sensitivity setting; v0.9.5 shipped schema 2 with
        // it), and in both cases has no previous response, so the current default is correct.
        if (from >= SettingsRegistry.FIRST_SCHEMA_WITH_SENSITIVITY &&
            !prefs.contains(SettingsRegistry.RESPONSE_PERCENT_KEY)
        ) {
            val legacy = prefs.getInt(
                SettingsRegistry.LEGACY_SENSITIVITY_KEY,
                SettingsRegistry.LEGACY_NEUTRAL_SENSITIVITY,
            )
            ed.putInt(SettingsRegistry.RESPONSE_PERCENT_KEY, Migrations.rescaleSensitivity(legacy))
        }
        ed.remove(SettingsRegistry.LEGACY_SENSITIVITY_KEY)
        // Schema 15 renames the address family setting. As with the sensitivity above, the retired key is
        // no registered setting, so only this read can carry a chosen value; the retired-key removal
        // below then drops the old one in the same commit.
        if (from < 15 && !prefs.contains(SettingsRegistry.ADDRESS_FAMILY_KEY)) {
            prefs.getString(SettingsRegistry.LEGACY_ADDRESS_FAMILY_KEY, null)?.let {
                ed.putString(SettingsRegistry.ADDRESS_FAMILY_KEY, it)
            }
        }
        // Schema 16 turns Home Assistant navigation hiding off for new panels only. A store that booted a
        // build with the setting and never chose was showing it on, and an update must not change that; a
        // fresh store (the schema-1 sentinel) or one from before the setting gets the new default.
        if (from >= SettingsRegistry.FIRST_SCHEMA_WITH_NATIVE_KIOSK && from < 16 &&
            !prefs.contains(SettingsRegistry.NATIVE_KIOSK_KEY)
        ) {
            ed.putBoolean(SettingsRegistry.NATIVE_KIOSK_KEY, true)
        }
        // A schema-3 live store may have relied on the old true fallbacks without ever materializing
        // them. Preserve that upgrade behavior, while fresh schema-1 stores receive schema-4 defaults.
        if (from == 3) {
            if (!prefs.contains("wake_on_wave")) ed.putBoolean("wake_on_wave", true)
            SettingsRegistry.LEGACY_DEFAULT_ON_HA_EXPOSURES.forEach { key ->
                val exposureKey = "${SettingsRegistry.HA_EXPOSE_PREFIX}$key"
                if (!prefs.contains(exposureKey)) ed.putBoolean(exposureKey, true)
            }
        }
        // Also clean a current-schema store dirtied by a raw legacy restore. Removal and the marker
        // share one commit, so a failed migration leaves everything available for the next retry.
        ed.retireStoredKeys(retired)
        ed.putInt("config_schema", SettingsRegistry.SCHEMA)
        // The schema marker moves in the SAME transaction as the values it describes, so a failed commit
        // leaves the store wholly at its old schema and the migration is simply retried at the next
        // start. That is the fail-closed direction, but only if the result is actually read: this used to
        // log success unconditionally, which would report a semantic migration as done when nothing had
        // been written. A schema-6 store that silently kept schema-5 state is exactly the condition an
        // operator needs to see in the log, so it is reported as an error and the outcome is returned.
        val committed = ed.commit()
        if (committed) {
            Log.i(TAG, "migrated live config store: schema $from -> ${SettingsRegistry.SCHEMA}")
        } else {
            Log.e(
                TAG,
                "live config store migration $from -> ${SettingsRegistry.SCHEMA} did not commit; " +
                    "the store remains at schema $from and the migration will be retried",
            )
        }
        committed
    }

    /**
     * A fresh random device identity: 16 bytes from [SecureRandom], lowercase hex.
     *
     * [SecureRandom] rather than [java.util.Random] because the seed is the whole point — a cloned
     * factory image gives every panel the same boot state, and a predictably seeded generator would
     * reproduce the defect this identity exists to remove.
     */
    private fun mintDeviceUid(): String {
        val bytes = ByteArray(DEVICE_UID_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /** Whether an HA-capable setting is currently exposed to Home Assistant (per-panel override). */
    fun haExposed(key: String, default: Boolean): Boolean =
        prefs.getBoolean("${SettingsRegistry.HA_EXPOSE_PREFIX}$key", default)
    fun setHaExposed(key: String, on: Boolean) {
        edit { putBoolean("${SettingsRegistry.HA_EXPOSE_PREFIX}$key", on) }
    }

    private fun slug(s: String): String = panelIdSlug(s).ifEmpty { "ha_paneld_panel" }

    companion object {
        internal const val TAG = "ha-paneld/config"
        internal const val PANEL_ASSISTANT_CONNECTION_SEEN_PREF = "migration_notice_connection_seen"
        internal const val UI_ROUTES_CLOSED_PREF = "ui_routes_closed"
        /** Every Config wrapper addresses one process-wide SQLite namespace, so transaction ownership
         * must be process-wide as well. */
        internal val CONFIG_LOCK = Any()
        internal const val SECURITY_MODE_PREF = "device_local_hardened_security"
        /** The minted device identity. `device_local_` and unregistered: never exported, never restored. */
        internal const val DEVICE_UID_PREF = "device_local_device_uid_v1"
        private const val DEVICE_UID_BYTES = 16
        private const val LOG_SHIP_TCP_DEFAULT_MIGRATION_PREF =
            "device_local_log_ship_tcp_default_migrated_v1"
        private const val HA_AREA_USER_OVERRIDE_PREF = "device_local_ha_area_user_override"
        private const val LIGHT_SENSOR_REPORTED_PREF = "device_local_light_sensor_reported"
        internal const val AUTO_SLEEP_GENERATION_PREF = "auto_sleep_generation_v1"
        private const val LAST_LAUNCH_SCREEN_VERSION_PREF =
            "device_local_last_launch_screen_version"
        private const val POWER_SAFETY_ACKNOWLEDGEMENT_PREF =
            "device_local_power_safety_acknowledgement_v1"
        internal const val DASHBOARD_ENTITY_INITIAL_ACTIVATION_PENDING_KEY =
            "dashboard_entity_initial_activation_pending"
        private const val PROXIMITY_PROFILE_MIGRATED = "proximity_profile_calibration_migrated"
        private val LEGACY_PROXIMITY_KEYS = listOf(
            "prox_near_raw",
            "prox_far_raw",
            "prox_threshold",
            "prox_near_below",
            "prox_sensitivity",
        )
        const val DEFAULT_PORT = 8888
        const val VERSION = BuildConfig.VERSION_NAME
        const val MDNS_SERVICE_TYPE = "_ha-paneld._tcp.local."
    }
}

/**
 * The path of an absolute dashboard route that names [configuredOrigin], or null for anything else.
 *
 * Exists only for values stored before `home_dashboard` was validated. The path canonicalizer refuses a
 * URL scheme outright, which is right for a write and useless for a value already on disk, where the
 * alternative is leaving the panel with a route its own reload cannot use. A route naming a DIFFERENT
 * origin returns null and is left untouched: rewriting it would silently retarget the panel at a
 * dashboard on someone else's server.
 */
internal fun sameOriginDashboardRoute(raw: String, configuredOrigin: String?): String? {
    val trimmed = raw.trim()
    val lower = trimmed.lowercase(Locale.ROOT)
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
    if (configuredOrigin == null) return null
    if (canonicalHaOrigin(trimmed) != configuredOrigin) return null
    val afterScheme = trimmed.substringAfter("://")
    val slash = afterScheme.indexOf('/')
    return if (slash < 0) "/" else afterScheme.substring(slash)
}

/**
 * The disambiguating suffix a generated panel id carries when the device name is too generic to use.
 *
 * Two panels flashed from one factory image report the same model AND the same ANDROID_ID, so a suffix
 * from the ANDROID_ID gave them one identical panel id (#155). The minted identity separates them. A
 * panel that already has an id keeps it: [Config.ensurePanelId] persists the first one, and every
 * build that can upgrade in place has done so since 0.9.6.
 *
 * Falls back to the ANDROID_ID suffix, and then to a constant, so a panel that can read neither value
 * still gets a well-formed id.
 */
internal fun panelIdSuffix(androidId: String, deviceUid: String): String =
    deviceUid.takeLast(4).ifBlank { androidId.takeLast(4).ifBlank { "panel" } }
