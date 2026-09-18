package io.github.maxlyth.hapaneld

import android.content.Context
import android.content.ContentResolver
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.maxlyth.hapaneld.audio.MicrophoneGain
import io.github.maxlyth.hapaneld.camera.CameraResolution
import io.github.maxlyth.hapaneld.config.Migrations
import io.github.maxlyth.hapaneld.config.SettingSpec
import io.github.maxlyth.hapaneld.config.SettingType
import io.github.maxlyth.hapaneld.config.SettingValue
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.config.Validation
import io.github.maxlyth.hapaneld.config.defaultBool
import io.github.maxlyth.hapaneld.config.defaultFloat
import io.github.maxlyth.hapaneld.config.defaultInt
import io.github.maxlyth.hapaneld.config.defaultLong
import io.github.maxlyth.hapaneld.dashboard.HomeDashboardLaunchCache
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.persistence.AppState
import io.github.maxlyth.hapaneld.persistence.DurableVisibilityPreferences
import io.github.maxlyth.hapaneld.util.DashboardPath
import io.github.maxlyth.hapaneld.util.DashboardTheme
import io.github.maxlyth.hapaneld.util.AndroidInput
import io.github.maxlyth.hapaneld.util.HaLink
import io.github.maxlyth.hapaneld.util.LogShipEndpoint
import io.github.maxlyth.hapaneld.util.SystemProps
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal data class MqttCredentialsSnapshot(
    val broker: String,
    val user: String,
    val password: String,
    val addressFamily: String = SettingsRegistry.DEFAULT_MQTT_ADDRESS_FAMILY,
)

internal enum class AutoSleepWriteResult { UNCHANGED, COMMITTED, FAILED }

internal data class HaAuthSnapshot(
    val url: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenExpiry: Long,
    val clientId: String,
)

/** Resolve the fresh-install software-navbar default from Android and vendor visibility signals.
 * Some PX30 firmware hardcodes Android's generic `config_showNavigationBar` to true even though the
 * vendor navbar is suppressed; the vendor property is authoritative when present.
 *
 * [hasNativeNavbar] is the profile's declaration that the firmware draws its own navigation bar, and
 * it wins outright: that is the one case where we know the panel needs no software bar, as opposed to
 * merely failing to prove it needs one. The remaining tiers are unchanged and still answer "the panel
 * probably has no usable system bar, so seed Swipe reveal". */
internal fun defaultNavbarMode(
    androidResourceShowsNavbar: Boolean?,
    vendorShowsNavbar: String?,
    profileId: String? = null,
    hasNativeNavbar: Boolean = false,
): String {
    if (hasNativeNavbar) return "Native"
    when (vendorShowsNavbar?.trim()?.lowercase(Locale.ROOT)) {
        "false", "0", "no", "off" -> return "Swipe reveal"
        "true", "1", "yes", "on" -> return "Off"
    }
    // Retained deliberately: it also covers the raw-config read path, where `resources` is null and
    // `androidResourceShowsNavbar` is therefore unknown rather than false.
    if (profileId in setOf("nspanel-pro")) return "Swipe reveal"
    return if (androidResourceShowsNavbar == false) "Swipe reveal" else "Off"
}

/**
 * The navbar mode this panel should actually run, given what it has stored. Separate from
 * [defaultNavbarMode] because a stored value can become invalid after the fact: config bundles carry
 * `navbar_mode` and are imported with no capability filter at all, so a bundle captured on a panel
 * with a native bar can land on one without. Coercing to the computed default rather than to `Off`
 * is deliberate — on a panel with no system bar that yields `Swipe reveal`, leaving the user with
 * working navigation instead of none.
 */
internal fun resolveNavbarMode(
    stored: String?,
    hasNativeNavbar: Boolean,
    androidResourceShowsNavbar: Boolean?,
    vendorShowsNavbar: String?,
    profileId: String? = null,
): String {
    val default = defaultNavbarMode(androidResourceShowsNavbar, vendorShowsNavbar, profileId, hasNativeNavbar)
    if (stored == null) return default
    if (!navbarModePermitted(stored, hasNativeNavbar)) return default
    return stored
}

/** Whether [mode] may be selected on a panel with this capability. Shared by the read-side coercion
 *  and by the HTTP/MQTT write admission, so all three cannot drift apart. */
internal fun navbarModePermitted(mode: String, hasNativeNavbar: Boolean): Boolean =
    hasNativeNavbar || !mode.equals("Native", ignoreCase = true)
/** Credential ownership that remains stable while a refresh token rotates its current access token. */
internal data class HaAuthOwner(
    val url: String,
    val refreshToken: String,
    val clientId: String,
    val staticAccessToken: String,
)

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
 * interface remains as a test and typed-caller compatibility boundary. The MQTT broker defaults to
 * empty, which disables MQTT — the
 * /play HTTP contract works standalone without a broker, so first-run never blocks on MQTT.
 */
class Config private constructor(
    private val prefs: SharedPreferences,
    private val contentResolver: ContentResolver?,
    private val calibrationPrefs: SharedPreferences,
    private val deviceStatePrefs: SharedPreferences,
    private val resources: Resources?,
) {
    enum class SecurityMode { RELAXED, HARDENED }

    // Preserve the previous HA policy for an upgraded enabled configuration. Materialise this
    // choice with the next Auto-sleep write so disabling/restarting never changes its source.
    private val absentAutoSleepSource = if (prefs.contains("auto_sleep")) "home_assistant" else "panel"
    private val wakeOnWaveEpoch = AtomicLong()
    private val haOAuthAttemptEpoch = AtomicLong()
    @Volatile private var logShipAbsentProtocolFallback: String? = null

    constructor(context: Context) : this(
        AppState.preferences(context, "config", "ha-paneld"),
        context.applicationContext.contentResolver,
        AppState.preferences(context, "profile-calibration", PROFILE_CALIBRATION_PREFS),
        AppState.preferences(context, "power-safety-acknowledgement", POWER_SAFETY_ACKNOWLEDGEMENT_PREFS),
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
     * remote config import, backup restore, HTTP request, or MQTT command cannot enable or disable it.
     * The non-exported native settings activity is the only UI that changes it.
     */
    val securityMode: SecurityMode
        get() = if (prefs.getBoolean(SECURITY_MODE_PREF, false)) SecurityMode.HARDENED else SecurityMode.RELAXED

    /** Hardened mode and ha-paneld-owned network ADB are mutually exclusive. The check and mode
     * commit share [CONFIG_LOCK] with network-ADB ownership so concurrent local/network requests
     * cannot cross and leave both durable authorities enabled. */
    fun setSecurityMode(mode: SecurityMode): Boolean = synchronized(CONFIG_LOCK) {
        if (mode == SecurityMode.HARDENED && prefs.getBoolean("network_adb_enabled", false)) return@synchronized false
        durableCommit { putBoolean(SECURITY_MODE_PREF, mode == SecurityMode.HARDENED) }
    }

    val hardenedSecurityEnabled: Boolean get() = securityMode == SecurityMode.HARDENED

    /**
     * Whether the user has explicitly accepted this panel's identity during setup.
     *
     * Needed because `panel_id` is never blank — it auto-derives on first read — so its value cannot
     * distinguish "the user chose this" from "nobody has looked at it yet". That distinction matters: the
     * id names every entity this panel publishes to Home Assistant, and changing it later renames all of
     * them, so setup asks once and records the answer rather than inferring consent from a generated
     * default.
     *
     * Deliberately an internal pref rather than a [SettingSpec]: it is setup's own state, not a user
     * setting, so it has no place on the Configure form, in a config bundle or as a Home Assistant entity.
     */
    var setupIdentityConfirmed: Boolean
        get() = prefs.getBoolean(SETUP_IDENTITY_CONFIRMED_PREF, false)
        set(value) = synchronized(CONFIG_LOCK) {
            prefs.edit().putBoolean(SETUP_IDENTITY_CONFIRMED_PREF, value).commit()
            Unit
        }

    /**
     * One-shot upgrade migration: a panel that was already configured before the entity-filter question
     * existed counts as having answered it.
     *
     * Without this, introducing the question strands the entire installed base. The flag defaults false, the
     * built-in renderer holds its first load until it is answered, and `setupEverCompleted` is itself new — so
     * an upgraded panel would stop showing its dashboard, and could not even earn the completion stamp that
     * would release it, because earning it requires the render that is being held. A canary deployed to two
     * configured panels reproduced exactly that.
     *
     * It runs on every start until it actually finds evidence, and only then records itself as done. The
     * earlier version ran exactly once and marked itself complete even when the configuration read back
     * blank — which happened on real hardware — leaving a configured panel permanently on the first-run path
     * with no second chance. Retrying is the safer failure: the worst case is that a panel restarted midway
     * through setup skips the question and renders unfiltered, which is recoverable from the Configure tab,
     * whereas the other direction leaves a working panel showing a setup screen instead of its dashboard.
     *
     * This is a convenience, not the safety net. The hold predicate independently exempts a panel whose
     * filter is already enabled, so a failure here can no longer strand anything.
     */
    fun migrateSetupQuestionsForExistingInstall() = synchronized(CONFIG_LOCK) {
        if (!prefs.getBoolean(SETUP_QUESTION_MIGRATION_PREF, false)) {
            // Guided setup OWNS this panel once the identity step has been confirmed — that flag is written by
            // the wizard and nothing else, so its presence proves any config found here was written BY the
            // journey in progress, not by a pre-existing install. Without this gate the retry below read the
            // wizard's own broker save as upgrade evidence on the first mid-journey service restart and
            // durably stamped every question answered: the panel rendered unfiltered, the journey reported
            // complete, and the sign-in return dumped the user to Configure with two questions never asked
            // (second hardware walk, 2026-07-26). Migration is DONE for such a panel: the wizard records the
            // real answers itself.
            if (setupIdentityConfirmed) {
                prefs.edit().putBoolean(SETUP_QUESTION_MIGRATION_PREF, true).commit()
            } else if (configuredBeforeSetupQuestionTracking()) {
                prefs.edit()
                    .putBoolean(SETUP_QUESTION_MIGRATION_PREF, true)
                    .putBoolean(SETUP_ENTITY_FILTER_ANSWERED_PREF, true)
                    // Also record that setup was reached before, so the wizard frames a later re-arm as a
                    // repair ("nothing has been reset") rather than replaying a first run at this panel.
                    .putBoolean(SETUP_EVER_COMPLETED_PREF, true)
                    .commit()
                Log.i(TAG, "setup migration: pre-existing install, entity-filter question marked answered")
            }
        }

        migrateHomeDashboardQuestionForExistingInstall()
    }

    /**
     * Versioned independently from the original entity-filter migration because that v1 marker is already
     * durable on the installed base. Reusing it made the newly introduced dashboard answer unreachable on
     * upgrade and sent completed panels back to the blocking HOME_DASHBOARD step.
     */
    private fun migrateHomeDashboardQuestionForExistingInstall() {
        if (prefs.getBoolean(SETUP_HOME_DASHBOARD_MIGRATION_PREF, false)) return

        val preExisting = setupEverCompleted || (!setupIdentityConfirmed && configuredBeforeSetupQuestionTracking())
        if (!preExisting && !setupIdentityConfirmed) return

        prefs.edit()
            .putBoolean(SETUP_HOME_DASHBOARD_MIGRATION_PREF, true)
            .apply {
                if (preExisting) putBoolean(SETUP_HOME_DASHBOARD_CHOSEN_PREF, true)
            }
            .commit()
        if (preExisting) Log.i(TAG, "setup migration: pre-existing install, home-dashboard question marked answered")
    }

    private fun configuredBeforeSetupQuestionTracking(): Boolean =
        dashboardEntityLearningEnabled || mqttBroker.isNotBlank() || haUrl.isNotBlank()

    /**
     * Whether the user has answered setup's entity-filter question — either way.
     *
     * Turning the filter ON is visible in `dashboard_entity_learning`, but a deliberate "no thanks" is
     * indistinguishable from never having been asked. Since the built-in renderer is held back from its
     * first load until this question is answered, inferring the answer from the filter setting would leave
     * anyone who declined stuck on the question forever, with a panel that never renders. So, like
     * [setupIdentityConfirmed], the answer is recorded rather than inferred. Internal setup state.
     */
    var setupEntityFilterAnswered: Boolean
        get() = prefs.getBoolean(SETUP_ENTITY_FILTER_ANSWERED_PREF, false)
        set(value) = synchronized(CONFIG_LOCK) {
            prefs.edit().putBoolean(SETUP_ENTITY_FILTER_ANSWERED_PREF, value).commit()
            Unit
        }

    /**
     * Whether the user has chosen which dashboard this panel shows — including choosing to follow the
     * signed-in account's default. That choice leaves `home_dashboard` blank, which is indistinguishable
     * from never having been asked, so like [setupEntityFilterAnswered] the answer is recorded rather than
     * inferred. Internal setup state.
     */
    var setupHomeDashboardChosen: Boolean
        get() = prefs.getBoolean(SETUP_HOME_DASHBOARD_CHOSEN_PREF, false)
        set(value) = synchronized(CONFIG_LOCK) {
            prefs.edit().putBoolean(SETUP_HOME_DASHBOARD_CHOSEN_PREF, value).commit()
            Unit
        }

    /**
     * A human's "yes, the dashboard is on the panel" — recorded as the configuration fingerprint it was
     * given against, because for a foreign renderer a render can only be attested, never observed. A
     * mismatch against the current fingerprint means the panel has not been shown to work as configured
     * NOW, so the journey re-arms; blank means never attested. Internal setup state, like the flag above.
     */
    /**
     * Whether this panel has EVER finished setup — the bit that separates "repair" from "first run".
     * A later broken broker or revoked token re-arms the journey, and the wizard must then read as a
     * repair of a configured panel, never as a factory reset: the difference between "one thing needs
     * attention" and a user believing everything they built is gone. Stamped by the two completion
     * proofs (frontend-connected, human attestation) and never cleared by re-arming.
     */
    var setupEverCompleted: Boolean
        get() = prefs.getBoolean(SETUP_EVER_COMPLETED_PREF, false)
        set(value) = synchronized(CONFIG_LOCK) {
            prefs.edit().putBoolean(SETUP_EVER_COMPLETED_PREF, value).commit()
            Unit
        }

    var setupRenderAttestation: String
        get() = prefs.getString(SETUP_RENDER_ATTESTED_PREF, "") ?: ""
        set(value) = synchronized(CONFIG_LOCK) {
            prefs.edit().putString(SETUP_RENDER_ATTESTED_PREF, value).commit()
            Unit
        }

    /** Empty => MQTT disabled. e.g. "tcp://192.168.1.10:1883". */
    val mqttBroker: String get() = stringPref("mqtt_broker")
    val mqttUser: String get() = stringPref("mqtt_user")
    val mqttPassword: String get() = stringPref("mqtt_password")
    val mqttAddressFamily: String get() = stringPref("mqtt_address_family")

    /** One immutable durable-state generation for connection setup; consumers must not combine
     * independent broker, credential, and route-policy getters across a concurrent commit. */
    internal fun mqttCredentialsSnapshot(): MqttCredentialsSnapshot = synchronized(CONFIG_LOCK) {
        val all = prefs.all
        MqttCredentialsSnapshot(
            broker = all["mqtt_broker"] as? String ?: "",
            user = all["mqtt_user"] as? String ?: "",
            password = all["mqtt_password"] as? String ?: "",
            addressFamily = all["mqtt_address_family"] as? String
                ?: SettingsRegistry.DEFAULT_MQTT_ADDRESS_FAMILY,
        )
    }

    /** Device-local MQTT route memory. One broker tuple survives a controlled process boundary but is
     * excluded from config APIs/bundles because it is observed transport state, not user configuration. */
    internal fun mqttFamilyPreference(brokerIdentity: String): Boolean? = synchronized(CONFIG_LOCK) {
        if (prefs.getString(MQTT_FAMILY_BROKER_PREF, null) == brokerIdentity) {
            prefs.getBoolean(MQTT_FAMILY_IPV4_PREF, false)
        } else {
            null
        }
    }

    internal fun rememberMqttFamilyPreference(brokerIdentity: String, preferIpv4: Boolean): Boolean =
        synchronized(CONFIG_LOCK) {
            durableCommit {
                putString(MQTT_FAMILY_BROKER_PREF, brokerIdentity)
                putBoolean(MQTT_FAMILY_IPV4_PREF, preferIpv4)
            }
        }

    internal fun forgetMqttFamilyPreference(): Boolean = synchronized(CONFIG_LOCK) {
        if (!prefs.contains(MQTT_FAMILY_BROKER_PREF) && !prefs.contains(MQTT_FAMILY_IPV4_PREF)) {
            return@synchronized true
        }
        durableCommit {
            remove(MQTT_FAMILY_BROKER_PREF)
            remove(MQTT_FAMILY_IPV4_PREF)
        }
    }

    /** True when this exact MQTT configuration still owns its one announcement-wedge process boundary. */
    internal fun mqttAnnouncementBoundaryAvailable(identity: String): Boolean = synchronized(CONFIG_LOCK) {
        val consumed = prefs.getString(MQTT_ANNOUNCEMENT_BOUNDARY_PREF, null) ?: return@synchronized true
        if (consumed == identity) return@synchronized false
        // A relevant broker/profile/credential identity change starts a new bounded recovery epoch.
        durableCommit { remove(MQTT_ANNOUNCEMENT_BOUNDARY_PREF) }
    }

    internal fun consumeMqttAnnouncementBoundary(identity: String): Boolean = synchronized(CONFIG_LOCK) {
        if (prefs.getString(MQTT_ANNOUNCEMENT_BOUNDARY_PREF, null) == identity) return@synchronized false
        durableCommit { putString(MQTT_ANNOUNCEMENT_BOUNDARY_PREF, identity) }
    }

    internal fun clearMqttAnnouncementBoundary(identity: String): Boolean = synchronized(CONFIG_LOCK) {
        if (prefs.getString(MQTT_ANNOUNCEMENT_BOUNDARY_PREF, null) != identity) return@synchronized true
        durableCommit { remove(MQTT_ANNOUNCEMENT_BOUNDARY_PREF) }
    }

    /** Stable per-panel id used in entity_ids / MQTT topics. A fresh install persists its generated
     *  identity before the service constructs MQTT or mDNS, so a later Android device-name change cannot
     *  silently rename the panel. The fallback remains for callers that run before service startup. */
    val panelId: String
        get() = prefs.getString("panel_id", null) ?: defaultPanelId()

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
        // A meaningful, non-generic device name → use it; else model + a short ANDROID_ID suffix.
        return if (!name.isNullOrBlank() && !name.equals(Build.MODEL, ignoreCase = true)) slug(name)
        else slug(Build.MODEL) + "_" + androidId.takeLast(4).ifBlank { "panel" }
    }

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
    private fun durableCommit(mutation: SharedPreferences.Editor.() -> Unit): Boolean =
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
    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
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
        if (accepted["mqtt_user"]?.isEmpty() == true) editor.putString("mqtt_password", "")

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

        accepted["dashboard_package"]?.let { next ->
            if (next != dashboardPackage) editor.putBoolean("renderer_launch_pending", true)
        }
        accepted["home_dashboard"]?.let { next ->
            val nextPath = dashboardEntityScopePath(next)
            if (nextPath != "/" && nextPath != dashboardEntityScopePath(homeDashboard)) {
                editor.putString("dashboard_entity_dashboard_path", nextPath)
            }
        }

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

    /** Cached HA device-settings URL. [haDeviceLinkTarget] records which configured HA endpoint and
     *  panel identity produced the random device-registry id so a renderer/server change cannot leave
     *  "Open in HA" pointing at a device which does not exist on the new server. */
    val haDeviceUrl: String get() = prefs.getString("ha_device_url", "")!!
    val haDeviceLinkTarget: String get() = prefs.getString("ha_link_target", "")!!
    /** When [haDeviceUrl] was last resolved — drives a periodic re-resolve so a link left stale by an HA
     *  device delete+recreate (the id changes without a panel_id change) self-heals. */
    val haLinkResolvedAt: Long get() = prefs.getLong("ha_link_at", 0L)
    fun setHaDeviceUrl(url: String, target: String) {
        prefs.edit()
            .putString("ha_device_url", url)
            .putString("ha_link_target", target)
            .putLong("ha_link_at", System.currentTimeMillis())
            .apply()
    }

    /** A pre-ownership (legacy) cache is deliberately stale so the first upgraded start repairs it. */
    fun haDeviceLinkIsFresh(target: String, nowMs: Long, ttlMs: Long): Boolean =
        haDeviceUrl.isNotBlank() && haDeviceLinkTarget == target && nowMs - haLinkResolvedAt in 0 until ttlMs

    /** HA's frontend URL as the Companion knows it (its internal/external_url), resolved from the Companion
     *  DB + cached. The header "Open in HA" button falls back to this when [haDeviceUrl] (the panel's own
     *  device page) hasn't resolved — e.g. a remote panel over a tunnel — so the button is always present. */
    val haBaseUrl: String get() = prefs.getString("ha_base_url", "")!!
    fun setHaBaseUrl(url: String) { prefs.edit().putString("ha_base_url", url).apply() }

    /** Best "Open in HA" target. Never expose an unowned legacy cache or a device id resolved for a
     * different native endpoint/panel while its asynchronous repair is still running. */
    val haLinkUrl: String get() {
        val nativeTarget = haUrl.takeIf(String::isNotBlank)?.let { HaLink.resolutionTarget(it, panelId) }
        val ownedDeviceUrl = haDeviceUrl.takeIf {
            haDeviceLinkTarget.isNotBlank() && (nativeTarget == null || haDeviceLinkTarget == nativeTarget)
        }
        return ownedDeviceUrl ?: haUrl.ifBlank { haBaseUrl }
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
        get() = prefs.getString("friendly_name", null)?.takeIf { it.isNotBlank() } ?: deviceName()
    fun setFriendlyName(name: String) {
        edit { putString("friendly_name", name) }
    }

    /** Stable per-device id (Settings.Secure.ANDROID_ID); used as the HA device serial_number. */
    val androidId: String
        get() = Settings.Secure.getString(requireNotNull(contentResolver), Settings.Secure.ANDROID_ID) ?: ""

    /** The device's configured name (Companion's default-name source), else the model. */
    private fun deviceName(): String =
        (Settings.Global.getString(requireNotNull(contentResolver), Settings.Global.DEVICE_NAME)
            ?: Build.MODEL).ifBlank { Build.MODEL }

    /** Persist MQTT settings (used by the HTTP config page). A null password leaves it unchanged. */
    fun setMqtt(broker: String, user: String, password: String?, addressFamily: String? = null) {
        val normalizedAddressFamily = addressFamily?.let { raw ->
            val spec = checkNotNull(SettingsRegistry.spec("mqtt_address_family"))
            (SettingValue.validate(spec, raw) as? Validation.Ok)?.normalized
                ?: throw IllegalArgumentException("mqtt_address_family: unsupported value")
        }
        edit {
            putString("mqtt_broker", broker)
            putString("mqtt_user", user)
            if (password != null) putString("mqtt_password", password)
            if (normalizedAddressFamily != null) putString("mqtt_address_family", normalizedAddressFamily)
        }
    }

    /** Dashboard renderer selection. Empty means the automatic built-in renderer. */
    val dashboardPackage: String get() = stringPref("dashboard_package")

    /**
     * Packages the kiosk lock allows to hold the foreground without being pulled back.
     *
     * Empty by default, and deliberately so: a shipped default would change kiosk behaviour for every
     * existing panel on upgrade, and would make this project the arbiter of which third-party apps
     * deserve the foreground on someone else's wall. The operator names a companion because they
     * installed one and want to reach its UI — a voice assistant's setup screens, for instance —
     * without unlocking the kiosk on a panel nobody is standing at.
     */
    val kioskCompanionPackages: Set<String>
        get() = parseKioskCompanionPackages(prefs.getString("kiosk_companion_packages", "").orEmpty())
    fun setDashboardPackage(pkg: String) {
        require(AndroidInput.isDashboardTarget(pkg)) { "invalid dashboard package" }
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

    /** Home Assistant base URL for the built-in dashboard renderer, e.g. "http://homeassistant.local:8123".
     *  Empty => the built-in renderer is unavailable (external renderers unaffected). */
    val haUrl: String get() = stringPref("ha_url")

    /** True when the Panel Assistant integration installed or adopted this panel and said so.
     *
     *  This is the single definition of "Home Assistant deployed this panel". Every setup step that
     *  skips a question Home Assistant can already answer reads THIS, never the presence of a value
     *  Home Assistant happened to supply: a handed-over value that failed verification still came from
     *  Home Assistant, and a later step (MQTT) needs the same predicate with no URL involved at all.
     *  Deriving provenance from a populated field instead would re-collapse that distinction. */
    val haSetupHandover: Boolean get() = prefs.getBoolean("ha_setup_handover", false)

    fun setHaSetupHandover(value: Boolean) {
        edit { putBoolean("ha_setup_handover", value) }
    }

    /** The Home Assistant address the integration handed this panel, kept only while it has not been
     *  accepted. A verified address is promoted into [haUrl] and this is cleared; a failed one stays
     *  here so the wizard can show what was tried. */
    val haUrlHandover: String get() = prefs.getString("ha_url_handover", "")!!

    fun setHaUrlHandover(raw: String) {
        edit { putString("ha_url_handover", raw.trim()) }
    }

    /** Why [haUrlHandover] did not answer, or blank when none was tried or it verified. Written only by
     *  the panel's own probe; `/api/v1/config` refuses it from the network. */
    val haUrlHandoverReason: String get() = prefs.getString("ha_url_handover_reason", "")!!

    fun setHaUrlHandoverReason(raw: String) {
        edit { putString("ha_url_handover_reason", raw.trim()) }
    }

    /** The built-in renderer is only ready once the URL and an actual auth route exist. */
    internal fun builtInRendererReady(): Boolean =
        haUrl.isNotBlank() && (haToken.isNotBlank() || haRefreshToken.isNotBlank())

    /** Last authoritative HA server version observed for this exact renderer endpoint. This is only a
     * transient-outage fallback for the V2 compatibility preflight; a successful probe always wins. */
    internal fun cachedHaServerVersion(url: String): String? {
        val owner = url.trim().trimEnd('/')
        return prefs.getString("dashboard_ha_version_owner", null)
            ?.takeIf { it == owner }
            ?.let { prefs.getString("dashboard_ha_version", null) }
    }

    internal fun setHaServerVersionIfOwned(url: String, version: String): Boolean = synchronized(CONFIG_LOCK) {
        val owner = url.trim().trimEnd('/')
        if (haUrl.trim().trimEnd('/') != owner) return@synchronized false
        durableCommit {
            putString("dashboard_ha_version_owner", owner)
            putString("dashboard_ha_version", version.take(64))
        }
    }

    /** The persisted launch cache's live owner: endpoint + stable credential + configured
     *  home_dashboard, one-way hashed (see [HomeDashboardLaunchCache.ownerFingerprint]). */
    internal fun homeDashboardLaunchOwner(): String = synchronized(CONFIG_LOCK) {
        HomeDashboardLaunchCache.ownerFingerprint(haAuthSnapshot().stableOwner(), homeDashboard)
    }

    /**
     * Last home-dashboard path a live, list-validated resolution produced for the CURRENT owner.
     * A relaunch accelerator only, the dashboard analogue of [cachedHaServerVersion]: the renderer
     * may navigate to it immediately while the authenticated resolution re-runs, and that live
     * answer always supersedes it. Any HA instance, account or explicit-path change makes the row
     * unreadable by owner mismatch; a corrupt stored value fails closed to a live resolution.
     */
    internal fun cachedHomeDashboardLaunchPath(): String? = synchronized(CONFIG_LOCK) {
        prefs.getString("dashboard_launch_path_owner", null)
            ?.takeIf { it == homeDashboardLaunchOwner() }
            ?.let { HomeDashboardLaunchCache.sanitizedStoredPath(prefs.getString("dashboard_launch_path", null)) }
    }

    /** Persist only while [ownerFingerprint] still names the live owner, so a resolution that raced
     *  a credential/endpoint/setting change cannot stamp the new identity with the old answer. */
    internal fun setHomeDashboardLaunchPathIfOwned(ownerFingerprint: String, path: String): Boolean =
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
    internal fun clearHomeDashboardLaunchPathIfOwned(ownerFingerprint: String): Boolean =
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
    val haToken: String get() = stringPref("ha_token")

    /** OAuth refresh token (from a provisioning login). When set, [haToken] is a short-lived access
     *  token the app refreshes on demand — so no 10-year token lives on the panel. Blank => [haToken] is
     *  treated as a static long-lived token that never needs refreshing. */
    val haRefreshToken: String get() = stringPref("ha_refresh_token")

    /** Epoch-seconds expiry of the current [haToken] (refresh model only). 0 => unknown → refresh now. */
    val haTokenExpiry: Long get() = longPref("ha_token_expiry")

    /** Dark mode for panels WITHOUT a system dark/light setting (Android 9-; default true — wall panels
     *  live in dark rooms): themes ha-paneld's own native activities (DayNight) and sets the built-in
     *  renderer's dashboard default. Android 10+ panels follow the OS setting instead (the Display-card
     *  toggle is hidden there); the `:8888` web UI always follows the viewing browser's preference. */
    val darkMode: Boolean get() = boolPref("dark_mode")
    fun setDarkMode(v: Boolean) = edit { putBoolean("dark_mode", v) }

    /** OAuth client_id to use when refreshing [haToken]. Blank => the HA origin (`<ha_url>/`, what the
     *  frontend uses). Set it to match the client the refresh token was issued for — e.g.
     *  `https://home-assistant.io/android` to reuse an HA Companion refresh token. */
    val haClientId: String get() = stringPref("ha_client_id")

    /** One immutable durable-state generation for renderer authentication and refresh. */
    internal fun haAuthSnapshot(): HaAuthSnapshot = synchronized(CONFIG_LOCK) {
        val all = prefs.all
        HaAuthSnapshot(
            url = all["ha_url"] as? String ?: "",
            accessToken = all["ha_token"] as? String ?: "",
            refreshToken = all["ha_refresh_token"] as? String ?: "",
            tokenExpiry = (all["ha_token_expiry"] as? Number)?.toLong() ?: 0L,
            clientId = all["ha_client_id"] as? String ?: "",
        )
    }

    internal fun beginHaOAuthAttempt(): HaOAuthAttemptAuthority = synchronized(CONFIG_LOCK) {
        HaOAuthAttemptAuthority(
            owner = haAuthSnapshot().stableOwner(),
            epoch = haOAuthAttemptEpoch.incrementAndGet(),
        )
    }

    internal fun isHaOAuthAttemptCurrent(epoch: Long): Boolean = synchronized(CONFIG_LOCK) {
        haOAuthAttemptEpoch.get() == epoch
    }

    /** Persist the built-in renderer connection (HTTP config page / provisioning). A null token leaves
     *  it unchanged, mirroring [setMqtt]'s password semantics. */
    fun setHaConnection(url: String, token: String?) {
        edit {
            val normalized = url.trim().trimEnd('/')
            putString("ha_url", normalized)
            stageDashboardEntityHaUrlChange(this, normalized)
            if (token != null) putString("ha_token", token)
        }
    }

    /** Persist a refresh result only while the complete auth generation used to obtain it is still
     * current. This prevents a late OAuth response from overwriting credentials replaced by an admin. */
    internal fun setHaRefreshedTokenIfOwned(
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
    fun setHaRefreshToken(refresh: String) { edit { putString("ha_refresh_token", refresh) } }

    /** Set the current access-token expiry (epoch seconds) — provisioning path. */
    fun setHaTokenExpiry(epochSec: Long) { edit { putLong("ha_token_expiry", epochSec) } }

    /** Set (or clear, with "") the OAuth client_id used for token refresh. */
    fun setHaClientId(clientId: String) { edit { putString("ha_client_id", clientId) } }

    /** Atomically persist every value borrowed from the Companion for a built-in renderer switch. */
    fun setBorrowedRendererSettings(
        url: String,
        accessToken: String,
        refreshToken: String,
        tokenExpiry: Long,
        clientId: String,
        zoom: Int?,
    ): Boolean = applyBatch {
        setHaConnection(url, accessToken)
        setHaRefreshToken(refreshToken)
        setHaTokenExpiry(tokenExpiry)
        setHaClientId(clientId)
        zoom?.let(::setDashboardZoom)
        edit { putBoolean("renderer_launch_pending", true) }
    }

    /** Launcher package the Launcher button brings forward. Empty => auto-pick a non-default home. */
    val launcherPackage: String get() = stringPref("launcher_package")
    fun setLauncherPackage(pkg: String) {
        edit { putString("launcher_package", pkg) }
    }

    /** Store the raw list as typed; [kioskCompanionPackages] owns parsing so both sides agree. */
    fun setKioskCompanionPackages(raw: String) {
        edit { putString("kiosk_companion_packages", raw.trim()) }
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
    fun setTameVendorPackages(raw: String) {
        edit { putString("tame_vendor_packages", raw.trim()) }
    }

    /**
     * Extra hostnames allowed in the HTTP `Host` header — the anti-DNS-rebinding allowlist for the
     * `:8888` guard ([http.OriginGuard.hostAllowed]). The guard always allows IP literals, `localhost`,
     * and `*.local` (mDNS), which covers reaching a panel by IP or its mDNS name, so this is only needed
     * when a panel is fronted by a **custom DNS name** (e.g. `kitchen-panel.myhome.lan`). Set it via the
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

    /** Opt-in activity policy. Physical screen light state and explicit manual screen commands remain
     * separate authorities; this flag only admits the automatic policy runtime. */
    val autoSleep: Boolean get() = boolPref("auto_sleep")
    internal fun migrateAutoSleepSource(): Boolean = synchronized(CONFIG_LOCK) {
        prefs.contains("auto_sleep_source") || durableCommit { putString("auto_sleep_source", absentAutoSleepSource) }
    }
    val autoSleepSource: String get() = prefs.getString("auto_sleep_source", null)
        ?.takeIf { it == "panel" || it == "home_assistant" } ?: absentAutoSleepSource
    internal val autoSleepGeneration: Long get() = synchronized(CONFIG_LOCK) {
        prefs.getLong(AUTO_SLEEP_GENERATION_PREF, 0L)
    }
    internal fun setAutoSleep(on: Boolean): AutoSleepWriteResult = synchronized(CONFIG_LOCK) {
        if (boolPref("auto_sleep") == on) return@synchronized AutoSleepWriteResult.UNCHANGED
        // MQTT immediately asks the runtime to refresh, so durability must precede that refresh.
        val generation = nextAutoSleepGenerationLocked()
        if (durableCommit {
                putString("auto_sleep_source", autoSleepSource)
                putBoolean("auto_sleep", on)
                putLong(AUTO_SLEEP_GENERATION_PREF, generation)
            }
        ) AutoSleepWriteResult.COMMITTED
        else AutoSleepWriteResult.FAILED
    }

    /** Null means a newer setting value superseded this compare-and-set request. */
    internal fun setAutoSleepIf(
        expected: Boolean,
        expectedGeneration: Long,
        on: Boolean,
    ): AutoSleepWriteResult? = synchronized(CONFIG_LOCK) {
        if (boolPref("auto_sleep") != expected ||
            prefs.getLong(AUTO_SLEEP_GENERATION_PREF, 0L) != expectedGeneration
        ) return@synchronized null
        setAutoSleep(on)
    }

    private fun nextAutoSleepGenerationLocked(): Long =
        (prefs.getLong(AUTO_SLEEP_GENERATION_PREF, 0L) + 1L).coerceAtLeast(1L)

    /** Durable expert exclusions for automatic Area-source discovery. They deliberately remain
     * outside SettingsRegistry: entity ids are installation-specific runtime inputs, not fleet
     * settings or Home Assistant entities. Area dashboard_entity is never used to prune this memory. */
    internal fun autoSleepExcludedEntityIds(areaId: String): Set<String> = synchronized(CONFIG_LOCK) {
        val area = normalizeAutoSleepAreaId(areaId)
        if (area.isBlank()) emptySet() else {
            val all = readAutoSleepExclusions()
            autoSleepExclusionScopes().flatMapTo(linkedSetOf()) { scope -> all[scope]?.get(area).orEmpty() }
        }
    }

    internal fun setAutoSleepSourceIncluded(areaId: String, entityId: String, included: Boolean): Boolean =
        synchronized(CONFIG_LOCK) {
            val scopes = autoSleepExclusionScopes()
            val scope = scopes.firstOrNull().orEmpty()
            val area = normalizeAutoSleepAreaId(areaId)
            val entity = normalizeAutoSleepEntityId(entityId)
            if (scope.isBlank() || area.isBlank() || entity.isBlank()) return@synchronized false
            val all = readAutoSleepExclusions().mapValuesTo(linkedMapOf()) { (_, areas) ->
                areas.mapValuesTo(linkedMapOf()) { (_, ids) -> ids.toMutableSet() }
            }
            val merged = scopes.flatMapTo(linkedSetOf()) { storedScope -> all[storedScope]?.get(area).orEmpty() }
            if (included) merged.remove(entity) else merged.add(entity)
            // UUID adoption is asynchronous. Collapse the URL fallback into the stable namespace on
            // the first later mutation so an old exclusion cannot reappear after being included.
            scopes.forEach { storedScope ->
                all[storedScope]?.remove(area)
                if (all[storedScope]?.isEmpty() == true) all.remove(storedScope)
            }
            val areas = all.getOrPut(scope) { linkedMapOf() }
            if (merged.isNotEmpty()) areas[area] = merged
            if (areas.isEmpty()) all.remove(scope)
            val encoded = JSONObject().apply {
                all.toSortedMap().forEach { (storedScope, storedAreas) ->
                    put(storedScope, JSONObject().apply {
                        storedAreas.toSortedMap().forEach { (storedArea, storedIds) ->
                            put(storedArea, JSONArray(storedIds.toSortedSet().toList()))
                        }
                    })
                }
            }.toString()
            if (encoded.toByteArray(Charsets.UTF_8).size > MAX_AUTO_SLEEP_EXCLUSIONS_BYTES) {
                return@synchronized false
            }
            durableCommit {
                putString(AUTO_SLEEP_EXCLUSIONS_PREF, encoded)
            }
        }

    /** Null means the opaque key belonged to a different HA installation by commit time. */
    internal fun setAutoSleepSourceIncludedIfScope(
        expectedScope: String,
        areaId: String,
        entityId: String,
        included: Boolean,
    ): Boolean? = synchronized(CONFIG_LOCK) {
        if (expectedScope.isBlank() || autoSleepExclusionScope() != expectedScope) null
        else setAutoSleepSourceIncluded(areaId, entityId, included)
    }

    internal fun autoSleepExclusionScope(): String {
        return autoSleepExclusionScopes().firstOrNull().orEmpty()
    }

    private fun autoSleepExclusionScopes(): List<String> {
        val stable = dashboardEntityInstanceUuid.trim().lowercase(Locale.ROOT)
            .takeIf(String::isNotBlank)?.let { "uuid:$it" }
        val origin = canonicalHaOrigin(haUrl)?.lowercase(Locale.ROOT)?.let { "origin:$it" }
        return listOfNotNull(stable, origin).distinct()
    }

    private fun readAutoSleepExclusions(): Map<String, Map<String, Set<String>>> {
        val raw = prefs.getString(AUTO_SLEEP_EXCLUSIONS_PREF, "").orEmpty()
        if (raw.isBlank() || raw.toByteArray(Charsets.UTF_8).size > MAX_AUTO_SLEEP_EXCLUSIONS_BYTES) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            root.keys().asSequence().associateWith { scope ->
                val areas = root.getJSONObject(scope)
                areas.keys().asSequence().mapNotNull { rawArea ->
                    val area = normalizeAutoSleepAreaId(rawArea).takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val values = areas.getJSONArray(rawArea)
                    area to (0 until values.length()).mapNotNullTo(linkedSetOf()) { index ->
                        normalizeAutoSleepEntityId(values.optString(index)).takeIf(String::isNotBlank)
                    }
                }.toMap()
            }
        }.onFailure { Log.w(TAG, "ignoring malformed auto-sleep source exclusions") }.getOrDefault(emptyMap())
    }

    private fun normalizeAutoSleepAreaId(raw: String): String = raw.trim().lowercase(Locale.ROOT)
        .takeIf { it.length <= 255 && it.matches(Regex("[a-z0-9_-]+")) }.orEmpty()

    private fun normalizeAutoSleepEntityId(raw: String): String = raw.trim().lowercase(Locale.ROOT)
        .takeIf { it.length <= 255 && it.matches(Regex("binary_sensor\\.[a-z0-9_]+")) }.orEmpty()

    // Prevent the vendor firmware idle-dimming the backlight at the screen-off timeout (it drops the
    // hardware backlight to ~1% after the timeout even while the OS keeps the screen on). On by default —
    // these are mains-powered wall panels; turn it off to restore the firmware's own dimming behaviour.
    val preventIdleDim: Boolean get() = boolPref("prevent_idle_dim")
    fun setPreventIdleDim(on: Boolean) {
        edit { putBoolean("prevent_idle_dim", on) }
    }

    // Hold a partial wakelock so the SoC + network never suspend (screen still free to sleep). ON by
    // default: a mains wall panel must never Doze into an unreachable state where the MQTT reactor +
    // keepalive freeze and the broker connection dies half-open. Toggle off only for a battery panel.
    val keepAwake: Boolean get() = boolPref("keep_awake")
    fun setKeepAwake(on: Boolean) {
        edit { putBoolean("keep_awake", on) }
    }

    // App watchdog: poll the dashboard app and self-heal it — relaunch if its process dies, and return
    // to it if it's been backgrounded too long. Opt-in (off by default): a stock panel never auto-acts.
    val watchdogEnabled: Boolean get() = boolPref("watchdog_enabled")
    fun setWatchdogEnabled(on: Boolean) {
        edit { putBoolean("watchdog_enabled", on) }
    }

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

    // Camera trial: the master switch and the hard caps that clamp every stream/snapshot request.
    // Every one of these needs a setter. An earlier comment here claimed the caps reached the owner
    // "through the ordinary reconfigure path" and so needed none — there is no ordinary path. The HTTP
    // config route persists a non-live setting only if it has an explicit line in the direct-mutation
    // batch, so a registry key with neither a live-apply handler nor a setter called from that batch is
    // reported saved and silently discarded. That is what happened to all four of these.
    val cameraEnabled: Boolean get() = boolPref("camera_enabled")
    fun setCameraEnabled(on: Boolean) {
        edit { putBoolean("camera_enabled", on) }
    }
    val cameraResolution: CameraResolution
        get() = CameraResolution.parse(stringPref("camera_resolution")) ?: CameraResolution.P720
    fun setCameraResolution(value: String) {
        edit { putString("camera_resolution", value) }
    }
    val cameraFps: Int get() = intPref("camera_fps").coerceIn(1, 30)
    fun setCameraFps(fps: Int) {
        edit { putInt("camera_fps", fps.coerceIn(1, 30)) }
    }
    val cameraKbps: Int get() = intPref("camera_kbps").coerceIn(250, 8000)

    /** Exposure bias in stops; the owner clamps it to whatever range the sensor advertises. */
    val cameraExposureEv: Float get() = floatPref("camera_exposure").coerceIn(-2f, 2f)
    fun setCameraExposureEv(raw: String) {
        val value = raw.trim().toFloatOrNull() ?: return
        edit { putFloat("camera_exposure", value.coerceIn(-2f, 2f)) }
    }
    fun setCameraKbps(kbps: Int) {
        edit { putInt("camera_kbps", kbps.coerceIn(250, 8000)) }
    }

    // Experimental Android dashboard lock: hide system bars and return from other apps/Recents so a casual
    // user does not remain away from the dashboard. Persisted, off by default, with recovery routes and a
    // startup escape window before enforcement is reasserted (see KioskController and PaneldService).
    val kioskLock: Boolean get() = boolPref("kiosk_lock")
    fun setKioskLock(on: Boolean) {
        edit { putBoolean("kiosk_lock", on) }
    }
    fun commitKioskLock(on: Boolean): Boolean = synchronized(CONFIG_LOCK) {
        durableCommit { putBoolean("kiosk_lock", on) }
    }

    // Voice assistant: on-panel wake-word listener + Home Assistant Assist pipeline routing. Off by
    // default (no microphone-capable panel exists yet); the actual pipeline runtime is a separate lane.
    // A durable commit (not the fire-and-forget async `edit`) — the bridge's handleVoiceEnabled must be
    // able to tell a genuine persistence failure apart from success, the same reason kiosk_lock has both
    // setKioskLock and commitKioskLock, so an ON acknowledgement is never reported before it durably
    // held.
    val voiceEnabled: Boolean get() = boolPref("voice_enabled")
    fun commitVoiceEnabled(on: Boolean): Boolean = synchronized(CONFIG_LOCK) {
        durableCommit { putBoolean("voice_enabled", on) }
    }

    /** JSON array of configured wake-word model ids — SettingsRegistry validates and canonicalizes it
     *  before it is ever committed here, so a stored value is always well-formed. */
    val voiceWakeWords: String get() = stringPref("voice_wake_words")
    fun setVoiceWakeWords(json: String) {
        edit { putString("voice_wake_words", json) }
    }

    /** JSON object of wake-word model id to Home Assistant Assist pipeline id (blank = preferred). */
    val voicePipelines: String get() = stringPref("voice_pipelines")
    fun setVoicePipelines(json: String) {
        edit { putString("voice_pipelines", json) }
    }

    val voiceAudioSource: String get() = stringPref("voice_audio_source")
    fun setVoiceAudioSource(source: String) {
        edit { putString("voice_audio_source", source) }
    }

    val voiceSensitivity: String get() = stringPref("voice_sensitivity")
    fun setVoiceSensitivity(sensitivity: String) {
        edit { putString("voice_sensitivity", sensitivity) }
    }

    val voiceMicGainDb: Int get() =
        intPref("voice_mic_gain_db").coerceIn(MicrophoneGain.MIN_DB, MicrophoneGain.MAX_DB)
    fun setVoiceMicGainDb(db: Int) {
        edit { putInt("voice_mic_gain_db", db.coerceIn(MicrophoneGain.MIN_DB, MicrophoneGain.MAX_DB)) }
    }

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

    // HA Companion app auto-manage: when on, ha-paneld installs the minimal Companion if it's
    // missing and updates it when a newer release exists (root panels; the minimal variant has no Play
    // auto-update, so ha-paneld is the only update path). Default off — installing/updating an app is
    // invasive, AND an upstream Companion release can be incompatible with a panel's old Android (e.g.
    // 2026.6.5-minimal crash-loops on Android 8.1/PX30 — a missing android.car class — blanking the
    // dashboard fleet-wide when auto-update was on). Opt in per panel (provision --companion-auto or the
    // MQTT switch); a per-profile known-good version pin is the planned safer gate.
    val companionAutoUpdate: Boolean get() = boolPref("companion_auto_update")
    fun setCompanionAutoUpdate(on: Boolean) {
        edit { putBoolean("companion_auto_update", on) }
    }
    /** Release channel the Companion auto-updater follows — mirrors [updateChannel] for ha-paneld. */
    val companionUpdateChannel: String get() = stringPref("companion_update_channel")
    fun setCompanionUpdateChannel(ch: String) {
        val v = if (ch.trim().lowercase().startsWith("pre")) "prerelease" else "stable"
        edit { putString("companion_update_channel", v) }
    }

    /** Language used by ha-paneld's own interface; `auto` delegates to the locale resolver. */
    val uiLanguage: String get() = stringPref("ui_language")
    fun setUiLanguage(language: String) {
        edit { putString("ui_language", language) }
    }

    // ha-paneld self-update: when on, ha-paneld installs a newer build of ITSELF from GitHub releases on
    // the selected [updateChannel] (root/Shizuku verified-install route; no Play Store on these panels).
    // Default ON only where the runtime exposes verified app install; the Configure schema hides it and
    // PaneldService rechecks capability before any automatic install on unsupported panels. It never
    // auto-DOWNGRADES: running a pre-release while on the stable channel simply waits ("suspended") until
    // stable catches up; the one deliberate move off an rc is an explicit channel switch pre-release→stable.
    val selfUpdate: Boolean get() = boolPref("self_update")
    fun setSelfUpdate(on: Boolean) {
        edit { putBoolean("self_update", on) }
    }
    val updateChannel: String get() = stringPref("update_channel")
    fun setUpdateChannel(ch: String) {
        val v = if (ch.trim().lowercase().startsWith("pre")) "prerelease" else "stable"
        edit { putString("update_channel", v) }
    }

    // System WebView auto-update: when on, ha-paneld advances the WebView to the profile's pinned
    // recommended build (from the webview-mirror release) on the update tick — the same curated pin the
    // too-old auto-heal installs, but proactively rather than only when the engine is broken. **Default
    // OFF**: a provider swap binds per-process (needs a restart) and is more invasive than an app update,
    // and the pin is advanced deliberately by the maintainer (there is no clean upstream feed to chase).
    val webViewAutoUpdate: Boolean get() = boolPref("webview_auto_update")
    fun setWebViewAutoUpdate(on: Boolean) {
        // commit() (not apply()): the natural workflow is "enable, then reboot to let it run", and an
        // async write can be lost if the reboot lands before it flushes to disk.
        editCommit { putBoolean("webview_auto_update", on) }
    }
    // Loop guard: the exact recommended version last auto-installed. If a later tick still doesn't see it
    // as the engine, the provider isn't switching (variant hardware) — don't re-download it daily; a pin
    // bump (new version string) clears the guard. commit() (not apply): must persist across the restart
    // the successful install triggers.
    val webViewAutoLastVersion: String get() = prefs.getString("webview_auto_last_version", "") ?: ""
    fun setWebViewAutoLastVersion(v: String) {
        prefs.edit().putString("webview_auto_last_version", v).commit()
    }

    // Network-adb persist INTENT (the switch). ha-paneld re-asserts adb-tcp at boot/reconnect when this
    // is true (some firmwares strip persist.adb.tcp.port at boot), and only tears adb down on OFF if it
    // was ha-paneld that turned it on — never disabling adb another mechanism started.
    val networkAdbEnabled: Boolean get() = prefs.getBoolean("network_adb_enabled", false)
    fun setNetworkAdbEnabled(on: Boolean): Boolean = synchronized(CONFIG_LOCK) {
        if (on && prefs.getBoolean(SECURITY_MODE_PREF, false)) return@synchronized false
        durableCommit { putBoolean("network_adb_enabled", on) }
    }

    // The ha-paneld version whose discovery set was last published to HA. On an upgrade (this differs
    // from the running version) MqttBridge prunes any entity a prior version published but this one no
    // longer does — so a refactored-away entity is actively removed from HA, not left as a zombie.
    val lastDiscoveryVersion: String get() = prefs.getString("last_discovery_version", "")!!
    fun setLastDiscoveryVersion(v: String) {
        prefs.edit().putString("last_discovery_version", v).apply()
    }

    // Runtime state for the MQTT update entities, never a user setting. The shape is what Home
    // Assistant was last told about one entity, so a restart can still recreate it when an attribute
    // HA cannot unset has gone; the lease is the wall-clock time a Panel Assistant entry last claimed
    // the ha-paneld update entity (PanelAssistantUpdateLease).
    fun softwareUpdateDiscoveryShape(component: String): String =
        prefs.getString("mqtt_update_shape_$component", "") ?: ""
    fun setSoftwareUpdateDiscoveryShape(component: String, encoded: String) {
        prefs.edit().putString("mqtt_update_shape_$component", encoded).apply()
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
    // The authority the Panel Assistant integration last granted this panel (mqtt, shadow or native),
    // empty before any session. Kept while no session is open, so a Home Assistant outage never lets
    // MQTT command a panel that was cut over to the native transport.
    val panelAssistantAuthority: String get() = prefs.getString("panel_assistant_authority", "") ?: ""
    fun setPanelAssistantAuthority(authority: String) {
        if (authority == panelAssistantAuthority) return
        prefs.edit().putString("panel_assistant_authority", authority).apply()
    }
    // Whether the integration holds this panel's MQTT discovery entities (withdraw) or has released
    // them (announce), empty before any session. Kept beside the authority for the same reason: while
    // it reads withdraw the bridge publishes tombstones instead of discovery on every connect, so an
    // outage or an uninstalled integration never brings back entities the integration replaced.
    val panelAssistantMqttDiscovery: String get() = prefs.getString("panel_assistant_mqtt_discovery", "") ?: ""
    fun setPanelAssistantMqttDiscovery(value: String) {
        if (value == panelAssistantMqttDiscovery) return
        prefs.edit().putString("panel_assistant_mqtt_discovery", value).apply()
    }
    val panelAssistantUpdateOwnerSeenMs: Long get() = prefs.getLong("panel_assistant_update_owner_seen_ms", 0L)
    fun setPanelAssistantUpdateOwnerSeenMs(wallMs: Long) {
        prefs.edit().putLong("panel_assistant_update_owner_seen_ms", wallMs).apply()
    }

    // Per-panel intended "home" dashboard path (e.g. "/lovelace/0"). When set, a reload keeps the hard
    // restart but re-navigates HERE once the frontend is back up, instead of leaving the Companion on its
    // user-default view. Empty = keep current behaviour (cold-start to the Companion default).
    /**
     * The panel's REQUESTED Home Assistant area, by name. Home Assistant's value is canonical over
     * ADOPTED values, but a person's explicit choice is a deliberate local override that adoption must
     * not undo — a panel whose own HA area holds no motion entities may have its area
     * deliberately set to a neighbouring room for auto-sleep sources (2026-07-26). [haAreaUserOverride]
     * records which kind of value this is. Setter is a plain pref write on purpose — adoption must not
     * re-trigger the write-back side effect.
     */
    var haArea: String
        get() = stringPref("ha_area")
        set(value) = synchronized(CONFIG_LOCK) {
            durableCommit { putString("ha_area", value.trim()) }
            Unit
        }

    /**
     * True while [haArea] holds a value a PERSON chose, as opposed to one adopted from Home Assistant.
     * Set when a user saves a non-blank area, cleared when they save blank ("follow Home Assistant") and
     * when Home Assistant comes to agree — an override that matches HA is no longer overriding anything.
     * Without this bit the two kinds of value are indistinguishable, and the convergence pass reverted
     * every deliberate divergence seconds after it was saved (hardware report, 2026-07-26).
     */
    var haAreaUserOverride: Boolean
        get() = prefs.getBoolean(HA_AREA_USER_OVERRIDE_PREF, false)
        set(value) = synchronized(CONFIG_LOCK) {
            durableCommit { putBoolean(HA_AREA_USER_OVERRIDE_PREF, value) }
            Unit
        }

    /** Persist the requested Area and its ownership bit as one live-setting authority. */
    internal fun commitHaArea(value: String, userOverride: Boolean): Boolean = synchronized(CONFIG_LOCK) {
        durableCommit {
            putString("ha_area", value.trim())
            putBoolean(HA_AREA_USER_OVERRIDE_PREF, userOverride)
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
    val dashboardIdleReturnMin: Int get() = intPref("dashboard_idle_return_min")

    /** Built-in renderer: hide the system status + navigation bars (immersive edge-to-edge kiosk).
     *  Swipe-from-edge still transiently reveals them, so an admin is never locked out. */
    val dashboardFullscreen: Boolean get() = boolPref("dashboard_fullscreen")
    fun setDashboardFullscreen(on: Boolean) { edit { putBoolean("dashboard_fullscreen", on) } }
    fun setDashboardIdleReturnMin(min: Int) { edit { putInt("dashboard_idle_return_min", min) } }

    /** Ask Home Assistant's own frontend to enter its native kiosk mode. This is independent of
     * Android fullscreen/dashboard lock and does not inject CSS into the dashboard. */
    val dashboardNativeKiosk: Boolean get() = boolPref("dashboard_native_kiosk")
    fun setDashboardNativeKiosk(on: Boolean) { edit { putBoolean("dashboard_native_kiosk", on) } }

    /** Built-in renderer: who owns the dashboard's light/dark choice — [DashboardTheme.FOLLOW] (Home
     *  Assistant does, which is what [darkMode] supplies a default for) or an explicit Dark/Light this
     *  panel imposes. A stored value this build no longer declares resolves to the default rather than
     *  leaving the renderer with a policy it cannot act on. */
    val dashboardTheme: String get() = DashboardTheme.policy(prefs.getString("dashboard_theme", null))
    fun setDashboardTheme(v: String) { edit { putString("dashboard_theme", DashboardTheme.policy(v)) } }

    /** Built-in renderer: allow Android's overscroll stretch/glow past the top or bottom of the page.
     *  Off by default (a wall panel rarely scrolls; the bounce looks out of place). API-only setting. */
    val dashboardOverscroll: Boolean get() = boolPref("dashboard_overscroll")
    fun setDashboardOverscroll(on: Boolean) { edit { putBoolean("dashboard_overscroll", on) } }

    /** Built-in renderer: dashboard page zoom %. 100 matches the HA Companion's default sizing (which
     *  scales the page by device density), so a switched-over panel keeps its layout. */
    val dashboardZoom: Int get() = intPref("dashboard_zoom")
    fun setDashboardZoom(pct: Int) { edit { putInt("dashboard_zoom", pct) } }

    /**
     * Experimental built-in-renderer entity filter. The exact entity ids deliberately live outside the
     * settings registry: they are a potentially large, installation-specific API input rather than a
     * settled product setting or an HA entity. The dedicated `/api/v1/dashboard/entity-filter` endpoint
     * is the only management surface and reports count/hash rather than echoing the ids.
     */
    val dashboardEntityFilterEnabled: Boolean
        get() = !dashboardEntityDefaultResolverMigrationRequired &&
            !dashboardEntityDefaultResolverRebootstrapPending &&
            entityStateOwnedByCurrent("dashboard_entity_filter_instance") &&
            prefs.getBoolean("dashboard_entity_filter_enabled", false)
    val dashboardEntityFilterIds: List<String>
        get() = if (entityStateOwnedByCurrent("dashboard_entity_filter_instance")) rawDashboardEntityFilterIds else emptyList()
    internal val rawDashboardEntityFilterIds: List<String>
        get() = prefs.getString("dashboard_entity_filter_ids", "").orEmpty()
            .lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().sorted().toList()

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

    /** The evidence namespace currently selected for the configured HA endpoint. A URL-derived key is
     *  used until mDNS can tie the authenticated endpoint to HA's stable core.uuid. */
    val dashboardEntityInstanceKey: String get() = prefs.getString("dashboard_entity_instance", "").orEmpty()
    val dashboardEntityInstanceOrigin: String get() = prefs.getString("dashboard_entity_instance_origin", "").orEmpty()
    val dashboardEntityInstanceUuid: String get() = prefs.getString("dashboard_entity_instance_uuid", "").orEmpty()
    val dashboardEntityDashboardPath: String
        get() = prefs.getString("dashboard_entity_dashboard_path", "").orEmpty()
    val dashboardEntityTargetKey: String
        get() = dashboardEntityInstanceKey.takeIf(String::isNotBlank)?.let {
            dashboardEntityTargetKey(it, dashboardEntityDashboardPath)
        }.orEmpty()

    /**
     * Ownership for USER-AUTHORED state (pins and forced exclusions), which follows the Home Assistant
     * instance and deliberately NOT the dashboard.
     *
     * A learned list is derived data: re-scoping it costs a rescan and it comes back identical. A pin or
     * an exclusion is the operator's own work, cannot be regenerated, and is equally valid on every
     * dashboard of the same instance — so a dashboard change must never silently withdraw it. The
     * instance still bounds it, because an entity id is only meaningful inside one Home Assistant:
     * `light.office` on another instance is an unrelated entity that merely shares a string.
     *
     * Losing an exclusion is the quieter failure of the two. A dropped pin shows up as a card that
     * stops updating; a dropped exclusion silently re-subscribes to something removed on purpose, and
     * the only symptom is the panel slowly getting heavier again.
     */
    private fun entityStateOwnedByInstance(ownerPreference: String): Boolean {
        val ownerKey = dashboardEntityInstanceOf(prefs.getString(ownerPreference, "").orEmpty())
        val currentKey = dashboardEntityInstanceKey
        if (ownerKey.isBlank() && currentKey.isBlank() && dashboardEntityInstanceOrigin.isBlank()) return true
        if (currentKey.isBlank() || ownerKey != currentKey) return false
        return dashboardEntityInstanceOrigin == canonicalHaOrigin(haUrl)
    }

    private fun entityStateOwnedByCurrent(ownerPreference: String): Boolean {
        val current = dashboardEntityTargetKey
        val owner = prefs.getString(ownerPreference, "").orEmpty()
        // Entirely blank selection/owner is the pre-instance-identity compatibility state. Once a URL or
        // dashboard setter records a target change, even legacy unowned state must fail closed immediately.
        if (current.isBlank() && owner.isBlank() && dashboardEntityInstanceOrigin.isBlank() &&
            dashboardEntityDashboardPath.isBlank()) return true
        if (current.isBlank()) return false
        val configuredOrigin = canonicalHaOrigin(haUrl) ?: return false
        val configuredPath = dashboardEntityScopePath(homeDashboard)
        val scopeAgrees = configuredPath == "/" || dashboardEntityDashboardPath == configuredPath
        return current == owner && dashboardEntityInstanceOrigin == configuredOrigin && scopeAgrees
    }

    /** Select the URL-keyed namespace and dashboard after a target change. Existing ownership is left
     *  behind, so a different/unverified HA or dashboard cannot inherit filter state. Null means the
     *  durable preference transaction failed and callers must not proceed with the selected target. */
    fun prepareDashboardEntityInstance(
        origin: String,
        dashboardPath: String,
        legacyKey: String,
    ): String? = synchronized(CONFIG_LOCK) {
        if (legacyKey.isBlank()) return null
        val canonicalOrigin = canonicalHaOrigin(origin) ?: return null
        val normalizedPath = dashboardEntityScopePath(dashboardPath)
        val currentOrigin = dashboardEntityInstanceOrigin
        val currentKey = dashboardEntityInstanceKey
        val currentPath = dashboardEntityDashboardPath
        if (currentKey.isNotBlank() && currentOrigin == canonicalOrigin && currentPath == normalizedPath) return currentKey
        val firstBinding = currentKey.isBlank() && currentOrigin.isBlank() && currentPath.isBlank()
        // A dashboard change at a verified origin retains its stable instance namespace. An origin change
        // must fall back to its URL-derived key until mDNS proves that it is merely an alias.
        val selectedKey = currentKey.takeIf { it.isNotBlank() && currentOrigin == canonicalOrigin } ?: legacyKey
        val selectedUuid = dashboardEntityInstanceUuid.takeIf {
            selectedKey == currentKey && currentOrigin == canonicalOrigin
        }.orEmpty()
        val selectedTarget = dashboardEntityTargetKey(selectedKey, normalizedPath)
        val committed = applyBatch {
            edit {
                putString("dashboard_entity_instance_origin", canonicalOrigin)
                putString("dashboard_entity_instance", selectedKey)
                putString("dashboard_entity_instance_uuid", selectedUuid)
                putString("dashboard_entity_dashboard_path", normalizedPath)
                // Derived owners are re-rooted in the same transaction that roots the path, because
                // nothing else ever would. An install upgrading from route-qualified ownership holds
                // `…:key/lovelace/kiosk` while the new target key is `…:key/lovelace`; doing this only at
                // first binding left every existing install owned by a key that can no longer be
                // constructed, so the filter read as unowned and the renderer closed — and a startup that
                // found the catalogue already synced skipped the rescan meant to repair it, making the
                // hold permanent rather than a single re-learn.
                //
                // Re-rooting is lossless, not a re-learn: learning fetches per dashboard ROOT and
                // extracts from the whole document, so a view and its dashboard yield the same set. The
                // list behind the owner is already correct. A DIFFERENT root is not migrated — that is a
                // genuine dashboard change, and failing closed so it re-learns is the intended behaviour.
                for (preference in listOf("dashboard_entity_filter_instance", "dashboard_entity_applied_instance")) {
                    val owner = prefs.getString(preference, "").orEmpty()
                    val reRoot = when {
                        owner.isBlank() -> firstBinding
                        dashboardEntityInstanceOf(owner) != selectedKey -> false
                        else -> dashboardEntityScopePath(dashboardEntityPathOf(owner)) == normalizedPath
                    }
                    if (reRoot) putString(preference, selectedTarget)
                }
                if (firstBinding && prefs.getString("dashboard_entity_override_instance", "").isNullOrBlank()) {
                    putString("dashboard_entity_override_instance", selectedTarget)
                }
            }
        }
        return selectedKey.takeIf { committed }
    }

    /** Adopt a UUID namespace only if the endpoint is still the one that was authenticated/discovered.
     *  First adoption moves legacy ownership without altering a known-good list; later URL aliases for
     *  the same UUID simply select the already-owned stable key. */
    fun adoptDashboardEntityInstance(
        expectedOrigin: String,
        expectedDashboardPath: String,
        uuid: String,
        legacyKey: String,
        stableKey: String,
    ): Boolean = synchronized(CONFIG_LOCK) {
        if (uuid.isBlank() || legacyKey.isBlank() || stableKey.isBlank()) return false
        val canonicalOrigin = canonicalHaOrigin(expectedOrigin) ?: return false
        val normalizedPath = dashboardEntityScopePath(expectedDashboardPath)
        if (dashboardEntityInstanceOrigin != canonicalOrigin || dashboardEntityDashboardPath != normalizedPath) return false
        val current = dashboardEntityInstanceKey
        if (current != legacyKey && current != stableKey) return false
        val legacyTarget = dashboardEntityTargetKey(legacyKey, normalizedPath)
        val stableTarget = dashboardEntityTargetKey(stableKey, normalizedPath)
        return applyBatch {
            edit {
                putString("dashboard_entity_instance", stableKey)
                putString("dashboard_entity_instance_origin", canonicalOrigin)
                putString("dashboard_entity_instance_uuid", uuid)
                putString("dashboard_entity_dashboard_path", normalizedPath)
                val filterOwner = prefs.getString("dashboard_entity_filter_instance", "").orEmpty()
                if (filterOwner.isBlank() || filterOwner == legacyKey || filterOwner == legacyTarget) {
                    putString("dashboard_entity_filter_instance", stableTarget)
                }
                val appliedOwner = prefs.getString("dashboard_entity_applied_instance", "").orEmpty()
                if (appliedOwner.isBlank() || appliedOwner == legacyKey || appliedOwner == legacyTarget) {
                    putString("dashboard_entity_applied_instance", stableTarget)
                }
                // The filter and applied owners above are DERIVED state, scoped to the dashboard root, so
                // whole-key equality is the right test for them. Operator intent is not: a pin or
                // exclusion is owned by the Home Assistant instance alone. Comparing its whole
                // dashboard-qualified key stranded any intent recorded before a dashboard change — the
                // paths differ, adoption skipped it, and the entries then read as owned by the legacy
                // instance under the stable key: invisible to the operator and overwritten by the next
                // edit. Match on the instance component alone, and keep the owner's own path so the
                // record still says where the intent was captured.
                val overrideOwner = prefs.getString("dashboard_entity_override_instance", "").orEmpty()
                if (overrideOwner.isBlank() || overrideOwner == legacyKey ||
                    dashboardEntityInstanceOf(overrideOwner) == legacyKey
                ) {
                    val capturedPath = dashboardEntityPathOf(overrideOwner).ifBlank { normalizedPath }
                    putString("dashboard_entity_override_instance",
                        dashboardEntityTargetKey(stableKey, capturedPath))
                }
            }
        }
    }

    /** Productized learner request. Distinct from runtime filter-active so first sync can fail open. */
    val dashboardEntityLearningEnabled: Boolean
        get() = boolPref("dashboard_entity_learning")
    fun setDashboardEntityLearningEnabled(enabled: Boolean) { edit { putBoolean("dashboard_entity_learning", enabled) } }

    /** Which evidence sources may automatically change the live subscription. Evidence is still
     * collected when either switch is off so the Entities tab can present it for manual pinning. */
    val dashboardEntityAutoStatic: Boolean
        get() = boolPref("dashboard_entity_auto_static")
    val dashboardEntityAutoRuntime: Boolean
        get() = boolPref("dashboard_entity_auto_runtime")
    fun setDashboardEntityAutoPolicy(staticRefs: Boolean, runtimeRefs: Boolean): Boolean = applyBatch {
        edit {
            putBoolean("dashboard_entity_auto_static", staticRefs)
            putBoolean("dashboard_entity_auto_runtime", runtimeRefs)
        }
    }
    fun commitDashboardEntityLearningEnabled(enabled: Boolean): Boolean = applyBatch {
        setDashboardEntityLearningEnabled(enabled)
        if (!enabled) setDashboardEntityLearningApplied(false)
    }

    /**
     * v220 initially resolved a blank/default dashboard as ordinary Lovelace. That could publish an
     * exact allow-list for a different panel than the frontend rendered. Invalidate that one ambiguous
     * automatic result once, while retaining its ids as inactive rollback/debug evidence, and require a
     * fresh scan using the complete user -> system -> built-in Home resolution chain.
     *
     * [dashboardEntityFilterEnabled] also checks this migration latch, so a failed preference commit
     * cannot briefly reactivate the suspect allow-list.
     */
    internal fun migrateDashboardEntityDefaultResolver(): DashboardEntityDefaultResolverMigration =
        synchronized(CONFIG_LOCK) {
        if (!dashboardEntityDefaultResolverMigrationRequired) return DashboardEntityDefaultResolverMigration.NOT_NEEDED
        val committed = applyBatch { edit {
            putInt(DASHBOARD_ENTITY_DEFAULT_RESOLVER_VERSION_KEY, DASHBOARD_ENTITY_DEFAULT_RESOLVER_VERSION)
            putString(DASHBOARD_ENTITY_DEFAULT_RESOLVER_TARGET_KEY, dashboardEntityTargetKey)
            putString(DASHBOARD_ENTITY_DEFAULT_RESOLVER_PENDING_KEY, dashboardEntityTargetKey)
            putBoolean("dashboard_entity_filter_enabled", false)
            putBoolean("dashboard_entity_learning_applied", false)
            putString("dashboard_entity_applied_instance", dashboardEntityTargetKey)
        } }
        return when {
            !committed -> DashboardEntityDefaultResolverMigration.PERSIST_FAILED
            else -> DashboardEntityDefaultResolverMigration.REBOOTSTRAP
        }
    }

    /**
     * One-time latch for collapsing route-keyed catalogue rows onto their dashboard root.
     *
     * Deliberately a preference latch rather than a schema step: this is a DATA reconciliation, the
     * schema contract forbids ad-hoc conditionals in `onUpgrade`, and a version bump here would collide
     * with the schema work already reserved by another lane. The store migration is idempotent, so a
     * failed commit costs a repeat rather than corruption.
     */
    val dashboardEntityScopeNamespaceMigrationRequired: Boolean
        get() = prefs.getInt("dashboard_entity_scope_namespace_version", 0) < 1

    fun markDashboardEntityScopeNamespaceMigrated(): Boolean =
        applyBatch { edit { putInt("dashboard_entity_scope_namespace_version", 1) } }

    private val dashboardEntityDefaultResolverMigrationRequired: Boolean
        get() {
            val target = dashboardEntityTargetKey
            if (target.isBlank() || !dashboardEntityLearningEnabled ||
                normalizeDashboardEntityPath(homeDashboard) != "/") return false
            // Narrowed, not removed. What this invalidates is an allow-list that may have been derived
            // while a blank dashboard resolved AMBIGUOUSLY — as ordinary Lovelace rather than through
            // the user -> system -> built-in Home chain — and so could describe a different panel than
            // the frontend rendered. A panel whose learning scope is a NAMED dashboard did not derive
            // its list that way, and Auto now retains and reconciles that binding instead of collapsing
            // it, so there is no ambiguity left to discard. Without this, every switch to "Account
            // default" threw away learned entities AND the operator's pins for a dashboard the panel
            // was already using. The protection still stands for the case it was written for: a panel
            // with no established dashboard, where a blank value really could resolve to anything.
            if (dashboardEntityDashboardPath.let { it.isNotBlank() && it != "/" }) return false
            return prefs.getInt(DASHBOARD_ENTITY_DEFAULT_RESOLVER_VERSION_KEY, 0) <
                DASHBOARD_ENTITY_DEFAULT_RESOLVER_VERSION ||
                prefs.getString(DASHBOARD_ENTITY_DEFAULT_RESOLVER_TARGET_KEY, "").orEmpty() != target
        }

    internal val dashboardEntityDefaultResolverRebootstrapPending: Boolean
        get() {
            val target = dashboardEntityTargetKey
            return target.isNotBlank() &&
                prefs.getString(DASHBOARD_ENTITY_DEFAULT_RESOLVER_PENDING_KEY, "").orEmpty() == target
        }

    /** Clear the durable hold only after the replacement subscription has committed successfully. */
    internal fun clearDashboardEntityDefaultResolverRebootstrapPending(): Boolean = synchronized(CONFIG_LOCK) {
        if (!dashboardEntityDefaultResolverRebootstrapPending) return true
        return applyBatch { edit { remove(DASHBOARD_ENTITY_DEFAULT_RESOLVER_PENDING_KEY) } }
    }

    /** Change automatic-learning mode without exposing a half-disabled stream. A fresh enable clears
     * the activation latch and reactivates any current-owner preserved allow-list for safe observation;
     * disabling turns interception off but keeps that list for a later re-enable. */
    fun commitDashboardEntityLearningMode(enabled: Boolean, clearApplied: Boolean): Boolean {
        val restorePreservedFilter = enabled && dashboardEntityFilterIds.isNotEmpty()
        val firstOptIn = enabled && !dashboardEntityLearningEnabled && rawDashboardEntityFilterIds.isEmpty() &&
            prefs.getString("dashboard_entity_applied_instance", "").isNullOrBlank()
        return applyBatch {
            edit {
                putBoolean("dashboard_entity_learning", enabled)
                if (firstOptIn) putBoolean(DASHBOARD_ENTITY_INITIAL_ACTIVATION_PENDING_KEY, true)
                if (clearApplied) {
                    putBoolean("dashboard_entity_learning_applied", false)
                    putString("dashboard_entity_applied_instance", dashboardEntityTargetKey)
                }
                // Keep the old list under its old owner, but disable interception globally. Otherwise
                // switching back to that owner later would silently reactivate a filter after learning
                // was explicitly disabled on another target.
                if (!enabled) {
                    putBoolean("dashboard_entity_filter_enabled", false)
                } else if (restorePreservedFilter) {
                    putBoolean("dashboard_entity_filter_enabled", true)
                }
            }
        }
    }

    /** True after a fresh installation has safely bootstrapped or an observed set was explicitly applied. */
    val dashboardEntityLearningApplied: Boolean
        get() = entityStateOwnedByCurrent("dashboard_entity_applied_instance") &&
            boolPref("dashboard_entity_learning_applied")
    fun setDashboardEntityLearningApplied(applied: Boolean) {
        edit {
            putBoolean("dashboard_entity_learning_applied", applied)
            putString("dashboard_entity_applied_instance", dashboardEntityTargetKey)
        }
    }
    internal val dashboardEntityInitialActivationPending: Boolean
        get() = prefs.getBoolean(DASHBOARD_ENTITY_INITIAL_ACTIVATION_PENDING_KEY, false)
    fun commitDashboardEntityLearningApplied(applied: Boolean): Boolean = applyBatch {
        setDashboardEntityLearningApplied(applied)
    }

    /** Backup-safe expert overrides; the derived catalog and metrics remain rebuildable SQLite state. */
    val dashboardEntityOverrides: Map<String, String>
        get() = if (!entityStateOwnedByInstance("dashboard_entity_override_instance")) emptyMap() else
            stringPref("dashboard_entity_overrides").lineSequence().mapNotNull { line ->
            val marker = line.firstOrNull() ?: return@mapNotNull null
            val id = line.drop(1).trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            when (marker) { '+' -> id to "pinned"; '-' -> id to "forced_exclude"; else -> null }
        }.toMap()

    fun setDashboardEntityOverrides(values: Map<String, String>): Boolean {
        val encoded = values.toSortedMap().mapNotNull { (id, value) ->
            when (value) { "pinned" -> "+$id"; "forced_exclude" -> "-$id"; else -> null }
        }.joinToString("\n")
        return applyBatch { edit {
            putString("dashboard_entity_overrides", encoded)
            putString("dashboard_entity_override_instance", dashboardEntityTargetKey)
        } }
    }

    /** Persist the complete experimental filter atomically so enabled cannot reference a partial list. */
    fun setDashboardEntityFilter(enabled: Boolean, entityIds: Collection<String>): Boolean {
        val normalized = entityIds.asSequence().map(String::trim).filter(String::isNotEmpty)
            .distinct().sorted().joinToString("\n")
        return applyBatch {
            edit {
                putString("dashboard_entity_filter_ids", normalized)
                putBoolean("dashboard_entity_filter_enabled", enabled && normalized.isNotEmpty())
                putString("dashboard_entity_filter_instance", dashboardEntityTargetKey)
            }
        }
    }

    /** Publish an automatic subscription and its activation latch as one owner-scoped transaction. */
    fun commitDashboardEntitySubscription(
        enabled: Boolean,
        entityIds: Collection<String>,
        applied: Boolean,
    ): Boolean {
        val normalized = entityIds.asSequence().map(String::trim).filter(String::isNotEmpty)
            .distinct().sorted().joinToString("\n")
        return applyBatch { edit {
            putString("dashboard_entity_filter_ids", normalized)
            // Automatic learning may intentionally install an empty allow-list after the user ignores
            // an unsafe selector. Enabled-empty means "subscribe nothing", never "fall back unfiltered".
            putBoolean("dashboard_entity_filter_enabled", enabled)
            putBoolean("dashboard_entity_learning_applied", applied)
            putString("dashboard_entity_filter_instance", dashboardEntityTargetKey)
            putString("dashboard_entity_applied_instance", dashboardEntityTargetKey)
            if (applied) remove(DASHBOARD_ENTITY_INITIAL_ACTIVATION_PENDING_KEY)
        } }
    }

    /** Reset rebuildable learning preferences in one commit. The default preserves the known-good live
     * allow-list; an explicit clean-slate reset also clears it without disabling automatic learning. */
    fun commitDashboardEntityEvidenceReset(clearFilter: Boolean): Boolean {
        val target = dashboardEntityTargetKey
        if (target.isBlank()) return false
        return applyBatch { edit {
            putString("dashboard_entity_overrides", "")
            putString("dashboard_entity_override_instance", target)
            putBoolean("dashboard_entity_learning_applied", false)
            putString("dashboard_entity_applied_instance", target)
            remove(DASHBOARD_ENTITY_INITIAL_ACTIVATION_PENDING_KEY)
            if (clearFilter) {
                putString("dashboard_entity_filter_ids", "")
                putBoolean("dashboard_entity_filter_enabled", false)
                putString("dashboard_entity_filter_instance", target)
            }
        } }
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

    /** Commit a promotion policy and any resulting live subscription as one durable preference change.
     *  A null [activeEntityIds] means the current stream is unchanged. */
    fun commitDashboardEntityPromotionPolicy(
        staticRefs: Boolean,
        runtimeRefs: Boolean,
        activeEntityIds: Collection<String>?,
        applied: Boolean,
    ): Boolean {
        val normalized = activeEntityIds?.asSequence()?.map(String::trim)?.filter(String::isNotEmpty)
            ?.distinct()?.sorted()?.joinToString("\n")
        return applyBatch { edit {
            putBoolean("dashboard_entity_auto_static", staticRefs)
            putBoolean("dashboard_entity_auto_runtime", runtimeRefs)
            putBoolean("dashboard_entity_learning_applied", applied)
            putString("dashboard_entity_applied_instance", dashboardEntityTargetKey)
            if (normalized != null) {
                putString("dashboard_entity_filter_ids", normalized)
                putBoolean("dashboard_entity_filter_enabled", applied)
                putString("dashboard_entity_filter_instance", dashboardEntityTargetKey)
            }
        } }
    }

    /** Atomically leave automatic learning and install a manually managed filter for the selected target.
     *  An enabled manual filter must contain at least one id; a target must have been durably prepared. */
    fun commitDashboardManualEntityFilter(enabled: Boolean, entityIds: Collection<String>): Boolean {
        val normalized = entityIds.asSequence().map(String::trim).filter(String::isNotEmpty)
            .distinct().sorted().joinToString("\n")
        val target = dashboardEntityTargetKey
        if (enabled && normalized.isEmpty()) return false
        if (target.isBlank() || canonicalHaOrigin(haUrl) != dashboardEntityInstanceOrigin ||
            dashboardEntityScopePath(homeDashboard).let { it != "/" && it != dashboardEntityDashboardPath }
        ) return false
        return applyBatch { edit {
            putBoolean("dashboard_entity_learning", false)
            putBoolean("dashboard_entity_learning_applied", false)
            putString("dashboard_entity_applied_instance", target)
            putString("dashboard_entity_filter_ids", normalized)
            putBoolean("dashboard_entity_filter_enabled", enabled && normalized.isNotEmpty())
            putString("dashboard_entity_filter_instance", target)
        } }
    }

    /** Immediately hide filter state when the configured endpoint changes. The URL-derived instance key
     *  is selected later by [prepareDashboardEntityInstance], once its legacy key has been calculated. */
    private fun stageDashboardEntityHaUrlChange(editor: SharedPreferences.Editor, nextUrl: String) {
        val currentEndpoint = haUrl.trim().trimEnd('/')
        val nextEndpoint = nextUrl.trim().trimEnd('/')
        if (currentEndpoint != nextEndpoint) clearHaDeviceLink(editor)
        val current = canonicalHaOrigin(haUrl) ?: haUrl.trim().trimEnd('/')
        val next = canonicalHaOrigin(nextUrl) ?: nextUrl.trim().trimEnd('/')
        if (current == next) return
        editor.remove("dashboard_entity_instance")
            .remove("dashboard_entity_instance_uuid")
            .putString("dashboard_entity_instance_origin", next)
    }

    private fun clearHaDeviceLink(editor: SharedPreferences.Editor) {
        editor.remove("ha_device_url").remove("ha_link_target").remove("ha_link_at")
    }

    // The screen-off timeout (ms) seen before we first raised it, so disabling preventIdleDim can restore
    // the firmware default. -1 = not yet captured.
    var savedScreenOffTimeout: Int
        get() = prefs.getInt("saved_screen_off_timeout", -1)
        set(v) {
            prefs.edit().putInt("saved_screen_off_timeout", v).apply()
        }

    // Navigation bar mode: "Off" | "Always on" | "Swipe reveal" | "Native" (NavbarController.MODES).
    // The first three govern ha-paneld's own drawn overlay; "Native" means the firmware's own bar has
    // authority and is only selectable where the profile declares one. Persisted so the mode is
    // restored on boot; a stored value that this panel may no longer use falls back to the default.
    val navbarMode: String
        get() = resolveNavbarMode(
            prefs.getString("navbar_mode", null),
            hasNativeNavbar,
            resourceShowsNavbar(),
            SystemProps.get("persist.smatek.show.navigationbar"),
            profile?.id,
        )

    /** The profile's native-navbar declaration; false whenever no profile is resolved (JVM-test seams). */
    internal val hasNativeNavbar: Boolean get() = profile?.hasNativeNavbar == true

    private fun resourceShowsNavbar(): Boolean? {
        val res = resources
        val id = res?.getIdentifier("config_showNavigationBar", "bool", "android") ?: 0
        return id.takeIf { it != 0 }?.let { runCatching { res?.getBoolean(it) }.getOrNull() }
    }
    fun setNavbarMode(mode: String) {
        edit { putString("navbar_mode", mode) }
    }

    // After an app update the launcher shows the App UI; when the configured renderer is ready, bounce
    // back to the dashboard so it does not linger. MQTT is optional. Default on.
    val autoReturnDashboard: Boolean get() = prefs.getBoolean("auto_return_dashboard", true)
    fun setAutoReturnDashboard(on: Boolean) {
        edit { putBoolean("auto_return_dashboard", on) }
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
    // Forward ha-paneld's OWN process logcat (its Log.* output + the Ktor/HiveMQ SLF4J library logs,
    // all emitted by this app's uid → readable with no READ_LOGS and no root) to a central aggregator
    // (any syslog- or HTTP-ingesting log collector) for fleet-wide debugging without per-panel `adb logcat`. OFF by default
    // with an EMPTY host — a stock panel ships nothing until deliberately configured. LAN-only by intent;
    // lines are redacted (tokens/passwords/URL secrets) before they leave the device. No on-panel UI:
    // set via the HTTP /config endpoint (provision.sh --log-* flags). See logship/LogShipper.
    val logShipEnabled: Boolean get() = boolPref("log_ship_enabled")
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
                ?: logShipAbsentProtocolFallback
                ?: specOf("log_ship_protocol").default,
        )
    /** True only when shipping is enabled AND a sink host is configured. */
    val logShipActive: Boolean get() = logShipEnabled && logShipHost.isNotBlank()
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
    val zigbeeRouterEnabled: Boolean get() = prefs.getBoolean("zigbee_router_enabled", false)
    // True once the user has EXPLICITLY toggled the zigbee switch. Gates boot-reconcile: we only enforce
    // an off-state (killing a vendor-started gateway) when the user actually asked for off — never by our
    // default — so panels relying on stock vendor Zigbee are left untouched until configured.
    val zigbeeRouterConfigured: Boolean get() = prefs.getBoolean("zigbee_router_configured", false)
    fun setZigbeeRouterEnabled(on: Boolean) {
        edit { putBoolean("zigbee_router_enabled", on); putBoolean("zigbee_router_configured", true) }
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
        get() = intPref(SettingsRegistry.RESPONSE_PERCENT_KEY).coerceIn(0, 100)
    fun setAutoBrightnessResponsePercent(value: Int) {
        edit { putInt(SettingsRegistry.RESPONSE_PERCENT_KEY, value.coerceIn(0, 100)) }
    }

    /** Lowest automatic target in user-facing percent; the actuator's visible floor remains absolute. */
    val autoBrightnessMinimumPercent: Int
        get() = intPref("auto_brightness_minimum_percent")
            .coerceIn(SettingsRegistry.MINIMUM_AUTOMATIC_PERCENT, SettingsRegistry.MAX_AUTOMATIC_MINIMUM_PERCENT)
    fun setAutoBrightnessMinimumPercent(value: Int) {
        edit {
            putInt(
                "auto_brightness_minimum_percent",
                value.coerceIn(SettingsRegistry.MINIMUM_AUTOMATIC_PERCENT, SettingsRegistry.MAX_AUTOMATIC_MINIMUM_PERCENT),
            )
        }
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
    @Volatile private var profile: DeviceProfile? = null
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

    private fun buttonBacklightKey(profile: DeviceProfile): String =
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

    fun setHardware(manufacturer: String, model: String) {
        edit { putString("manufacturer", manufacturer); putString("model", model) }
    }

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
    fun setRoomTempOffset(raw: String) {
        val v = raw.trim().toFloatOrNull()?.takeIf { it.isFinite() } ?: return
        val spec = SettingsRegistry.spec("room_temp_offset")
        val lo = spec?.min?.toFloat() ?: -20f
        val hi = spec?.max?.toFloat() ?: 20f
        edit { putFloat("room_temp_offset", v.coerceIn(lo, hi)) }
    }

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

    // --- arrangeable dashboard layout (per-panel; serialized by the web UI, opaque to the backend) ---
    /** Gridstack layout JSON for the Dashboard tab, persisted per-panel (empty = default layout). */
    var uiDashboardLayout: String
        get() = prefs.getString("ui_dashboard_layout", "")!!
        set(v) { prefs.edit().putString("ui_dashboard_layout", v).apply() }

    // --- registry-driven generic access (HTTP config API + bundles + revisions) -----------------
    // These read/write a setting by its [SettingSpec] so the config API, form generation, export and
    // import all go through one typed path instead of ~35 bespoke getters. Typed convenience getters
    // above remain for callers; they read the same durable keys.

    // The [SettingsRegistry] SettingSpec is the SOLE authority for every registry-backed key's default:
    // the typed convenience accessors above read their default through these helpers rather than each
    // re-declaring a literal that must be kept coherent with the spec by hand. A key with no spec is a
    // programming error (surfaces at test time), never a silent fallback to some other literal.
    private fun specOf(key: String): SettingSpec =
        SettingsRegistry.spec(key) ?: error("no SettingSpec registered for '$key'")

    private fun boolPref(key: String): Boolean = prefs.getBoolean(key, specOf(key).defaultBool())
    private fun intPref(key: String): Int = prefs.getInt(key, specOf(key).defaultInt())
    private fun longPref(key: String): Long = prefs.getLong(key, specOf(key).defaultLong())
    private fun floatPref(key: String): Float {
        val spec = specOf(key)
        val default = spec.defaultFloat()
        val persisted = prefs.getFloat(key, default)
        val normalized = (SettingValue.validate(spec, persisted.toString()) as? Validation.Ok)?.normalized
            ?: (SettingValue.validate(spec, default.toString()) as? Validation.Ok)?.normalized
            ?: default.toString()
        return normalized.toFloatOrNull() ?: default
    }
    private fun stringPref(key: String): String = prefs.getString(key, specOf(key).default) ?: specOf(key).default

    /** Current raw string value for a registry key (the spec default if unset). */
    fun getRaw(spec: SettingSpec): String = when (spec.type) {
        SettingType.BOOL -> prefs.getBoolean(spec.key, spec.defaultBool()).toString()
        SettingType.INT -> prefs.getInt(spec.key, spec.defaultInt()).toString()
        SettingType.LONG -> prefs.getLong(spec.key, spec.defaultLong()).toString()
        SettingType.FLOAT -> {
            // Legacy stores and restored databases can bypass current write admission. [floatPref]
            // gives both typed runtime accessors and this raw projection the same validated effective
            // value; read-back deliberately does not mutate persistence.
            (SettingValue.validate(spec, floatPref(spec.key).toString()) as Validation.Ok).normalized
        }
        else -> when (spec.key) {
            "auto_sleep_source" -> autoSleepSource
            "navbar_mode" -> navbarMode
            // A pre-UDP panel has the retired "syslog" spelling on disk. Canonicalize here so the
            // Configure select, the export bundle and the revision diff all see a declared option.
            "log_ship_protocol" -> logShipProtocol
            // Same reason: an alias spelling ("follow") or a value from a build that declared a
            // different option set must present as one of THIS build's declared options.
            "dashboard_theme" -> dashboardTheme
            else -> prefs.getString(spec.key, spec.default) ?: spec.default
        }
    }

    /** Stage a typed write into [editor] (no commit) — used by the transactional bundle import. */
    fun stage(editor: SharedPreferences.Editor, spec: SettingSpec, normalized: String) {
        if (spec.key == "auto_sleep_source" && normalized != autoSleepSource)
            editor.putLong(AUTO_SLEEP_GENERATION_PREF, nextAutoSleepGenerationLocked())
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
     * written by an older shape, using the same [Migrations] chain as bundle import. A fleet self-updates
     * unattended, so the first time a key is renamed or retyped the persisted value must be carried
     * forward, not silently reset to its default. No-op (and cheap) while already current; call once at
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
            val canonical = if (DashboardPath.followsAccountDefault(candidate)) ""
            else DashboardPath.canonical(candidate, preserveRoute = true)
            if (canonical != null && canonical != stored) {
                prefs.edit().putString("home_dashboard", canonical).apply()
            }
        }
    }

    private fun sameOriginDashboardRoute(raw: String): String? =
        sameOriginDashboardRoute(raw, canonicalHaOrigin(haUrl))

    fun migrateLiveStore(): Boolean {
        rerootDashboardEntityOwners()
        canonicalizeStoredHomeDashboard()
        val from = storedSchema
        if (from == SettingsRegistry.SCHEMA) return true
        val specs = SettingsRegistry.SPECS.filterNot { it.readOnly || it.transient }
        val current = specs.associate { it.key to getRaw(it) }
        val (migrated, warnings) = Migrations.migrate(from, current)
        warnings.forEach { Log.w(TAG, "live-store migration: $it") }
        val ed = prefs.edit()
        for (spec in specs) {
            val next = migrated[spec.key] ?: continue
            if (next != current[spec.key]) stage(ed, spec, next)
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
        // A schema-3 live store may have relied on the old true fallbacks without ever materializing
        // them. Preserve that upgrade behavior, while fresh schema-1 stores receive schema-4 defaults.
        if (from == 3) {
            if (!prefs.contains("wake_on_wave")) ed.putBoolean("wake_on_wave", true)
            SettingsRegistry.LEGACY_DEFAULT_ON_HA_EXPOSURES.forEach { key ->
                val exposureKey = "${SettingsRegistry.HA_EXPOSE_PREFIX}$key"
                if (!prefs.contains(exposureKey)) ed.putBoolean(exposureKey, true)
            }
        }
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
        return committed
    }

    /** Whether an HA-capable setting is currently exposed to Home Assistant (per-panel override). */
    fun haExposed(key: String, default: Boolean): Boolean =
        prefs.getBoolean("${SettingsRegistry.HA_EXPOSE_PREFIX}$key", default)
    fun setHaExposed(key: String, on: Boolean) {
        edit { putBoolean("${SettingsRegistry.HA_EXPOSE_PREFIX}$key", on) }
    }

    private fun slug(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "ha_paneld_panel" }

    companion object {
        private const val TAG = "ha-paneld/config"
        /** Every Config wrapper addresses one process-wide SQLite namespace, so transaction ownership
         * must be process-wide as well. */
        private val CONFIG_LOCK = Any()
        private const val SECURITY_MODE_PREF = "device_local_hardened_security"
        private const val SETUP_IDENTITY_CONFIRMED_PREF = "device_local_setup_identity_confirmed"
        private const val SETUP_ENTITY_FILTER_ANSWERED_PREF = "device_local_setup_entity_filter_answered"
        private const val SETUP_HOME_DASHBOARD_CHOSEN_PREF = "device_local_setup_home_dashboard_chosen"
        private const val SETUP_QUESTION_MIGRATION_PREF = "device_local_setup_questions_migrated_v1"
        private const val LOG_SHIP_TCP_DEFAULT_MIGRATION_PREF =
            "device_local_log_ship_tcp_default_migrated_v1"
        private const val SETUP_HOME_DASHBOARD_MIGRATION_PREF = "device_local_setup_home_dashboard_migrated_v2"
        private const val HA_AREA_USER_OVERRIDE_PREF = "device_local_ha_area_user_override"
        private const val SETUP_RENDER_ATTESTED_PREF = "device_local_setup_render_attested"
        private const val SETUP_EVER_COMPLETED_PREF = "device_local_setup_ever_completed"
        private const val MQTT_FAMILY_BROKER_PREF = "device_local_mqtt_family_broker"
        private const val MQTT_FAMILY_IPV4_PREF = "device_local_mqtt_family_ipv4"
        private const val MQTT_ANNOUNCEMENT_BOUNDARY_PREF =
            "device_local_mqtt_announcement_boundary_consumed"
        private const val AUTO_SLEEP_EXCLUSIONS_PREF = "auto_sleep_source_exclusions_v1"
        private const val AUTO_SLEEP_GENERATION_PREF = "auto_sleep_generation_v1"
        private const val MAX_AUTO_SLEEP_EXCLUSIONS_BYTES = 256 * 1024
        private const val LAST_LAUNCH_SCREEN_VERSION_PREF =
            "device_local_last_launch_screen_version"
        private const val POWER_SAFETY_ACKNOWLEDGEMENT_PREF =
            "device_local_power_safety_acknowledgement_v1"
        private const val POWER_SAFETY_ACKNOWLEDGEMENT_PREFS =
            "ha-paneld-power-safety-acknowledgement"
        private const val DASHBOARD_ENTITY_DEFAULT_RESOLVER_VERSION_KEY =
            "dashboard_entity_default_resolver_version"
        private const val DASHBOARD_ENTITY_DEFAULT_RESOLVER_TARGET_KEY =
            "dashboard_entity_default_resolver_target"
        private const val DASHBOARD_ENTITY_DEFAULT_RESOLVER_PENDING_KEY =
            "dashboard_entity_default_resolver_pending"
        private const val DASHBOARD_ENTITY_INITIAL_ACTIVATION_PENDING_KEY =
            "dashboard_entity_initial_activation_pending"
        private const val DASHBOARD_ENTITY_DEFAULT_RESOLVER_VERSION = 1
        private const val PROXIMITY_PROFILE_MIGRATED = "proximity_profile_calibration_migrated"
        internal const val PROFILE_CALIBRATION_PREFS = "ha-paneld-profile-calibration"
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

internal enum class DashboardEntityDefaultResolverMigration {
    NOT_NEEDED,
    REBOOTSTRAP,
    PERSIST_FAILED,
}

/** Dashboard paths are ownership boundaries. Query/fragment state does not identify a Lovelace dashboard. */
internal fun normalizeDashboardEntityPath(raw: String): String {
    val value = raw.trim().ifBlank { "/" }
    val parsedPath = runCatching { java.net.URI(value).path }.getOrNull().orEmpty().ifBlank { value }
    val leading = if (parsedPath.startsWith('/')) parsedPath else "/$parsedPath"
    return leading.replace(Regex("/{2,}"), "/").let { if (it.length > 1) it.trimEnd('/') else it }
}

/** Length-prefixing avoids collisions if a future instance-key encoding contains the separator. */
/**
 * The dashboard an entity-learning scope belongs to, reduced to its ROOT.
 *
 * Learning fetches `lovelace/config` for the dashboard root and extracts entities from the whole
 * document, so every view of one dashboard yields the same learned set. Scoping ownership by the full
 * route would therefore discard a filter that a rescan reproduces byte for byte — the panel would drop
 * its filter and re-learn every time the home view changed, which is precisely what choosing a specific
 * view is for. This is deliberately NOT [normalizeDashboardEntityPath]: that one keeps the full route,
 * because navigation, idle return and no-op save detection must still see a view change as a change.
 */
internal fun dashboardEntityScopePath(raw: String): String {
    val normalized = normalizeDashboardEntityPath(raw)
    val root = normalized.trim('/').substringBefore('/')
    return if (root.isBlank()) "/" else "/$root"
}

/** The dashboard-scope portion of a target key produced by [dashboardEntityTargetKey], or "" if
 *  unparseable. Operator intent is owned by instance, so this component is inert for ownership and
 *  records only where the intent was first captured — which is why adoption preserves it rather than
 *  restamping it with whatever dashboard happens to be configured at adoption time. */
internal fun dashboardEntityPathOf(targetKey: String): String {
    val separator = targetKey.indexOf(':')
    if (separator <= 0) return ""
    val length = targetKey.substring(0, separator).toIntOrNull() ?: return ""
    val start = separator + 1
    if (length <= 0 || start + length > targetKey.length) return ""
    return targetKey.substring(start + length)
}

/** The instance portion of a target key produced by [dashboardEntityTargetKey], or "" if unparseable. */
internal fun dashboardEntityInstanceOf(targetKey: String): String {
    val separator = targetKey.indexOf(':')
    if (separator <= 0) return ""
    val length = targetKey.substring(0, separator).toIntOrNull() ?: return ""
    val start = separator + 1
    if (length <= 0 || start + length > targetKey.length) return ""
    return targetKey.substring(start, start + length)
}

internal fun dashboardEntityTargetKey(instanceKey: String, dashboardPath: String): String {
    val key = instanceKey.trim()
    if (key.isEmpty()) return ""
    val path = dashboardEntityScopePath(dashboardPath)
    return "${key.length}:$key$path"
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
