package io.github.maxlyth.hapaneld

import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.maxlyth.hapaneld.control.AdbController
import io.github.maxlyth.hapaneld.control.AutoBrightnessController
import io.github.maxlyth.hapaneld.control.BootChimeController
import io.github.maxlyth.hapaneld.control.BrightnessController
import io.github.maxlyth.hapaneld.control.CpuController
import io.github.maxlyth.hapaneld.control.LedEffectController
import io.github.maxlyth.hapaneld.control.LedCommandPolicy
import io.github.maxlyth.hapaneld.control.PowerSafetyMutationPolicy
import io.github.maxlyth.hapaneld.hardware.LedEffects
import io.github.maxlyth.hapaneld.util.BrokerEndpoint
import io.github.maxlyth.hapaneld.util.LatestDispatcher
import io.github.maxlyth.hapaneld.util.submit
import io.github.maxlyth.hapaneld.util.HaLink
import io.github.maxlyth.hapaneld.control.NavbarController
import io.github.maxlyth.hapaneld.control.NavbarModeApplyOutcome
import io.github.maxlyth.hapaneld.control.NavbarModeApplyStatus
import io.github.maxlyth.hapaneld.control.NavigateController
import io.github.maxlyth.hapaneld.control.RelayController
import io.github.maxlyth.hapaneld.control.ScreenController
import io.github.maxlyth.hapaneld.control.BuiltinDashboard
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.control.WatchdogController
import io.github.maxlyth.hapaneld.control.TouchSoundController
import io.github.maxlyth.hapaneld.control.VolumeController
import io.github.maxlyth.hapaneld.control.ZigbeeController
import io.github.maxlyth.hapaneld.control.ZigbeeHealthSnapshot
import io.github.maxlyth.hapaneld.config.Capabilities
import io.github.maxlyth.hapaneld.config.SettingValue
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.config.Validation
import io.github.maxlyth.hapaneld.hardware.LedController
import io.github.maxlyth.hapaneld.input.ButtonBus
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantCommand
import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantCommandProcessor
import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantCommandResult
import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantTransportProtocol
import io.github.maxlyth.hapaneld.security.ApprovalBroker
import io.github.maxlyth.hapaneld.security.LocalApprovalBroker
import io.github.maxlyth.hapaneld.security.SensitiveOperation
import io.github.maxlyth.hapaneld.sensors.HaLifecycleEvent
import io.github.maxlyth.hapaneld.sensors.HaLifecycleRuntime
import io.github.maxlyth.hapaneld.sensors.ProximityReportGate
import io.github.maxlyth.hapaneld.storage.StorageHealthRuntime
import io.github.maxlyth.hapaneld.storage.StorageHealthSeverity
import io.github.maxlyth.hapaneld.storage.StorageHealthSnapshot
import io.github.maxlyth.hapaneld.mqtt.HiveMqTransport
import io.github.maxlyth.hapaneld.mqtt.MqttCallbacks
import io.github.maxlyth.hapaneld.mqtt.MqttConnectionLease
import io.github.maxlyth.hapaneld.mqtt.MqttConnectConfig
import io.github.maxlyth.hapaneld.mqtt.MqttFinalPublish
import io.github.maxlyth.hapaneld.metrics.PanelMetrics
import io.github.maxlyth.hapaneld.control.WifiDiagnosticDemand
import io.github.maxlyth.hapaneld.control.WifiDiagnosticSnapshot
import io.github.maxlyth.hapaneld.control.WifiOutageCounts
import io.github.maxlyth.hapaneld.mqtt.MqttTransport
import io.github.maxlyth.hapaneld.mqtt.MqttConnectionGeneration
import io.github.maxlyth.hapaneld.mqtt.MqttFamilyPreference
import io.github.maxlyth.hapaneld.mqtt.MqttAddressFamilyPolicy
import io.github.maxlyth.hapaneld.mqtt.MqttRoutePlanner
import io.github.maxlyth.hapaneld.mqtt.classifyDisconnect
import io.github.maxlyth.hapaneld.mqtt.mqttFamilyBrokerIdentity
import io.github.maxlyth.hapaneld.mqtt.AuthRecovery
import io.github.maxlyth.hapaneld.mqtt.isAuthRecoveryState
import io.github.maxlyth.hapaneld.mqtt.SoftwareCommandOutcome
import io.github.maxlyth.hapaneld.mqtt.SoftwareComponent
import io.github.maxlyth.hapaneld.mqtt.SoftwareDiscoveryShape
import io.github.maxlyth.hapaneld.mqtt.SoftwareDiscoveryStep
import io.github.maxlyth.hapaneld.mqtt.SoftwareUpdateEntities
import io.github.maxlyth.hapaneld.mqtt.SoftwareUpdateInputs
import io.github.maxlyth.hapaneld.mqtt.SoftwareUpdateSources
import io.github.maxlyth.hapaneld.util.HelperClient
import io.github.maxlyth.hapaneld.util.Json
import io.github.maxlyth.hapaneld.util.MonotonicDeadline
import io.github.maxlyth.hapaneld.util.RetirableMutationGate
import io.github.maxlyth.hapaneld.util.ServiceRuntimeOwner
import io.github.maxlyth.hapaneld.util.interruptAndJoin
import io.github.maxlyth.hapaneld.util.shutdownNowAndAwait
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.RejectedExecutionException
import java.security.MessageDigest
import java.util.Locale
import java.util.WeakHashMap
import org.json.JSONObject

/** Independent, production-used map from registry live keys to their concrete MqttBridge effect owner.
 * Keeping dispatch exhaustive over this enum means a newly declared live setting cannot acquire HTTP
 * and MQTT classification merely because both classifications derive from the same registry flag. */
internal enum class LiveSettingEffectOwner(val settingKey: String) {
    WAKE_ON_WAVE("wake_on_wave"),
    AUTO_SLEEP("auto_sleep"),
    AUTO_SLEEP_SOURCE("auto_sleep_source"),
    PREVENT_IDLE_DIM("prevent_idle_dim"),
    WATCHDOG("watchdog_enabled"),
    KIOSK("kiosk_lock"),
    BOOT_CHIME("silence_boot_chime"),
    AUTO_BRIGHTNESS("auto_brightness"),
    TOUCH_SOUND("touch_sound"),
    VOICE("voice_enabled"),
    NETWORK_ADB("network_adb"),
    ZIGBEE("zigbee_router"),
    AUTO_BRIGHTNESS_MINIMUM("auto_brightness_minimum_percent"),
    AUTO_BRIGHTNESS_RESPONSE("auto_brightness_response_percent"),
    AUTO_BRIGHTNESS_HA_ENTITY("auto_brightness_ha_entity"),
    CPU_GOVERNOR("cpu_governor"),
    NAVBAR("navbar_mode"),
    COMPANION_AUTO_UPDATE("companion_auto_update"),
    COMPANION_UPDATE_CHANNEL("companion_update_channel"),
    SELF_UPDATE("self_update"),
    WEBVIEW_AUTO_UPDATE("webview_auto_update"),
    UPDATE_CHANNEL("update_channel"),
    HOME_DASHBOARD("home_dashboard"),
    HA_AREA_PUBLISH_ONLY("ha_area"),
    ;

    companion object {
        private val bySettingKey = entries.associateBy(LiveSettingEffectOwner::settingKey)
        internal val settingKeys: Set<String> = bySettingKey.keys

        fun requireFor(key: String): LiveSettingEffectOwner =
            bySettingKey[key] ?: error("live setting declared without a dispatcher: $key")
    }
}

/**
 * The complete external MQTT surface for registry-backed live settings.
 *
 * This is deliberately narrower than [LiveSettingEffectOwner]: HTTP can apply adaptive-brightness
 * tuning/source fields, while `ha_area` is followed by server-owned HA registry work and its bridge
 * owner is intentionally publish-only. The wildcard subscription must not turn those HTTP-only/no-op
 * owners into undocumented MQTT writers.
 */
internal val externalMqttLiveSettingOwners: Map<String, String> = linkedMapOf(
    "cpu_governor" to "cpu_governor",
    "network_adb" to "network_adb",
    "home_dashboard" to "home_dashboard",
    "navbar" to "navbar_mode",
    "wake_on_wave" to "wake_on_wave",
    "auto_sleep" to "auto_sleep",
    "touch_sound" to "touch_sound",
    "watchdog" to "watchdog_enabled",
    "kiosk_lock" to "kiosk_lock",
    "companion_auto_update" to "companion_auto_update",
    "companion_update_channel" to "companion_update_channel",
    "self_update" to "self_update",
    "webview_auto_update" to "webview_auto_update",
    "update_channel" to "update_channel",
    "silence_boot_chime" to "silence_boot_chime",
    "prevent_idle_dim" to "prevent_idle_dim",
    "zigbee_router" to "zigbee_router",
    "auto_brightness" to "auto_brightness",
    "voice_enabled" to "voice_enabled",
)

/**
 * The key a *failed* external command must still supersede, or null.
 *
 * A handler that persists intent before actuating leaves newer durable truth behind even when the
 * actuation then fails — and that failure throws straight past the supersession step that normally
 * retires the older queued HTTP value. Left alone, startup replay commits the stale HTTP value over the
 * newer external one, inverting last-write-wins on the durable setting.
 *
 * Both arguments are required to agree. [committedKey] alone would let a key committed by an earlier
 * dispatch retire a pending value the current command never touched, and [topicKey] alone would retire
 * one for a command that threw before committing anything — an unauthorized or refused command must
 * leave HTTP intent exactly where it was.
 */
internal fun supersededKeyAfterFailedDispatch(topicKey: String?, committedKey: String?): String? =
    topicKey?.takeIf { it == committedKey }

/** Resolve an admitted MQTT setting topic to the same registry key used by HTTP live application. */
internal fun externalLiveSettingKey(panel: String, topic: String): String? {
    val prefix = "ha-paneld/$panel/"
    if (!topic.startsWith(prefix) || !topic.endsWith("/set")) return null
    val leaf = topic.removePrefix(prefix).removeSuffix("/set")
    return externalMqttLiveSettingOwners[leaf]
}

/**
 * The concrete live-setting owners. [MqttBridge] implements these methods itself, so production has no
 * second key-to-method adapter that can be swapped independently of the behaviour tested by the router.
 */
internal interface LiveSettingHandlers {
    fun handleWakeOnWave(payload: String)
    fun handleAutoSleep(payload: String)
    fun handleAutoSleepSource(payload: String)
    fun handlePreventIdleDim(payload: String, approvalRequired: Boolean = true)
    fun handleWatchdog(payload: String)
    fun handleKiosk(payload: String)
    fun handleSilenceBootChime(payload: String)
    fun handleAutoBright(payload: String)
    fun handleTouchSound(payload: String)
    fun handleVoiceEnabled(payload: String)
    fun handleNetAdb(payload: String)
    fun handleZigbee(payload: String)
    fun handleAutoBrightnessMinimum(payload: String)
    fun handleAutoBrightnessSensitivity(payload: String)
    fun handleAutoBrightnessHaEntity(payload: String)
    fun handleCpuGov(payload: String)
    fun handleNavbar(payload: String)
    fun handleCompanionAuto(payload: String, approvalRequired: Boolean = true)
    fun handleCompanionChannel(
        payload: String,
        previousValue: String? = null,
        approvalRequired: Boolean = true,
    )
    fun handleSelfUpdate(payload: String, approvalRequired: Boolean = true)
    fun handleWebViewAuto(payload: String, approvalRequired: Boolean = true)
    fun handleUpdateChannel(
        payload: String,
        previousValue: String? = null,
        approvalRequired: Boolean = true,
    )
    fun handleHomeDashboard(payload: String, previousValue: String? = null)
    fun handleHaAreaPublishOnly()
}

/** Route one normalized registry value to exactly one typed effect handler. */
internal fun dispatchLiveSetting(
    key: String,
    value: String,
    previousValue: String? = null,
    sensitiveApprovalRequired: Boolean = true,
    handlers: LiveSettingHandlers,
) {
    val onOff = if (SettingValue.parseBool(value) == true) "ON" else "OFF"
    when (LiveSettingEffectOwner.requireFor(key)) {
        LiveSettingEffectOwner.WAKE_ON_WAVE -> handlers.handleWakeOnWave(onOff)
        LiveSettingEffectOwner.AUTO_SLEEP -> handlers.handleAutoSleep(onOff)
        LiveSettingEffectOwner.AUTO_SLEEP_SOURCE -> handlers.handleAutoSleepSource(value)
        LiveSettingEffectOwner.PREVENT_IDLE_DIM -> handlers.handlePreventIdleDim(
            if (sensitiveApprovalRequired) value else onOff,
            sensitiveApprovalRequired,
        )
        LiveSettingEffectOwner.WATCHDOG -> handlers.handleWatchdog(onOff)
        LiveSettingEffectOwner.KIOSK -> handlers.handleKiosk(onOff)
        LiveSettingEffectOwner.BOOT_CHIME -> handlers.handleSilenceBootChime(onOff)
        LiveSettingEffectOwner.AUTO_BRIGHTNESS -> handlers.handleAutoBright(onOff)
        LiveSettingEffectOwner.TOUCH_SOUND -> handlers.handleTouchSound(onOff)
        LiveSettingEffectOwner.VOICE -> handlers.handleVoiceEnabled(onOff)
        LiveSettingEffectOwner.NETWORK_ADB -> handlers.handleNetAdb(onOff)
        LiveSettingEffectOwner.ZIGBEE -> handlers.handleZigbee(onOff)
        LiveSettingEffectOwner.AUTO_BRIGHTNESS_MINIMUM -> {
            requireNotNull(value.toIntOrNull()) { "$key requires a normalized integer" }
            handlers.handleAutoBrightnessMinimum(value)
        }
        LiveSettingEffectOwner.AUTO_BRIGHTNESS_RESPONSE -> {
            requireNotNull(value.toIntOrNull()) { "$key requires a normalized integer" }
            handlers.handleAutoBrightnessSensitivity(value)
        }
        LiveSettingEffectOwner.AUTO_BRIGHTNESS_HA_ENTITY -> handlers.handleAutoBrightnessHaEntity(value)
        LiveSettingEffectOwner.CPU_GOVERNOR -> handlers.handleCpuGov(value)
        LiveSettingEffectOwner.NAVBAR -> handlers.handleNavbar(value)
        LiveSettingEffectOwner.COMPANION_AUTO_UPDATE ->
            handlers.handleCompanionAuto(onOff, sensitiveApprovalRequired)
        LiveSettingEffectOwner.COMPANION_UPDATE_CHANNEL ->
            handlers.handleCompanionChannel(value, previousValue, sensitiveApprovalRequired)
        LiveSettingEffectOwner.SELF_UPDATE -> handlers.handleSelfUpdate(onOff, sensitiveApprovalRequired)
        LiveSettingEffectOwner.WEBVIEW_AUTO_UPDATE ->
            handlers.handleWebViewAuto(onOff, sensitiveApprovalRequired)
        LiveSettingEffectOwner.UPDATE_CHANNEL ->
            handlers.handleUpdateChannel(value, previousValue, sensitiveApprovalRequired)
        LiveSettingEffectOwner.HOME_DASHBOARD -> handlers.handleHomeDashboard(value, previousValue)
        LiveSettingEffectOwner.HA_AREA_PUBLISH_ONLY -> handlers.handleHaAreaPublishOnly()
    }
}

/**
 * A live-setting handler failed because this panel has no path to the hardware at all.
 *
 * The classification is thrown rather than returned because the command dispatcher between the handler
 * and the live-setting journal reports one boolean per command. Throwing carries the distinction across
 * that boundary without giving every MQTT command a typed result it has no use for; on the plain MQTT
 * command path, where nothing is journalled, it is an ordinary failure.
 */
internal class LiveSettingUnavailableException(val key: String) :
    IllegalStateException("$key has no apply path on this hardware")

/** Fail a handler while preserving whether the attempt found no path at all. */
internal fun requireControlApplied(
    key: String,
    outcome: io.github.maxlyth.hapaneld.control.ControlApplyOutcome,
    message: () -> String,
) {
    when (outcome) {
        io.github.maxlyth.hapaneld.control.ControlApplyOutcome.APPLIED -> Unit
        io.github.maxlyth.hapaneld.control.ControlApplyOutcome.UNAVAILABLE ->
            throw LiveSettingUnavailableException(key)
        io.github.maxlyth.hapaneld.control.ControlApplyOutcome.FAILED -> error(message())
    }
}

internal fun liveSettingApplyResult(
    result: MqttCommandDispatcher.RunResult,
    unavailable: () -> Boolean = { false },
): LiveSettingApplyResult = when {
    result.admission == MqttCommandDispatcher.Admission.CLOSED ||
        result.admission == MqttCommandDispatcher.Admission.REJECTED ->
        LiveSettingApplyResult.DEFERRED
    result.execution == MqttCommandDispatcher.Execution.PENDING ->
        LiveSettingApplyResult.DEFERRED
    result.execution == MqttCommandDispatcher.Execution.SUCCEEDED ->
        LiveSettingApplyResult.APPLIED
    // Only a handler that ran and failed can report unavailability. Superseded and never-admitted work
    // observed no hardware at all, so it stays an ordinary retryable failure whatever the flag says.
    result.execution == MqttCommandDispatcher.Execution.FAILED && unavailable() ->
        LiveSettingApplyResult.UNAVAILABLE
    else -> LiveSettingApplyResult.FAILED
}

internal fun liveSettingApplication(
    result: MqttCommandDispatcher.RunResult,
    unavailable: () -> Boolean = { false },
): LiveSettingApplication {
    val initial = liveSettingApplyResult(result, unavailable)
    if (result.execution != MqttCommandDispatcher.Execution.PENDING) {
        return LiveSettingApplication.immediate(initial)
    }
    return LiveSettingApplication(initial) { observer ->
        result.observeLateCompletion { terminal ->
            observer(
                liveSettingApplyResult(
                    MqttCommandDispatcher.RunResult(result.admission, terminal),
                    unavailable,
                ),
            )
        }
    }
}

internal fun applyAutoSleepSetting(
    desired: Boolean,
    write: (Boolean) -> AutoSleepWriteResult,
    onCommitted: (Boolean) -> Unit,
) {
    when (write(desired)) {
        AutoSleepWriteResult.UNCHANGED -> Unit
        AutoSleepWriteResult.COMMITTED -> onCommitted(desired)
        AutoSleepWriteResult.FAILED -> error("auto_sleep setting commit failed")
    }
}

internal fun hiddenReadOnlyStateTopic(key: String, panel: String): String? =
    SettingsRegistry.spec(key)?.ha?.takeIf { it.readOnly }?.stateTopic(panel)

internal const val MQTT_MEASUREMENT_REFRESH_AFTER_ACK_MS = 5L * 60L * 1_000L

/** A finite opt-in threshold keeps refreshes on graphable measurements rather than every state topic. */
internal fun mqttMeasurementRefreshAfterAckMs(key: String): Long? =
    SettingsRegistry.spec(key)?.ha
        ?.takeIf { it.periodicRefresh }
        ?.let { MQTT_MEASUREMENT_REFRESH_AFTER_ACK_MS }

internal fun mqttMeasurementPayloadIsRefreshable(payload: String): Boolean =
    payload.toDoubleOrNull()?.isFinite() == true

private val WIFI_DIAGNOSTIC_KEYS = setOf("diag_wifi_ssid", "diag_wifi_rssi")

/** Whether the published count is a total or a known lower bound, so HA never records a floor as exact. */
internal fun wifiOutageMqttAttributes(counts: WifiOutageCounts): String =
    JSONObject().put("is_lower_bound", counts.saturated).toString()

/**
 * What the outage-attributes topic should carry right now.
 *
 * The attributes share the count's opt-in: hiding the sensor must CLEAR the retained payload rather
 * than leave a stale `is_lower_bound` alive in the broker for an entity nobody exposed. Pure so the
 * expose/hide/reconnect boundary is checked by behaviour, not by reading the bridge's source.
 */
internal fun wifiOutageAttributeObservation(
    exposed: Boolean,
    counts: WifiOutageCounts?,
): io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation =
    if (!exposed || counts == null) {
        io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation.Unavailable
    } else {
        io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation.Known(wifiOutageMqttAttributes(counts))
    }

/** Path-free, bounded Home Assistant attributes derived from the shared immutable snapshot. */
internal fun storageHealthMqttAttributes(snapshot: StorageHealthSnapshot): String {
    val probeRan = snapshot.checkedAtMillis > 0L
    val capacityKnown = probeRan && snapshot.totalBytes > 0L
    val sqliteMetricsKnown = probeRan && snapshot.pageSizeBytes > 0L && snapshot.pageCount > 0L
    val databaseFilesBytes = listOf(
        snapshot.mainDatabaseBytes,
        snapshot.walBytes,
        snapshot.sidecarBytes,
    ).fold(0L) { total, value ->
        if (value > Long.MAX_VALUE - total) Long.MAX_VALUE else total + value
    }
    fun valueOrNull(value: Any?, known: Boolean = probeRan): Any? = if (known) value else JSONObject.NULL
    return JSONObject()
        .put("storage_pressure", snapshot.pressureSeverity.name.lowercase(Locale.ROOT))
        .put("failure_category", snapshot.databaseFailureKind?.name?.lowercase(Locale.ROOT) ?: JSONObject.NULL)
        .put("failure_operation", snapshot.databaseFailureOperationLabel ?: JSONObject.NULL)
        .put("auto_vacuum", snapshot.autoVacuumMode.name.lowercase(Locale.ROOT))
        .put("usable_bytes", valueOrNull(snapshot.usableBytes, capacityKnown))
        .put("total_bytes", valueOrNull(snapshot.totalBytes, capacityKnown))
        .put("used_percent", if (capacityKnown) snapshot.usedPercent ?: JSONObject.NULL else JSONObject.NULL)
        .put("main_database_bytes", valueOrNull(snapshot.mainDatabaseBytes))
        .put("wal_bytes", valueOrNull(snapshot.walBytes))
        .put("database_sidecar_bytes", valueOrNull(snapshot.sidecarBytes))
        .put("database_files_bytes", valueOrNull(databaseFilesBytes))
        .put("page_size_bytes", valueOrNull(snapshot.pageSizeBytes, sqliteMetricsKnown))
        .put("page_count", valueOrNull(snapshot.pageCount, sqliteMetricsKnown))
        .put("freelist_count", valueOrNull(snapshot.freelistCount, sqliteMetricsKnown))
        .put("schema_version", valueOrNull(snapshot.schemaVersion, sqliteMetricsKnown && snapshot.schemaVersion > 0))
        .put("quick_check", snapshot.quickCheck.name.lowercase(Locale.ROOT))
        .put("checked_at_epoch_seconds", valueOrNull(snapshot.checkedAtMillis / 1_000L))
        .toString()
}

internal fun diagnosticObservation(
    key: String,
    exposed: Boolean,
    value: String?,
): io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation = when {
    key in WIFI_DIAGNOSTIC_KEYS && (!exposed || value == null) ->
        io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation.Unavailable
    !exposed -> io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation.Unknown
    else -> io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation.Known(value ?: "unknown")
}

internal enum class LearnedProximityRefresh { SKIPPED, ELIGIBLE, INELIGIBLE }

/** Service-owned policy projection for HA's binary activity entity. The entity is deliberately
 * binary: HA's recorder/history chart is the useful historical visualization, while attributes retain
 * only bounded categorical context for diagnosing why the policy is active or unavailable. */
internal data class AutoSleepActivitySnapshot(
    val holdingAwake: Boolean = false,
    val policyHealthy: Boolean = false,
    val reason: String = "unavailable",
    val learnedDelay: String = "unknown",
    val sourceCount: Int = 0,
    val phase: String = "unavailable",
    val manualSuppression: Boolean = false,
)

internal data class AutoSleepMqttProjection(
    val state: String,
    val attributes: String,
    val availability: String,
)

internal data class AutoSleepMqttPublication(
    val topic: String,
    val payload: String,
    val retain: Boolean = true,
)

/** Discovery availability for an entity that is unavailable both when the panel is offline and when its
 *  own precondition does not hold — `all` mode, so either topic going offline is enough. */
internal fun dualAvailabilityFragment(
    panelAvailabilityTopic: String,
    entityAvailabilityTopic: String,
): String = """"availability":[{"topic":"$panelAvailabilityTopic","payload_available":"online","payload_not_available":"offline"},{"topic":"$entityAvailabilityTopic","payload_available":"online","payload_not_available":"offline"}],"availability_mode":"all""""

internal fun autoSleepAvailabilityFragment(
    panelAvailabilityTopic: String,
    policyAvailabilityTopic: String,
): String = dualAvailabilityFragment(panelAvailabilityTopic, policyAvailabilityTopic)

/**
 * The device identifiers one discovery payload publishes, in order.
 *
 * Home Assistant matches a device on ANY identifier in the set and UNIONS what it did not already
 * hold, so every member of this list is a permanent claim on whichever device it reaches.
 *
 * - `ha-paneld-<panelId>` is the historical primary and is always present: it is what an existing
 *   registration matches on across an upgrade, which is what keeps the user's customisation.
 * - `ha-paneld-uid-<deviceUid>` is this installation's minted identity. It is what makes a later
 *   panel_id change re-attach to the same device rather than mint a duplicate.
 * - `ha-paneld-aid-<androidId>` is the retired identifier from #155 and is emitted ONLY while
 *   [Config.legacyAidBridgePending] — that is, at most once, by an installation that may already be
 *   registered under it. It is cloned across a factory image, so publishing it routinely is what
 *   merged whole fleets into one device, and republishing it would re-merge them the moment a
 *   merged device is deleted.
 *
 * A blank member is omitted rather than published as a bare prefix: `ha-paneld-uid-` would itself be
 * a shared identifier, and would merge every panel that could not read its own.
 */
internal fun mqttDeviceIdentifiers(
    panelId: String,
    deviceUid: String,
    legacyAndroidId: String,
): List<String> = buildList {
    add("ha-paneld-$panelId")
    deviceUid.trim().takeIf(String::isNotEmpty)?.let { add("ha-paneld-uid-$it") }
    legacyAndroidId.trim().takeIf(String::isNotEmpty)?.let { add("ha-paneld-aid-$it") }
}

internal fun autoSleepMqttProjection(snapshot: AutoSleepActivitySnapshot): AutoSleepMqttProjection {
    fun category(raw: String): String = raw.trim().lowercase(java.util.Locale.ROOT)
        .replace(Regex("[^a-z0-9_-]+"), "_")
        .trim('_')
        .take(48)
        .ifBlank { "unknown" }
    return AutoSleepMqttProjection(
        state = if (snapshot.holdingAwake) "ON" else "OFF",
        attributes = "{" +
            "\"reason\":${Json.str(category(snapshot.reason))}," +
            "\"learned_delay\":${Json.str(category(snapshot.learnedDelay))}," +
            "\"source_count\":${snapshot.sourceCount.coerceAtLeast(0)}," +
            "\"phase\":${Json.str(category(snapshot.phase))}," +
            "\"manual_suppression\":${snapshot.manualSuppression}" +
            "}",
        availability = if (snapshot.policyHealthy) "online" else "offline",
    )
}

internal fun autoSleepMqttPublications(
    panel: String,
    exposed: Boolean,
    snapshot: AutoSleepActivitySnapshot,
): List<AutoSleepMqttPublication> {
    val stateTopic = "ha-paneld/$panel/auto_sleep_activity/state"
    val attributesTopic = "ha-paneld/$panel/auto_sleep_activity/attributes"
    val availabilityTopic = "ha-paneld/$panel/auto_sleep_activity/availability"
    if (!exposed) return listOf(
        AutoSleepMqttPublication(stateTopic, ""),
        AutoSleepMqttPublication(attributesTopic, ""),
        AutoSleepMqttPublication(availabilityTopic, ""),
    )
    val projection = autoSleepMqttProjection(snapshot)
    val state = AutoSleepMqttPublication(stateTopic, projection.state)
    val attributes = AutoSleepMqttPublication(attributesTopic, projection.attributes)
    val availability = AutoSleepMqttPublication(availabilityTopic, projection.availability)
    return if (snapshot.policyHealthy) listOf(state, attributes, availability)
    else listOf(availability, state, attributes)
}

internal fun cameraSnapshotUrlTopic(panel: String): String = "ha-paneld/$panel/camera_snapshot/url"

internal fun cameraSnapshotAvailabilityTopic(panel: String): String =
    "ha-paneld/$panel/camera_snapshot/availability"

/** The snapshot image entity's discovery payload. Built here rather than inline in the announcement so
 * its shape is a fact a test can read: a URL topic, never an image topic, because image bytes on the
 * broker would be a frame stored outside the panel, which the camera contract forbids. */
internal fun cameraSnapshotDiscoveryJson(panel: String, availJson: String, deviceJson: String): String =
    """{"name":"Camera snapshot (experimental)","object_id":"${panel}_camera_snapshot","unique_id":"${panel}_camera_snapshot","url_topic":"${cameraSnapshotUrlTopic(panel)}","icon":"mdi:camera",$availJson,$deviceJson}"""

/** One retained publication for the snapshot image entity. Retained because Home Assistant must find the
 * URL and the availability again after its own restart, without the panel having to notice. */
internal data class CameraSnapshotPublication(val topic: String, val payload: String)

/**
 * What the snapshot image entity publishes for one observation of the camera surface.
 *
 * The URL topic never carries an empty payload while the entity exists: Home Assistant validates every
 * message on it as a URL and logs an error for anything else, so "the camera is off" is said on the
 * availability topic instead of by clearing the URL. Home Assistant drops its cached frame on every
 * message, so republishing the URL is what makes an open card fetch a new one; [refreshUrl] is therefore
 * asked only on a fresh enable and on announcement, and never on a timer, because a periodic republish
 * would turn a card somebody left open into a schedule the panel chose.
 *
 * [announced] false means this profile declares no camera and the entity was not published. The retained
 * URL is then cleared and availability goes offline, so a panel that moved to camera-less hardware leaves
 * no readable address behind for the entity the discovery prune is removing.
 */
/** The `image` discovery payload for the camera snapshot: the discovery document while the entity is
 *  announced, and the empty retained tombstone while it is not. The withdrawal has to be published
 *  rather than skipped, because a config topic written earlier in the same bridge generation stays in
 *  Home Assistant until something overwrites it. Pairs with [cameraSnapshotPublications], which
 *  withdraws the retained URL and availability for the same transition. */
internal fun cameraSnapshotConfigPayload(announced: Boolean, discoveryJson: () -> String): String =
    if (announced) discoveryJson() else ""

internal fun cameraSnapshotPublications(
    panel: String,
    announced: Boolean,
    enabled: Boolean,
    url: String?,
    refreshUrl: Boolean,
): List<CameraSnapshotPublication> {
    val urlTopic = cameraSnapshotUrlTopic(panel)
    val availabilityTopic = cameraSnapshotAvailabilityTopic(panel)
    if (!announced) return listOf(
        CameraSnapshotPublication(availabilityTopic, "offline"),
        CameraSnapshotPublication(urlTopic, ""),
    )
    val live = url?.takeIf { enabled && it.isNotBlank() }
    val availability = CameraSnapshotPublication(
        availabilityTopic, if (live != null) "online" else "offline",
    )
    // The URL precedes the online edge for the reason proximity's retained state does: Home Assistant
    // must not see the entity become available while the topic still holds an earlier address.
    return if (refreshUrl && live != null) {
        listOf(CameraSnapshotPublication(urlTopic, live), availability)
    } else listOf(availability)
}

/** A mode-change signal carries no truth. The admitted bridge generation samples the service-owned
 * authority immediately before it clears stale state and schedules fresh discovery. */
internal fun refreshLearnedProximityFromAuthority(
    admitted: Boolean,
    eligible: () -> Boolean,
    clearIneligibleState: () -> Unit,
    reannounce: () -> Unit,
): LearnedProximityRefresh {
    if (!admitted) return LearnedProximityRefresh.SKIPPED
    val current = runCatching(eligible).getOrDefault(false)
    if (!current) clearIneligibleState()
    reannounce()
    return if (current) LearnedProximityRefresh.ELIGIBLE else LearnedProximityRefresh.INELIGIBLE
}

/** Sticky retirement evidence. Mutation owners must drain synchronously before shared hardware can be
 * dismantled; retained final publication and transport disconnect may finish on the transport owner. */
internal class MqttRetirement {
    val ownersDrained = CompletableFuture<Boolean>()
    val finalization = CompletableFuture<Unit>()
}

/** Kiosk ON is durable only after privileged actuation accepts it; OFF remains fail-safe and retryable. */
internal fun applyAcknowledgedKioskSetting(
    on: Boolean,
    actuate: (Boolean) -> Boolean,
    persist: (Boolean) -> Boolean,
    reconcile: () -> Unit,
): Boolean {
    if (on) {
        if (!runCatching { actuate(true) }.getOrDefault(false)) return false
        if (!runCatching { persist(true) }.getOrDefault(false)) {
            // Actuation owns a durable recovery marker. Roll back local enforcement and retry
            // uncertain platform cleanup without ever acknowledging an ON that config did not commit.
            // SharedPreferences may update its in-memory map even when commit() reports disk failure,
            // so explicitly restore OFF before cleanup as well as returning a failed acknowledgement.
            runCatching { persist(false) }
            runCatching { actuate(false) }
            return false
        }
    } else {
        // OFF is fail-safe: persistence failure must never prevent local/persistent cleanup.
        val persisted = runCatching { persist(false) }.getOrDefault(false)
        runCatching { actuate(false) }
        if (!persisted) return false
    }
    runCatching(reconcile)
    return true
}

/**
 * voice_enabled ON is durable only once [commit] (the durable [io.github.maxlyth.hapaneld.Config.
 * commitVoiceEnabled], never the fire-and-forget async write) accepts it AND the live bridge generation
 * has a microphone; [reconcile] runs on every path — success, capability refusal, or commit failure —
 * so the voice_enabled channel is never left echoing a value nothing actually took effect for, whether
 * that is a stale retained ON from before a profile switch removed the capability, or an in-memory
 * SharedPreferences value that changed even though its commit() reported failure (the same hazard
 * [applyAcknowledgedKioskSetting] guards against). A `false` return means the caller must report the
 * request as failed/refused rather than applied — see handleVoiceEnabled.
 */
internal fun applyAcknowledgedVoiceEnabled(
    on: Boolean,
    hasMicrophone: Boolean,
    commit: (Boolean) -> Boolean,
    reconcile: () -> Unit,
): Boolean {
    if (on && !hasMicrophone) {
        runCatching(reconcile)
        return false
    }
    if (!runCatching { commit(on) }.getOrDefault(false)) {
        runCatching(reconcile)
        return false
    }
    runCatching(reconcile)
    return true
}

/** Camera OFF is always immediate. Camera ON is admitted only on a camera-capable profile and only
 * after the local approval broker accepts the exact request. The caller performs no state mutation
 * until this function returns, so a missing capability or pending/refused approval cannot pre-arm a
 * hidden camera setting. */
internal fun requireCameraEnableAdmission(
    on: Boolean,
    hasCamera: Boolean,
    authorize: () -> Unit,
) {
    if (!on) return
    check(hasCamera) { "this panel has no camera capability" }
    authorize()
}

/** One non-reentrant lane for HTTP, MQTT, and the on-device escape gesture. The kiosk controller is
 * service-owned, so it can apply synchronously without borrowing the replaceable MQTT dispatcher. */
internal class KioskSettingCoordinator(
    private val canEnable: () -> Boolean,
    private val actuate: (Boolean) -> Boolean,
    private val persist: (Boolean) -> Boolean,
) {
    private val lock = Any()

    fun apply(on: Boolean, reconcile: () -> Unit = {}): Boolean = synchronized(lock) {
        if (on && !canEnable()) return false
        applyAcknowledgedKioskSetting(on, actuate, persist, reconcile)
    }

    fun <T> serialized(block: () -> T): T = synchronized(lock, block)
}

internal fun applyAcknowledgedNavbarMode(
    payload: String,
    previousMode: String,
    actuate: (String) -> CompletableFuture<NavbarModeApplyOutcome>,
    rollback: (expectedMode: String, previousMode: String) -> CompletableFuture<NavbarModeApplyOutcome>,
    persist: (String) -> Boolean,
    reconcile: () -> Unit,
    timeoutMs: Long = NAVBAR_ACK_TIMEOUT_MS,
) {
    fun await(
        future: CompletableFuture<NavbarModeApplyOutcome>,
        label: String,
    ): NavbarModeApplyOutcome = try {
        future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (failure: Exception) {
        throw IllegalStateException("navbar $label acknowledgement failed", failure)
    }

    val outcome = try {
        await(actuate(payload), "actuation")
    } catch (failure: IllegalStateException) {
        // Conditional rollback supersedes this request only if it is still the controller's latest
        // desired mode. A newer HTTP/MQTT request remains authoritative and returns SUPERSEDED here.
        runCatching { await(rollback(payload, previousMode), "timeout rollback") }
        throw failure
    }
    check(outcome.status == NavbarModeApplyStatus.APPLIED) {
        "navbar transition ${outcome.status.name.lowercase()}"
    }
    val persisted = runCatching { persist(outcome.mode) }.getOrDefault(false)
    if (!persisted) {
        val restored = await(rollback(outcome.mode, previousMode), "persistence rollback")
        check(restored.status == NavbarModeApplyStatus.APPLIED ||
            restored.status == NavbarModeApplyStatus.SUPERSEDED
        ) { "navbar persistence failed and rollback ${restored.status.name.lowercase()}" }
        error("navbar persistence failed")
    }
    reconcile()
}

private const val NAVBAR_ACK_TIMEOUT_MS = 20_000L

/**
 * MQTT bridge — the single uniform control API across the fleet. Publishes Home Assistant
 * MQTT-discovery configs so every panel exposes identical entities (the per-hardware HAL is hidden
 * behind them), subscribes to the command topics, and dispatches to the controllers. Best-effort:
 * disabled silently when no broker is configured (the HTTP /play surface works standalone).
 *
 * Entities published (per panel):
 * - `light.<panel>_screen`  — brightness + on/off (on=wake, off=sleep). HA-driven; no on-device loop.
 * - `light.<panel>_led`     — RGB (only if [led].available()).
 * - `text.<panel>_navigate` — URL navigate.
 * - `event.<panel>_button`  — hardware button events (only if [buttonsEnabled]).
 * - `media_player.<panel>_paneld` — TTS/announce (HTTP /play does the work).
 */
/** One atomic broker-progress observation for the watchdog. The connection generation identifies the
 * concrete transport client that produced the timestamp, so a late ACK from a retiring client cannot
 * prove that its replacement connected. */
internal data class MqttBrokerProgress(
    val lastOkMs: Long,
    val connectionGeneration: Long?,
) {
    /** Preserve the liveness timestamp but expose its provenance only while that concrete connection is
     * still current. A queued fallback can otherwise detach an old auto-reconnect after it ACKed. */
    fun forCurrentConnection(currentGeneration: Long?): MqttBrokerProgress =
        if (currentGeneration != null && connectionGeneration == currentGeneration) this
        else copy(connectionGeneration = null)
}

internal data class MqttWatchdogObservation(
    val state: String,
    val progress: MqttBrokerProgress,
    val holdSelectedFamily: Boolean,
    /** Sticky for this bridge runtime; only online+state readiness may set it. */
    val applicationReadyEver: Boolean,
    val announcementProcessRecoveryAvailable: Boolean,
    val recoveryTicket: MqttRecoveryTicket,
)

/** Immutable proof that queued recovery still targets the exact stale bridge epoch it observed. */
internal data class MqttRecoveryTicket(
    val recoveryEpoch: Long,
    val familyConnectAttempt: Long,
    val stagedBrokerIdentity: String? = null,
)

internal enum class MqttRecoveryOutcome { REBUILT, NO_LONGER_NEEDED, NOT_ADMITTED }

private data class MqttSelectedFamilyRoute(
    val brokerIdentity: String,
    val connectAttempt: Long,
    val preferIpv4: Boolean,
)

enum class MqttAddressFamily(val label: String) { IPV4("IPv4"), IPV6("IPv6") }

internal fun mqttAddressFamily(address: java.net.InetAddress?): MqttAddressFamily? = when (address) {
    is java.net.Inet4Address -> MqttAddressFamily.IPV4
    is java.net.Inet6Address -> MqttAddressFamily.IPV6
    else -> null
}

internal fun mqttTransportLabel(tls: Boolean, family: MqttAddressFamily?): String =
    (if (tls) "TLS" else "TCP") + family?.let { "/${it.label}" }.orEmpty()

internal fun mqttAnnouncementRecoveryIdentity(
    brokerIdentity: String,
    panelId: String,
    profileIdentity: String,
    user: String,
    password: String,
    addressFamilyPolicy: String = SettingsRegistry.DEFAULT_MQTT_ADDRESS_FAMILY,
    buildVersionCode: Int = BuildConfig.VERSION_CODE,
): String {
    // Internal candidates share VERSION_NAME. Every installed build is a legitimate new bounded
    // recovery epoch because it may contain the fix for the wedge that spent the previous token.
    val framed = listOf(
        Config.VERSION,
        buildVersionCode.toString(),
        brokerIdentity,
        panelId,
        profileIdentity,
        user,
        password,
        addressFamilyPolicy,
    )
        .joinToString(separator = "") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256")
        .digest(framed.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal enum class MqttAnnouncementReadinessBudgetResult {
    PRESERVED_PENDING_BOUNDARY,
    REARMED,
    CLEAR_FAILED,
}

/** Late readiness cannot rearm a boundary already committed by this still-running process. */
internal fun reconcileMqttAnnouncementReadinessBudget(
    consumedHere: Boolean,
    clearInheritedBoundary: () -> Boolean,
): MqttAnnouncementReadinessBudgetResult = when {
    consumedHere -> MqttAnnouncementReadinessBudgetResult.PRESERVED_PENDING_BOUNDARY
    clearInheritedBoundary() -> MqttAnnouncementReadinessBudgetResult.REARMED
    else -> MqttAnnouncementReadinessBudgetResult.CLEAR_FAILED
}

internal data class MqttRecoverySnapshot(
    val revision: Long,
    /**
     * Advances ONLY on genuine recovery progress: a new connect attempt, real broker progress
     * (CONNACK/PUBACK), or a consumed claim. [revision] advances on every lifecycle write, so it cannot
     * decide staleness — a black-holed family disconnects roughly every socket-connect bound, and using
     * [revision] made the watchdog's staged alternate-family recovery read as "already recovered" on
     * every entry, roll itself back, and pin the dead family indefinitely.
     */
    val recoveryEpoch: Long,
    val state: String,
    val addressFamily: MqttAddressFamily?,
    val familyConnectAttempt: Long,
    val connectionGeneration: Long?,
    val brokerProgress: MqttBrokerProgress,
    val applicationReadyEver: Boolean,
)

/** One atomic, directly testable authority for watchdog observation and stale-recovery admission. */
internal class MqttRecoveryAuthority(
    initialState: String,
    initialProgress: MqttBrokerProgress,
) {
    enum class Claim { CLAIMED, STALE_SAME_ATTEMPT, CONSUMED_BY_NEW_ATTEMPT }
    enum class Status { CURRENT, STALE_SAME_ATTEMPT, CONSUMED_BY_NEW_ATTEMPT }

    private val current = AtomicReference(
        MqttRecoverySnapshot(
            revision = 0L,
            recoveryEpoch = 0L,
            state = initialState,
            addressFamily = null,
            familyConnectAttempt = 0L,
            connectionGeneration = null,
            brokerProgress = initialProgress,
            applicationReadyEver = false,
        ),
    )

    fun snapshot(): MqttRecoverySnapshot = current.get()

    fun ticket(
        snapshot: MqttRecoverySnapshot,
        stagedBrokerIdentity: String? = null,
    ): MqttRecoveryTicket = MqttRecoveryTicket(
        snapshot.recoveryEpoch,
        snapshot.familyConnectAttempt,
        stagedBrokerIdentity,
    )

    fun isCurrent(ticket: MqttRecoveryTicket): Boolean = snapshot().matches(ticket)

    fun status(ticket: MqttRecoveryTicket): Status = snapshot().let { observed ->
        when {
            observed.familyConnectAttempt != ticket.familyConnectAttempt ->
                Status.CONSUMED_BY_NEW_ATTEMPT
            observed.matches(ticket) -> Status.CURRENT
            else -> Status.STALE_SAME_ATTEMPT
        }
    }

    fun updateLifecycle(
        state: String,
        connectionGeneration: Long?,
        applicationReadyEver: Boolean,
    ) = update { previous ->
        previous.copy(
            revision = previous.revision + 1L,
            state = state,
            connectionGeneration = connectionGeneration,
            applicationReadyEver = applicationReadyEver,
        )
    }

    fun updateLifecycleWithAddressFamily(
        state: String,
        connectionGeneration: Long?,
        applicationReadyEver: Boolean,
        addressFamily: MqttAddressFamily?,
    ) = update { previous ->
        previous.copy(
            revision = previous.revision + 1L,
            state = state,
            addressFamily = addressFamily,
            connectionGeneration = connectionGeneration,
            applicationReadyEver = applicationReadyEver,
        )
    }

    fun beginConnectAttempt(state: String, connectionGeneration: Long?): Long {
        while (true) {
            val previous = current.get()
            val attempt = previous.familyConnectAttempt + 1L
            val updated = previous.copy(
                revision = previous.revision + 1L,
                recoveryEpoch = previous.recoveryEpoch + 1L,
                state = state,
                addressFamily = null,
                familyConnectAttempt = attempt,
                connectionGeneration = connectionGeneration,
            )
            if (current.compareAndSet(previous, updated)) return attempt
        }
    }

    fun updateAddressFamily(connectAttempt: Long, addressFamily: MqttAddressFamily?): Boolean {
        while (true) {
            val previous = current.get()
            if (previous.familyConnectAttempt != connectAttempt) return false
            val updated = previous.copy(
                revision = previous.revision + 1L,
                addressFamily = addressFamily,
            )
            if (current.compareAndSet(previous, updated)) return true
        }
    }

    fun updateProgress(progress: MqttBrokerProgress) = update { previous ->
        previous.copy(
            revision = previous.revision + 1L,
            recoveryEpoch = previous.recoveryEpoch + 1L,
            brokerProgress = progress,
        )
    }

    fun claim(ticket: MqttRecoveryTicket): Claim {
        while (true) {
            val observed = current.get()
            if (observed.familyConnectAttempt != ticket.familyConnectAttempt) {
                return Claim.CONSUMED_BY_NEW_ATTEMPT
            }
            if (!observed.matches(ticket)) return Claim.STALE_SAME_ATTEMPT
            val consumed = observed.copy(
                revision = observed.revision + 1L,
                recoveryEpoch = observed.recoveryEpoch + 1L,
            )
            if (current.compareAndSet(observed, consumed)) return Claim.CLAIMED
        }
    }

    private fun update(transform: (MqttRecoverySnapshot) -> MqttRecoverySnapshot) {
        while (true) {
            val previous = current.get()
            if (current.compareAndSet(previous, transform(previous))) return
        }
    }

    private fun MqttRecoverySnapshot.matches(ticket: MqttRecoveryTicket): Boolean =
        recoveryEpoch == ticket.recoveryEpoch && familyConnectAttempt == ticket.familyConnectAttempt
}

/**
 * Cross-layer CONNACK boundary: broker progress becomes observable before the lifecycle can become
 * `announcing`. This makes `announcing`/`connected` with a never-recorded first ACK unreachable from
 * the production connection-event path; no speculative first-ACK timer is needed.
 */
internal fun recordMqttConnack(
    authority: MqttRecoveryAuthority,
    connectionGeneration: Long,
    applicationReadyEver: Boolean,
    addressFamily: MqttAddressFamily?,
    nowMs: Long,
): MqttRecoverySnapshot {
    authority.updateProgress(
        MqttBrokerProgress(nowMs.coerceAtLeast(1L), connectionGeneration),
    )
    authority.updateLifecycleWithAddressFamily(
        state = "announcing",
        connectionGeneration = connectionGeneration,
        applicationReadyEver = applicationReadyEver,
        addressFamily = addressFamily,
    )
    return authority.snapshot()
}

internal fun reconcileRejectedMqttRecovery(
    authority: MqttRecoveryAuthority,
    ticket: MqttRecoveryTicket,
    cancelStaged: () -> Boolean,
): Boolean = when (authority.status(ticket)) {
    MqttRecoveryAuthority.Status.CONSUMED_BY_NEW_ATTEMPT -> true
    MqttRecoveryAuthority.Status.CURRENT,
    MqttRecoveryAuthority.Status.STALE_SAME_ATTEMPT -> cancelStaged()
}

/** One exact app/transport generation pair for off-event-loop connection announcement work. */
internal data class MqttConnectAnnouncement(
    val generation: Long,
    val connection: MqttConnectionLease,
)

/** Latest transport lifecycle event. Callbacks only submit here, so HiveMQ's event loop never waits on
 * the bridge's fair mutation gate (which can legitimately be held across slower hardware work). */
internal sealed interface MqttConnectionEvent {
    data class Connected(
        val connection: MqttConnectionLease,
        val addressFamily: MqttAddressFamily? = null,
    ) : MqttConnectionEvent
    data class Disconnected(
        val connection: MqttConnectionLease?,
        val state: String,
        val causeMessage: String?,
    ) : MqttConnectionEvent
}

internal data class SequencedMqttConnectionEvent(
    val sequence: Long,
    val event: MqttConnectionEvent,
)

internal class MqttConnectionEventDispatcher(
    perform: (SequencedMqttConnectionEvent) -> Unit,
) : AutoCloseable {
    private val sequence = AtomicLong()
    private val worker = LatestDispatcher.singleSlot("mqtt-connection-event", perform)

    fun submit(event: MqttConnectionEvent): LatestDispatcher.Admission {
        val submitted = SequencedMqttConnectionEvent(sequence.incrementAndGet(), event)
        return worker.submit(submitted)
    }

    /** Invalidate queued callbacks from a client an explicit fresh start is about to replace. */
    fun supersede() {
        sequence.incrementAndGet()
    }

    fun isCurrent(event: SequencedMqttConnectionEvent): Boolean = sequence.get() == event.sequence
    override fun close() { worker.closeAndJoin(0L) }
    fun closeAndJoin(timeoutMs: Long): Boolean = worker.closeAndJoin(timeoutMs)
}

/** Heavy connect work has its own conflated owner so a newer lifecycle event remains observable even if
 * one client library call never returns. Tests use this exact production boundary against a broker. */
internal class MqttConnectAnnouncementDispatcher(
    perform: (MqttConnectAnnouncement) -> Unit,
) : AutoCloseable {
    private val worker = LatestDispatcher.singleSlot("mqtt-connect-announcement", perform)

    fun submit(announcement: MqttConnectAnnouncement): LatestDispatcher.Admission = worker.submit(announcement)
    override fun close() { worker.closeAndJoin(0L) }
    fun closeAndJoin(timeoutMs: Long): Boolean = worker.closeAndJoin(timeoutMs)
}

internal fun mqttAutomaticReconnectAllowed(
    classifiedState: String,
    admission: LatestDispatcher.Admission,
): Boolean = classifiedState != "auth-failed" && admission != LatestDispatcher.Admission.CLOSED

/** A current disconnect still owns state when its connected event was conflated before bridge admission. */
internal fun mqttDisconnectOwnsBridgeState(
    activeConnection: MqttConnectionLease?,
    disconnectedConnection: MqttConnectionLease?,
): Boolean = activeConnection == null || activeConnection === disconnectedConnection

/** Two independent broker proofs are required before an MQTT session is application-ready. */
internal class MqttAnnouncementReadiness {
    private var announcement: MqttConnectAnnouncement? = null
    private var onlineAcknowledged = false
    private var stateAcknowledged = false
    private var completed = false

    @Synchronized
    fun begin(value: MqttConnectAnnouncement) {
        announcement = value
        onlineAcknowledged = false
        stateAcknowledged = false
        completed = false
    }

    @Synchronized
    fun acknowledgeOnline(value: MqttConnectAnnouncement): MqttConnectAnnouncement? {
        if (announcement != value || completed) return null
        onlineAcknowledged = true
        return completeIfReady()
    }

    @Synchronized
    fun acknowledgeState(generation: Long): MqttConnectAnnouncement? {
        if (announcement?.generation != generation || completed) return null
        stateAcknowledged = true
        return completeIfReady()
    }

    @Synchronized
    fun clear() {
        announcement = null
        onlineAcknowledged = false
        stateAcknowledged = false
        completed = false
    }

    private fun completeIfReady(): MqttConnectAnnouncement? {
        if (!onlineAcknowledged || !stateAcknowledged) return null
        completed = true
        return announcement
    }
}

internal fun normalizeSelfUpdateChannel(raw: String): String =
    if (raw.trim().trim('"').lowercase(Locale.ROOT).startsWith("pre")) "prerelease" else "stable"

/**
 * Keep an active self-update channel switch out of durable configuration until the service has acquired
 * the operation lane and started the exact-candidate preflight. The service owns the later order:
 * authenticate + database-admit exact staged APK -> commit channel -> consume that same staged APK.
 * With auto-update disabled there is no candidate or package replacement, so the preference is ordinary
 * configuration and may be committed immediately.
 */
internal fun stageSelfUpdateChannelChange(
    current: String,
    requested: String,
    selfUpdateEnabled: Boolean,
    requestAdmittedInstall: (requested: String, previous: String) -> Boolean,
    persist: (String) -> Unit,
    publishCurrent: () -> Unit,
) {
    if (requested == current) {
        publishCurrent()
        return
    }
    if (!selfUpdateEnabled) {
        persist(requested)
        publishCurrent()
        return
    }
    // Starting work is not compatibility proof. The asynchronous owner commits only after the prepared
    // candidate is authenticated and admitted; a busy refusal re-projects unchanged durable truth.
    if (!requestAdmittedInstall(requested, current)) publishCurrent()
}

internal class MqttBridge(
    private val config: Config,
    private val brightness: BrightnessController,
    private val screen: ScreenController,
    private val led: LedController,
    // Drives strobe/blink/pulse on the LED (HA's built-in light `effect`). Service-owned + injected so a
    // bridge rebuild (reconfigure) can never orphan a running effect loop — there is only ever one.
    private val ledEffect: LedEffectController,
    private val navigate: NavigateController,
    private val volume: VolumeController,
    private val system: SystemController,
    // Soft on-screen navbar overlay (select: Off / Always on / Swipe reveal).
    private val navbar: NavbarController,
    // App watchdog (switch): self-heals a dead/abandoned dashboard. Toggling restarts its poll loop.
    private val watchdog: WatchdogController,
    private val touchSound: TouchSoundController,
    private val bootChime: BootChimeController,
    // Zigbee gateway control (Sonoff NSPanel Pro only). Presence is detected lazily on the MQTT
    // thread in publishDiscovery — it costs a su exec, so it must not run on the main thread.
    private val zigbee: ZigbeeController,
    // On-board relays + button LEDs (Smatek S9E). Probed lazily on the MQTT thread.
    private val relay: RelayController,
    // CPU governor + persistent network adb (root/su panels). Probed lazily on the MQTT thread.
    private val cpu: CpuController,
    private val adb: AdbController,
    private val buttonsEnabled: Boolean,
    // Panel has hardware buttons instrumented via the daemon (evdev) — publish the event entity even
    // when the accessibility key capture is off (e.g. the WF1589T power button).
    private val hasEvdevButtons: Boolean,
    // Capability snapshot supplier for availableWhen gating of registry entities (null = no gating,
    // used by tests). Called on the MQTT thread at discovery time, so probes stay off the main thread.
    private val capabilities: (() -> Capabilities)? = null,
    private val hasProximity: Boolean,
    private val hasTemperature: Boolean,
    private val hasHumidity: Boolean,
    // Panel carries a CHT8305 room temp/humidity chip (daemon-read) — gates the opt-in Room sensors.
    private val hasCht8305: Boolean,
    private val hasButtonBacklight: Boolean,
    // The profile-authoritative microphone capability (Capabilities.hasMicrophone, itself sourced from
    // the active device profile), captured per bridge generation exactly like hasCht8305/
    // hasButtonBacklight — a profile switch already forces a fresh bridge (profileIdentity), so this is
    // never stale for longer than that. Gates both the voice_enabled command handler (refuses ON without
    // it) and the voice_enabled/voice_state channels (report OFF rather than echoing stale state), the
    // same defensive pattern hasProximity uses for wake_on_wave — HA discovery availability alone is not
    // a write guard, since the command topic is subscribed unconditionally (the wildcard
    // ha-paneld/$panel/+/set).
    private val hasMicrophone: Boolean = false,
    // Profile-authoritative camera capability. Discovery visibility is not a write guard because the
    // wildcard command subscription still receives direct camera_enabled publications.
    private val hasCamera: () -> Boolean = { false },
    // Optional service-owned adaptive-brightness engine.
    private val autoBright: AutoBrightnessController,
    private val onAutoBrightnessConfigChanged: () -> Unit = {},
    // Service-owned auto-sleep policy projection; sampled on the MQTT worker for HA history.
    private val autoSleepActivity: () -> AutoSleepActivitySnapshot = { AutoSleepActivitySnapshot() },
    // Evaluated for every discovery announcement so DHCP/address changes never leave HA's device-page
    // Visit link pinned to the address captured when the service process started.
    private val configUrl: () -> String? = { null },
    // The panel's own snapshot endpoint, as Home Assistant must dial it. Evaluated per announcement for
    // the same reason as configUrl — a DHCP move must not leave a stale address published. Null when no
    // LAN address is known, which makes the snapshot image entity unavailable rather than wrong.
    private val cameraSnapshotUrl: () -> String? = { null },
    // The camera master switch moved through MQTT. The service performs the same actuation its HTTP
    // reconfigure path performs, so both routes converge on one owner rather than two.
    private val onCameraEnabledChanged: () -> Unit = {},
    // Resolves HA's LAN IP via mDNS to default the broker when none is configured (injected by the
    // service, wired to MdnsAdvertiser). Returns null if HA isn't found / mDNS unavailable.
    private val discoverHaIp: () -> String? = { null },
    // HA's advertised base URL (scheme+host+port) from zeroconf TXT — for the "Open in HA" device link,
    // so we never guess a port/scheme. Null if HA isn't found / advertises no URL.
    private val discoverHaUrl: (configuredBroker: String) -> String? = { null },
    // Trigger an HA Companion app install/update. Injected by the service (needs Context + a
    // coroutine); runs off the MQTT thread. Fired by the update_companion button.
    private val onUpdateCompanion: () -> Unit = {},
    // Trigger a ha-paneld self-update on the configured channel. force=true installs the channel's newest
    // regardless of the version check (the update_paneld button + a pre-release→stable channel switch).
    private val onSelfUpdate: (force: Boolean) -> Unit = {},
    // A channel switch with self-update enabled is a staged install transaction. The service resolves,
    // authenticates and database-admits the exact APK before it durably commits the requested channel;
    // this bridge must not make the preference visible ahead of that proof. False means the shared
    // operation lane was busy, in which case the current state is re-published immediately.
    private val onSelfUpdateChannelChange: (requested: String, previous: String) -> Boolean,
    // Inputs for the MQTT update entities, sampled on this bridge's workers: installed versions,
    // same-policy catalog targets, install-lane ownership and Panel Assistant ownership. Install
    // capability comes from this bridge's own capability snapshot. Null publishes no update entities.
    private val softwareUpdateSources: (() -> SoftwareUpdateSources)? = null,
    // One admitted `install` command with its exact catalog tag already bound. False means the shared
    // destructive-operation lane was busy and nothing started.
    private val onSoftwareInstall: (SoftwareComponent, String) -> Boolean = { _, _ -> false },
    // Home-dashboard commands change the automatic entity-learning ownership target. Notify only after
    // the normalized path is durably visible so the manager can hide/rebuild the correct subscription.
    private val onDashboardTargetChanged: () -> Unit = {},
    // Direct MQTT kiosk commands join the service-owned coordinator used by HTTP and the local escape
    // gesture, instead of creating a second persistence/actuation ordering lane inside this bridge.
    private val onDirectKioskSetting: (Boolean) -> Boolean,
    // A successfully executed external MQTT setting is newer truth than any HTTP generation which
    // timed out ahead of it, even though their dispatcher conflation keys intentionally differ.
    private val onExternalSettingApplied: (String) -> Boolean = { true },
    private val zigbeeHealth: () -> ZigbeeHealthSnapshot = { ZigbeeHealthSnapshot() },
    private val storageHealth: () -> StorageHealthSnapshot = StorageHealthRuntime::snapshot,
    private val onZigbeeExplicitRetry: () -> Unit = {},
    // Current voice-assistant phase for sensor.<panel>_voice_state (io.github.maxlyth.hapaneld.assist.
    // VoiceState.wireValue). Owned by a service-side VoiceStateAuthority the voice-coordinator lane
    // drives; defaults to "off" so a bridge built without that wiring reports the safe default.
    private val voiceState: () -> String = { io.github.maxlyth.hapaneld.assist.VoiceState.OFF.wireValue },
    // A panel-id replaced by reconfiguration. Its discovery and availability are cleared by the NEW
    // connection, so cleanup cannot be lost when the old client is detached or the broker was offline.
    private val stalePanelId: String? = null,
    // Immutable id of the active profile revision (for example "panel.example@<sha256>"). A profile
    // switch can remove capabilities without changing the app version, so it must trigger the same
    // stale-discovery pruning as a core upgrade. Empty preserves the pre-profile marker for callers
    // that have not opted into runtime profiles.
    private val profileIdentity: String = "",
    // Event types declared by the active runtime profile. Discovery must advertise every value this
    // bridge can publish, not only the historical built-in key list.
    private val profileButtonEventTypes: Set<String> = emptySet(),
    // Immutable connection/advertisement identity for this concrete bridge generation. Config remains
    // live for settings that can be re-projected without replacing the broker connection.
    private val runtimePanelId: String = config.panelId,
    private val runtimeFriendlyName: String = config.friendlyName,
    private val runtimeBroker: String = config.mqttBroker,
    private val runtimeMqttUser: String = config.mqttUser,
    private val runtimeMqttPassword: String = config.mqttPassword,
    private val runtimeMqttAddressFamily: String = config.mqttAddressFamily,
    private val wifiDiagnostics: (WifiDiagnosticDemand) -> WifiDiagnosticSnapshot = { WifiDiagnosticSnapshot() },
    private val wifiOutages: () -> WifiOutageCounts? = { null },
    private val learnedProximityEligibility: () -> Boolean = { false },
    // Learned proximity once it is settled; null while the calibration is still loading or has closed, so a
    // transient false is never stated to Home Assistant as a panel without the sensor.
    private val learnedProximityState: () -> Boolean? = { null },
    private val onAutoSleepConfigChanged: (Boolean) -> Unit = {},
    // This bridge generation's right to report lifecycle observations, issued at construction by the
    // service. A bridge can outlive both the service that configured it AND its own replacement on
    // reconfigure, so every observation carries the lease and the runtime drops it once a newer bridge
    // holds one — otherwise a queued callback from a superseded broker session mutates whichever
    // coordinator happens to be installed when it finally runs.
    internal val haLifecycleLease: HaLifecycleRuntime.MqttLease? = null,
    private val transport: MqttTransport = HiveMqTransport(),
) : LiveSettingHandlers {
    private enum class CommandKind { LATEST, ACTION }

    private val haLinkResolutionThread = AtomicReference<Thread?>()
    @Volatile private var reloadNavigationFuture: ScheduledFuture<*>? = null
    private val discoveryCapabilities = MqttDiscoveryCapabilitySource(
        supplier = capabilities,
        onFailure = { error ->
            Log.w(TAG, "capability snapshot failed; publishing conservative discovery", error)
        },
    )
    // Channel shape grows monotonically after a live capability is confirmed. A transient negative
    // snapshot therefore publishes unknown/unavailable state instead of deleting the channel forever.
    private val capabilityShape = MqttCapabilityShape()
    private val capabilityChannelLock = Any()

    /** Broker actually in use — configured, or auto-discovered as `tcp://<ha-ip>:1883`; "" if none. */
    var activeBroker: String = ""
        private set
    // The configured broker string at construction time. Config is a live mutable store, so the service
    // needs this snapshot to decide whether reconfigure is retiring a genuinely different broker.
    internal val configuredBroker: String = runtimeBroker.trim()

    /**
     * Whether THIS bridge generation was built from the given credentials. The bridge swap after a
     * config save is asynchronous, so between the commit and the swap any state this instance reports
     * was earned by the OLD credentials — on a fresh panel that state is the anonymous discovery
     * connect's auth rejection, which the setup wizard then misattributed to the credentials the user
     * had just typed correctly (first hardware walks, 2-for-2). Callers use this to report a stale
     * generation as "connecting" instead of parroting a verdict the new credentials never earned.
     */
    internal fun servesMqttConfiguration(
        broker: String,
        user: String,
        password: String,
        addressFamily: String,
    ): Boolean = runtimeBroker.trim() == broker.trim() && runtimeMqttUser == user &&
        runtimeMqttPassword == password && runtimeMqttAddressFamily == addressFamily

    /** Whether the active connection uses TLS (a ssl:///mqtts:// broker URL). Surfaced on the info page
     *  + /diag so a TLS setup is visible. */
    @Volatile var tlsActive: Boolean = false
        private set

    /** Live connection state for the UI, so an auth failure reads differently from "unreachable":
     *  connected | announcing | auth-failed | unreachable | connecting | discovering | disabled. */
    private val recoveryAuthority = MqttRecoveryAuthority("disabled", MqttBrokerProgress(0L, null))
    val state: String get() = recoveryAuthority.snapshot().state
    private val lifecycle = RetirableMutationGate()
    private val retirement = AtomicReference<MqttRetirement?>()
    private val connectionGeneration = MqttConnectionGeneration()
    private val familyRecoveryLock = Any()
    @Volatile private var selectedFamilyRoute: MqttSelectedFamilyRoute? = null
    @Volatile private var activeConnection: MqttConnectionLease? = null
    private val commandDispatcher = MqttCommandDispatcher(
        onQueueWait = { waitNanos ->
            FeatureCosts.registry.recordSynchronousElapsed(FeatureCostOperation.MQTT_COMMAND_QUEUE_WAIT, waitNanos)
        },
    )
    // Relay/button-LED read-backs leave the command worker and coalesce latest-generation-wins, so a
    // burst costs one privileged read, and a late read-back can never publish behind a newer command:
    // the generation predicate travels into the converger and is re-evaluated under its admission
    // lock, so an observation overtaken mid-flight is withdrawn instead of published.
    // The scheduler is the RAW convergence pump, deliberately not dispatchStateWork: the bridge
    // lifecycle wrapper DROPS tasks after retirement, which would strand the gate's open feature-cost
    // span. Every task runs; cancellation lives inside reconcile, whose own gate no-ops after close().
    private val relayReadbackGate = io.github.maxlyth.hapaneld.mqtt.CommandReadbackGate(
        schedule = { task -> io.github.maxlyth.hapaneld.mqtt.StateConverger.dispatch(task) },
        readback = { key, stillCurrent -> stateConverger.reconcile(key, force = true, admit = stillCurrent) },
    )
    private val adbReassertWorker = LatestDispatcher.singleSlot<Unit>("mqtt-adb-reassert", consume = { adb.reassert() })
    private val discoveryAnnouncementLock = Any()
    private var buttonSubscription: ButtonBus.Subscription? = null

    fun isConnected(): Boolean = state == "connected"
    internal fun heartbeatConnectionGeneration(): Long? =
        if (lifecycle.isOpen()) connectionGeneration.currentOrNull() else null
    internal fun isCurrentHeartbeatConnection(generation: Long): Boolean =
        lifecycle.isOpen() && connectionGeneration.isCurrent(generation)

    /** Monotonic timestamp (elapsedRealtime) of the last publish that the broker actually ACKed, plus
     *  every (re)connect. This is a TRUE liveness signal — unlike [state]/[isConnected], which reflect
     *  only HiveMQ's own connect/disconnect callbacks and stay "connected" on a half-open (CLOSE-WAIT)
     *  socket the broker already dropped. The service watchdog reconnects when this goes stale. 0 until
     *  the first successful connect. */
    private val announcementBudgetLock = Any()
    @Volatile private var announcementRecoveryIdentity: String? = null
    @Volatile private var announcementProcessRecoveryAvailable = false
    val lastOkMs: Long get() = recoveryAuthority.snapshot().brokerProgress.lastOkMs
    internal fun watchdogObservation(): MqttWatchdogObservation {
        val authority = recoveryAuthority.snapshot()
        // Application readiness clears the volatile hold only after both required ACKs. A concurrent
        // hold transition is conservative; every recovery-mutating field comes from one atomic view.
        val holdSelectedFamily = familyPreference.awaitingProgress
        val progress = authority.brokerProgress
            .forCurrentConnection(authority.connectionGeneration)
        return MqttWatchdogObservation(
            state = authority.state,
            progress = progress,
            holdSelectedFamily = holdSelectedFamily,
            applicationReadyEver = authority.applicationReadyEver,
            announcementProcessRecoveryAvailable = announcementProcessRecoveryAvailable,
            recoveryTicket = recoveryAuthority.ticket(authority),
        )
    }

    /** Lifecycle-gated writer: preserve concurrently published broker progress. */
    private fun publishRecoveryLifecycleState(
        state: String,
        applicationReadyEver: Boolean = recoveryAuthority.snapshot().applicationReadyEver,
    ) {
        recoveryAuthority.updateLifecycle(
            state,
            connectionGeneration.currentOrNull(),
            applicationReadyEver,
        )
    }

    private fun publishRecoveryLifecycleStateWithAddressFamily(
        state: String,
        addressFamily: MqttAddressFamily?,
        applicationReadyEver: Boolean = recoveryAuthority.snapshot().applicationReadyEver,
    ) {
        recoveryAuthority.updateLifecycleWithAddressFamily(
            state,
            connectionGeneration.currentOrNull(),
            applicationReadyEver,
            addressFamily,
        )
    }

    /** PUBACK/CONNACK writer: update only progress, preserving lifecycle state from the gate owner. */
    private fun publishRecoveryBrokerProgress(progress: MqttBrokerProgress) {
        recoveryAuthority.updateProgress(progress)
    }

    /** Happy-eyeballs family preference for the NEXT connect. One broker-scoped tuple survives the
     *  controlled process boundary, so recovery does not forget its IPv4 fallback and restart into the
     *  same failing IPv6 route. A broker change invalidates the tuple and resets to IPv6-first. */
    private val familyPreference = MqttFamilyPreference(
        load = config::mqttFamilyPreference,
        persist = config::rememberMqttFamilyPreference,
        clear = config::forgetMqttFamilyPreference,
        onClearFailure = { Log.w(TAG, "could not invalidate the previous broker's MQTT family preference") },
    )
    @Volatile private var lastPublishedConfigUrl: String? = null
    // Whether the last announcement actually published the snapshot image entity. A panel whose profile
    // declares no camera has no such entity, so later switch or availability publishes would be retained
    // strings on a topic nothing reads.
    @Volatile private var cameraSnapshotAnnounced = false

    private val authRecovery = AuthRecovery(jitter = { base, _ ->
        // Bounded ±20% jitter prevents a fleet-wide broker restart becoming a synchronized retry storm.
        (base * (80 + java.util.concurrent.ThreadLocalRandom.current().nextInt(41)) / 100)
    })
    private val authScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mqtt-auth-recovery").apply { isDaemon = true }
    }

    private fun markOk(expectedGeneration: Long? = connectionGeneration.currentOrNull()) {
        val generation = expectedGeneration ?: return
        if (!connectionGeneration.isCurrent(generation)) return
        val progress = MqttBrokerProgress(SystemClock.elapsedRealtime(), generation)
        publishRecoveryBrokerProgress(progress)
    }

    /** Milliseconds since the last broker-ACKed activity, or 0 if never connected (so a not-yet-started
     *  bridge never looks "stale" to the watchdog — the state-based check covers startup). */
    fun msSinceLastOk(): Long = if (lastOkMs == 0L) 0L else SystemClock.elapsedRealtime() - lastOkMs

    /** Public-paste-safe MQTT status — state + broker-ACKed liveness age + address-family preference,
     *  deliberately WITHOUT the broker host (the host stays in the info page's "MQTT" row, which /diag
     *  omits). This is the row a /diag dump needs to answer "is this panel broker-connected?". */
    fun statusPublic(): String {
        val now = SystemClock.elapsedRealtime()
        val recovery = recoveryAuthority.snapshot()
        if (recovery.state == "disabled") return "disabled"
        if (recovery.state == "config-error") return "config-error · invalid or unsupported broker URL"
        val lastOkMs = recovery.brokerProgress.lastOkMs
        val age = if (lastOkMs == 0L) "never" else "${((now - lastOkMs).coerceAtLeast(0) / 1000)}s ago"
        val transport = mqttTransportLabel(tlsActive, recovery.addressFamily)
        val a = authRecovery.snapshot(now)
        val auth = if (a.consecutiveRejects == 0) "auth-ok" else
            "${a.state} · rejects ${a.consecutiveRejects} · attempt ${a.retryAttempt} · next ${a.nextRetryMs?.let { ((it - now).coerceAtLeast(0) / 1000).toString() + "s" } ?: "none"}"
        val lastAuth = a.lastSuccessMs?.let { "${((now - it).coerceAtLeast(0) / 1000)}s ago" } ?: "never"
        val policy = MqttAddressFamilyPolicy.fromConfig(runtimeMqttAddressFamily)
        val familyPolicy = if (policy == MqttAddressFamilyPolicy.AUTOMATIC) {
            "Automatic (next ${if (familyPreference.preferIpv4) "IPv4" else "IPv6"})"
        } else {
            policy.configValue
        }
        return "${recovery.state} · $transport · last-ok $age · last-auth $lastAuth · $auth · family $familyPolicy"
    }

    private val panel = runtimePanelId
    internal val panelId: String get() = panel
    private val availabilityTopic = "ha-paneld/$panel/availability"
    private val cmdScreen = "ha-paneld/$panel/screen/set"
    private val cmdLed = "ha-paneld/$panel/led/set"
    private val cmdNavigate = "ha-paneld/$panel/navigate/set"
    private val cmdVolume = "ha-paneld/$panel/volume/set"
    private val cmdReload = "ha-paneld/$panel/reload/set"
    private val cmdHomeDashboard = "ha-paneld/$panel/home_dashboard/set"
    private val stateHomeDashboard = "ha-paneld/$panel/home_dashboard/state"
    private val cmdReboot = "ha-paneld/$panel/reboot/set"
    private val cmdButtons = "ha-paneld/$panel/buttons/set"
    private val stateButtons = "ha-paneld/$panel/buttons/state"
    private val cmdNavbar = "ha-paneld/$panel/navbar/set"
    private val stateNavbar = "ha-paneld/$panel/navbar/state"
    private val cmdWakeOnWave = "ha-paneld/$panel/wake_on_wave/set"
    private val stateWakeOnWave = "ha-paneld/$panel/wake_on_wave/state"
    private val cmdAutoSleep = "ha-paneld/$panel/auto_sleep/set"
    private val stateAutoSleep = "ha-paneld/$panel/auto_sleep/state"
    private val autoSleepAvailabilityTopic = "ha-paneld/$panel/auto_sleep_activity/availability"
    private val cmdTouchSound = "ha-paneld/$panel/touch_sound/set"
    private val stateTouchSound = "ha-paneld/$panel/touch_sound/state"
    private val cmdWatchdog = "ha-paneld/$panel/watchdog/set"
    private val stateWatchdog = "ha-paneld/$panel/watchdog/state"
    private val cmdKiosk = "ha-paneld/$panel/kiosk_lock/set"
    private val stateKiosk = "ha-paneld/$panel/kiosk_lock/state"
    private val cmdUpdateCompanion = "ha-paneld/$panel/update_companion/set"
    private val cmdCompanionAuto = "ha-paneld/$panel/companion_auto_update/set"
    private val stateCompanionAuto = "ha-paneld/$panel/companion_auto_update/state"
    private val cmdUpdatePaneld = "ha-paneld/$panel/update_paneld/set"
    private val cmdCompanionChannel = "ha-paneld/$panel/companion_update_channel/set"
    private val stateCompanionChannel = "ha-paneld/$panel/companion_update_channel/state"
    private val cmdSelfUpdate = "ha-paneld/$panel/self_update/set"
    private val stateSelfUpdate = "ha-paneld/$panel/self_update/state"
    private val cmdWebViewAuto = "ha-paneld/$panel/webview_auto_update/set"
    private val stateWebViewAuto = "ha-paneld/$panel/webview_auto_update/state"
    private val cmdUpdateChannel = "ha-paneld/$panel/update_channel/set"
    private val stateUpdateChannel = "ha-paneld/$panel/update_channel/state"
    private val cmdSilenceBootChime = "ha-paneld/$panel/silence_boot_chime/set"
    private val stateSilenceBootChime = "ha-paneld/$panel/silence_boot_chime/state"
    private val cmdPreventIdleDim = "ha-paneld/$panel/prevent_idle_dim/set"
    private val statePreventIdleDim = "ha-paneld/$panel/prevent_idle_dim/state"
    private val cmdZigbee = "ha-paneld/$panel/zigbee_router/set"
    private val stateZigbee = "ha-paneld/$panel/zigbee_router/state"
    private val cmdCpuGov = "ha-paneld/$panel/cpu_governor/set"
    private val stateCpuGov = "ha-paneld/$panel/cpu_governor/state"
    private val cmdNetAdb = "ha-paneld/$panel/network_adb/set"
    private val stateNetAdb = "ha-paneld/$panel/network_adb/state"
    private val stateScreen = "ha-paneld/$panel/screen/state"
    // Last brightness reported to HA on stateScreen (-1 = screen reported OFF); heartbeat reconciles
    // the effective hardware backlight against this so firmware dims reach HA between commands.
    @Volatile private var lastScreenBrightness = -1
    // LED hardware has no readback on every backend. Publish desired state only after the current bridge
    // has a confirmed write; a failed command/effect frame remains Unknown instead of fabricating success.
    @Volatile private var ledActuationKnown = false
    // Effective (node-scale) level the last command settled at; -1 = capture on next heartbeat tick.
    @Volatile private var screenEffectiveBaseline = -1
    // Per-channel sync state: previous tick's read (settle detection) + last published volume.
    @Volatile private var prevTickBrightness = -1
    @Volatile private var prevTickVolume = -1
    @Volatile private var lastPublishedVolume = -1
    // Last CPU-tier reported to HA — baselined at announce / on command, so the sync channel only fires
    // when the live sysfs governor is changed by something else (a thermal daemon, another app).
    @Volatile private var lastPublishedGovTier: String? = null
    // Recent "changed outside MQTT" events, surfaced on the info page + /diag for debugging.
    private val syncLog = io.github.maxlyth.hapaneld.mqtt.SyncLog()
    private val stateLed = "ha-paneld/$panel/led/state"
    private val stateNavigate = "ha-paneld/$panel/navigate/state"
    private val stateVolume = "ha-paneld/$panel/volume/state"
    private val eventButton = "ha-paneld/$panel/button/event"
    private val stateIlluminance = "ha-paneld/$panel/illuminance/state"
    private val stateProximity = "ha-paneld/$panel/proximity/state"
    private val stateProximityLevel = "ha-paneld/$panel/proximity_level/state"
    private val proximityAvailabilityTopic = "ha-paneld/$panel/proximity/availability"
    private val stateTemperature = "ha-paneld/$panel/temperature/state"
    private val stateHumidity = "ha-paneld/$panel/humidity/state"
    private val stateZigbeeHealth = "ha-paneld/$panel/zigbee_gateway_health/state"
    private val attrZigbeeHealth = "ha-paneld/$panel/zigbee_gateway_health/attributes"
    private val stateStorageHealth = "ha-paneld/$panel/storage_health/state"
    private val attrStorageHealth = "ha-paneld/$panel/storage_health/attributes"
    private val attrWifiOutages = "ha-paneld/$panel/diag_wifi_outages_24h/attributes"
    private val cmdAutoBright = "ha-paneld/$panel/auto_brightness/set"
    private val stateAutoBright = "ha-paneld/$panel/auto_brightness/state"
    private val cmdVoiceEnabled = "ha-paneld/$panel/voice_enabled/set"
    private val stateVoiceEnabled = "ha-paneld/$panel/voice_enabled/state"
    private val stateVoiceState = "ha-paneld/$panel/voice_state/state"
    private val cmdCameraEnabled = "ha-paneld/$panel/camera_enabled/set"
    private val stateCameraEnabled = "ha-paneld/$panel/camera_enabled/state"
    // The snapshot image entity carries a URL rather than image bytes: the camera contract serves frames
    // only over the panel's own listeners to a client that dialled in, and a retained byte payload would
    // leave a frame sitting on the broker. HA stores this URL and fetches it when somebody actually looks
    // at the card, so the camera opens for a viewer and never for a publish.
    private val cameraSnapshotAvailability = cameraSnapshotAvailabilityTopic(panel)
    private val stateCommandTopics by lazy {
        setOf(
            cmdCpuGov, cmdNetAdb, cmdScreen, cmdLed, cmdNavigate, cmdVolume, cmdHomeDashboard,
            cmdButtons, cmdNavbar, cmdWakeOnWave, cmdAutoSleep, cmdTouchSound, cmdWatchdog, cmdKiosk,
            cmdCompanionAuto, cmdCompanionChannel, cmdSelfUpdate, cmdWebViewAuto, cmdUpdateChannel,
            cmdSilenceBootChime, cmdPreventIdleDim, cmdZigbee, cmdAutoBright, cmdVoiceEnabled,
            cmdCameraEnabled,
        )
    }
    private val actionCommandTopics by lazy {
        setOf(
            cmdReload, cmdReboot, cmdUpdateCompanion, cmdUpdatePaneld,
        )
    }
    @Volatile private var lastIlluminance: Int? = null
    private val proximityPublication = ProximityPublicationState()
    @Volatile private var lastTemperature: Float? = null
    @Volatile private var lastHumidity: Float? = null

    private data class SoftwareUpdateDiscoveryContext(val availability: String, val device: String)
    // Captured by each discovery pass so a later shape change can republish with the same identity.
    @Volatile private var softwareUpdateDiscovery: SoftwareUpdateDiscoveryContext? = null
    private val softwareUpdateLock = Any()
    // What this bridge generation last told Home Assistant, seeded from the persisted shape. Guarded
    // by softwareUpdateLock.
    private val softwareShapes = HashMap<SoftwareComponent, SoftwareDiscoveryShape?>()

    // Eager ownership removes the extra lazy/not-yet-closed state: every external producer sees the
    // same converger, and retirement can close it before any late callback tries to publish.
    private val announcementReadiness = MqttAnnouncementReadiness()
    // The MQTT edge: each channel's topic and retain policy, recorded before the channel is registered so
    // no reconcile can reach the sink for a channel without its route. Declared ahead of the converger,
    // whose construction registers channels.
    private val mqttStateRoutes = java.util.concurrent.ConcurrentHashMap<String, MqttStateRoute>()
    @Volatile private var nativeStateSink: io.github.maxlyth.hapaneld.mqtt.StateSink? = null
    private val stateConverger = createStateConverger()
    private val zigbeeActuation = MqttZigbeeActuationCoordinators.forController(zigbee)
    private val zigbeeLease = zigbeeActuation.activate()
    private val zigbeeWorker = LatestDispatcher.singleSlot<Boolean>("zigbee-state", ::reconcileZigbeeDesired)
    // HiveMQ invokes both lifecycle callbacks on its own event loop. That thread may never acquire the
    // bridge gate: it only hands the latest event to this fixed-cardinality owner.
    private val connectionEventDispatcher = MqttConnectionEventDispatcher(::performConnectionEvent)
    // Discovery can exhaust outbound flow demand while submitting its large finite announcement. Keep
    // it separate from connection transitions so even a permanently wedged library call remains visible
    // to the watchdog and cannot suppress a later disconnect/reconnect event.
    private val connectAnnouncementDispatcher =
        MqttConnectAnnouncementDispatcher(::performConnectAnnouncement)
    // Synchronous announcement publication inherits the exact transport lease without changing every
    // discovery helper signature. The value exists only on the announcement dispatcher's one thread.
    private val announcementConnection = ThreadLocal<MqttConnectionLease?>()
    private val reannounceDispatcher: MqttReannounceDispatcher = MqttReannounceDispatcher(
        debounceMs = REANNOUNCE_DEBOUNCE_MS,
        perform = ::performReAnnounce,
    )

    /** The process-wide convergence pump is only a scheduler; this bridge's lifecycle gate remains the
     * authority for whether a queued observation may touch this concrete generation. */
    private fun dispatchStateWork(action: () -> Unit) {
        io.github.maxlyth.hapaneld.mqtt.StateConverger.dispatch {
            lifecycle.runIfOpen(Unit, action)
        }
    }

    private fun createStateConverger(): io.github.maxlyth.hapaneld.mqtt.StateConverger {
        val known = { payload: String -> io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation.Known(payload) }
        val unknown = io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation.Unknown
        val c = io.github.maxlyth.hapaneld.mqtt.StateConverger(
            sender = ::publishStateObservation,
            schedule = ::dispatchStateWork,
            onObservation = { channel, observation -> nativeStateSink?.invoke(channel, observation) {} },
        )
        fun channel(
            key: String,
            topic: String,
            retain: Boolean = true,
            equivalent: (String, String) -> Boolean = String::equals,
            observe: () -> io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation,
        ) = registerStateChannel(c, mqttStateChannel(key, topic, retain, equivalent, observe))

        channel("storage_health", stateStorageHealth) {
            known(storageHealth().severity.name.lowercase(Locale.ROOT))
        }
        // Retained update-entity state. A withheld entity (ha-paneld's under a Panel Assistant lease, or
        // the Companion's while no Companion is installed) clears its retained payload rather than
        // leaving a stale version behind the discovery tombstone.
        if (softwareUpdateSources != null) for (component in SoftwareComponent.entries) {
            channel(
                SoftwareUpdateEntities.stateChannelKey(component),
                SoftwareUpdateEntities.stateTopic(panel, component),
            ) {
                softwareUpdateInputs(component)?.let { inputs ->
                    if (SoftwareUpdateEntities.withheld(inputs)) io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation.Unavailable
                    else known(SoftwareUpdateEntities.stateJson(inputs))
                } ?: unknown
            }
        }
        channel("storage_health_attributes", attrStorageHealth) {
            known(storageHealthMqttAttributes(storageHealth()))
        }
        channel("screen", stateScreen) {
            if (!config.haExposed("screen", true)) return@channel unknown
            when (screen.observedDark()) {
                true -> {
                    lastScreenBrightness = -1
                    screenEffectiveBaseline = -1
                    known("""{"state":"OFF"}""")
                }
                false -> {
                    val level = lastScreenBrightness.takeIf { it >= 0 } ?: brightness.getCommanded().coerceAtLeast(1)
                    // Baseline the first authoritative observation too. Otherwise the first heartbeat
                    // misclassifies an already-lit startup as a local wake and emits a duplicate state.
                    lastScreenBrightness = level
                    known("""{"state":"ON","brightness":$level}""")
                }
                null -> unknown
            }
        }
        channel("led", stateLed) {
            LedCommandPolicy.statePayload(
                LedCommandPolicy.stored(config.lastLed, config.lastLedEffect),
                ledActuationKnown,
                ledEffect.status(),
            )?.let(known) ?: unknown
        }
        channel("navigate", stateNavigate) { known(config.lastNavigate.ifEmpty { "/" }) }
        channel("home_dashboard", stateHomeDashboard) { known(config.homeDashboard) }
        channel("volume", stateVolume) {
            if (config.haExposed("volume", true)) known(volume.getPercent().toString()) else unknown
        }
        if (hasButtonBacklight) channel("buttons", stateButtons) {
            config.lastButtonBacklight.takeIf { it >= 0 }?.let {
                known(if (it == 0) """{"state":"OFF"}""" else """{"state":"ON","brightness":$it}""")
            } ?: unknown
        }
        channel("wake_on_wave", stateWakeOnWave) {
            if (hasProximity) known(if (config.wakeOnWave) "ON" else "OFF") else unknown
        }
        channel("auto_sleep", stateAutoSleep) { known(if (config.autoSleep) "ON" else "OFF") }
        // Persisted intent, not the platform flag: the flag has other writers, and publishing an
        // observation of it would let firmware drift flip the Home Assistant switch on its own.
        channel("touch_sound", stateTouchSound) { known(if (config.touchSound) "ON" else "OFF") }
        channel("watchdog", stateWatchdog) { known(if (config.watchdogEnabled) "ON" else "OFF") }
        channel("kiosk_lock", stateKiosk) { known(if (config.kioskLock) "ON" else "OFF") }
        channel("companion_auto_update", stateCompanionAuto) { known(if (config.companionAutoUpdate) "ON" else "OFF") }
        channel("companion_update_channel", stateCompanionChannel) { known(companionChannelLabel()) }
        channel("self_update", stateSelfUpdate) { known(if (config.selfUpdate) "ON" else "OFF") }
        channel("webview_auto_update", stateWebViewAuto) { known(if (config.webViewAutoUpdate) "ON" else "OFF") }
        channel("update_channel", stateUpdateChannel) { known(updateChannelLabel()) }
        channel("silence_boot_chime", stateSilenceBootChime) { known(if (bootChime.isEnabled()) "ON" else "OFF") }
        channel("prevent_idle_dim", statePreventIdleDim) { known(if (config.preventIdleDim) "ON" else "OFF") }
        channel("auto_brightness", stateAutoBright) { known(if (config.autoBrightness) "ON" else "OFF") }
        channel("camera_enabled", stateCameraEnabled) { known(if (config.cameraEnabled) "ON" else "OFF") }
        channel("navbar", stateNavbar) { known(config.navbarMode) }
        // Mirrors wake_on_wave's hasProximity gate: without the capability, report OFF rather than
        // echoing a persisted value the panel can no longer act on — never Unknown/skip, so a stale
        // retained ON (e.g. from before a profile switch removed the capability) is overwritten rather
        // than left in place.
        channel("voice_enabled", stateVoiceEnabled) { known(if (hasMicrophone && config.voiceEnabled) "ON" else "OFF") }
        // Hidden until exposed, exactly like the diag_* sensors: a hidden entity must not keep a
        // retained voice-state payload alive on the broker for something nobody opted into. Also reports
        // "off" — not the coordinator's authority value — once the capability is gone, so a phase like
        // "listening" can never outlive the microphone it describes.
        channel("voice_state", stateVoiceState) {
            if (!hasMicrophone) known(io.github.maxlyth.hapaneld.assist.VoiceState.OFF.wireValue)
            else diagnosticObservation("voice_state", config.haExposed("voice_state", false), voiceState())
        }

        channel("illuminance", stateIlluminance, retain = false) {
            if (config.haExposed("illuminance", true)) lastIlluminance?.let { known(it.toString()) } ?: unknown
            else unknown
        }
        channel("proximity", stateProximity) {
            if (learnedProximityEligible() && config.haExposed("proximity", true)) {
                proximityPublication.near?.let { known(if (it) "ON" else "OFF") } ?: unknown
            } else unknown
        }
        channel(
            "proximity_level",
            stateProximityLevel,
            equivalent = io.github.maxlyth.hapaneld.mqtt.StateConverger.numericDeadband(4.0),
        ) {
            if (learnedProximityEligible() && config.haExposed("proximity_level", true)) {
                proximityPublication.level?.let { known(it.toString()) } ?: unknown
            } else unknown
        }
        channel("temperature", stateTemperature, retain = false,
            equivalent = io.github.maxlyth.hapaneld.mqtt.StateConverger.numericDeadband(0.1)) {
            if (config.haExposed("temperature", true)) {
                lastTemperature?.let { known(String.format(java.util.Locale.US, "%.1f", it)) } ?: unknown
            } else unknown
        }
        channel("humidity", stateHumidity, retain = false,
            equivalent = io.github.maxlyth.hapaneld.mqtt.StateConverger.numericDeadband(1.0)) {
            if (config.haExposed("humidity", true)) lastHumidity?.let { known(Math.round(it).toString()) } ?: unknown
            else unknown
        }

        // The integer alone cannot say "this is a floor", so the saturation flag rides with it as an
        // attribute; without it Home Assistant records a capped 200 as an exact measurement.
        channel("diag_wifi_outages_attributes", attrWifiOutages) {
            // The attributes ride with the count and share its opt-in: a hidden sensor must not keep
            // a retained attribute payload alive in the broker for an entity nobody exposed.
            val exposed = config.haExposed(
                "diag_wifi_outages_24h",
                requireNotNull(SettingsRegistry.spec("diag_wifi_outages_24h")).haExposedByDefault,
            )
            wifiOutageAttributeObservation(exposed, wifiOutages())
        }

        val diagDeadband = mapOf(
            "diag_cpu" to 5.0, "diag_memory" to 3.0, "diag_soc_temp" to 0.5,
            "diag_wifi_rssi" to 3.0,
            "room_temp" to 0.2, "room_humidity" to 1.0,
        )
        val diagKeys = if (hasCht8305) DIAG_KEYS + ROOM_KEYS else DIAG_KEYS
        for (key in diagKeys) channel(
            key,
            SettingsRegistry.spec(key)!!.ha!!.stateTopic(panel),
            equivalent = diagDeadband[key]?.let(io.github.maxlyth.hapaneld.mqtt.StateConverger::numericDeadband)
                ?: String::equals,
        ) {
            // Transport-dependent Wi-Fi discovery clears its retained state when unavailable.
            // Do not immediately recreate a retained literal "unknown" behind the tombstone.
            val exposedByDefault = requireNotNull(SettingsRegistry.spec(key)).haExposedByDefault
            diagnosticObservation(key, config.haExposed(key, exposedByDefault), diagValue(key))
        }
        return c
    }

    private fun registerStateChannel(
        converger: io.github.maxlyth.hapaneld.mqtt.StateConverger,
        mqtt: MqttStateChannel,
    ) {
        // A duplicate keeps the first route, and the converger then refuses the duplicate channel.
        mqttStateRoutes.putIfAbsent(mqtt.channel.key, mqtt.route)
        converger.register(mqtt.channel)
    }

    /** Bind the native reporter directly to observations, independently of MQTT delivery capacity. */
    internal fun addStateSink(sink: io.github.maxlyth.hapaneld.mqtt.StateSink) { nativeStateSink = sink }

    /** The converger's registered channels, which grow as hardware capabilities are confirmed. */
    internal fun stateChannelKeys(): Set<String> = stateConverger.keys()

    /**
     * The native describe's view of [stateChannelKeys]: a channel whose [hardwareAvailability] is settled
     * false is stated unsupported instead of described. An unsettled channel stays described, as before.
     */
    internal fun nativeChannelShape(): io.github.maxlyth.hapaneld.panelassistant.PanelAssistantChannelShape {
        val learned = runCatching(learnedProximityState).getOrNull()
        val (unsupported, served) = stateConverger.keys().partition { hardwareAvailability(it, learned) == false }
        return io.github.maxlyth.hapaneld.panelassistant.PanelAssistantChannelShape(served, unsupported)
    }

    /**
     * Whether this panel can fill a hardware-gated entity: the one definition MQTT discovery (its
     * availability override, which publishes an empty config when false) and the native describe (which
     * states the channel unsupported when false) share. Null when [key] is not hardware-gated here, or when
     * its answer is not settled; MQTT passes learned proximity from its announcement snapshot, the native
     * describe from the live, settled source.
     */
    private fun hardwareAvailability(key: String, learnedProximity: Boolean?): Boolean? = when (key) {
        "temperature" -> hasTemperature
        "humidity" -> hasHumidity
        "proximity", "proximity_level" -> learnedProximity
        SoftwareUpdateEntities.stateChannelKey(SoftwareComponent.COMPANION) ->
            softwareUpdateInputs(SoftwareComponent.COMPANION)
                ?.takeUnless { it.presenceUnknown }
                ?.let { !SoftwareUpdateEntities.companionAbsent(it) }
        else -> null
    }

    /** The primary state sink: derives the topic, retain flag and payload bytes from the channel. */
    private fun publishStateObservation(
        channel: String,
        observation: io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation.Reportable,
        done: (Boolean) -> Unit,
    ) {
        val route = checkNotNull(mqttStateRoutes[channel]) { "no MQTT route for state channel $channel" }
        val generation = connectionGeneration.currentOrNull()
        publish(route.topic, mqttStatePayload(observation), route.retain) { acknowledged ->
            if (acknowledged && generation != null && connectionGeneration.isCurrent(generation)) {
                completeAnnouncementIfReady(announcementReadiness.acknowledgeState(generation))
            }
            done(acknowledged)
        }
    }

    /**
     * Add newly confirmed hardware channels without replacing the bridge. The possible shape never
     * shrinks, while each observer checks the latest live snapshot and reports unavailable rather than
     * presenting a transiently unreachable controller as healthy.
     */
    private fun ensureCapabilityChannels(snapshot: Capabilities?): MqttCapabilityObservation =
        synchronized(capabilityChannelLock) {
            val live = snapshot ?: Capabilities(
                zigbeePresent = zigbee.present(),
                cpuGovernors = cpu.available(),
                networkAdb = adb.available(),
                relays = relay.count(),
                buttonLeds = relay.ledCount(),
            )
            val observation = capabilityShape.observe(live)
            val converger = stateConverger
            // capabilityChannelLock serializes this compound keys/register operation; StateConverger
            // independently synchronizes each registry mutation and observation snapshot.
            val registered = converger.keys()
            val unknown = io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation.Unknown
            fun known(payload: String) = io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation.Known(payload)
            fun register(
                key: String,
                topic: String,
                observe: () -> io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation,
            ) {
                if (key !in registered) {
                    registerStateChannel(
                        converger,
                        MqttStateChannel(
                            io.github.maxlyth.hapaneld.mqtt.StateConverger.Channel(key, observe = observe),
                            MqttStateRoute(topic, retain = true),
                        ),
                    )
                }
            }

            if (observation.possible.networkAdb) register("network_adb", stateNetAdb) {
                if (!capabilityShape.current().live.networkAdb) known("unknown")
                else known(if (adb.isPersisted()) "ON" else "OFF")
            }
            if (observation.possible.zigbee) register("zigbee_router", stateZigbee) {
                if (!capabilityShape.current().live.zigbee) known("unknown")
                else if (config.zigbeeRouterConfigured && !config.zigbeeRouterEnabled) known("OFF")
                else known(if (zigbee.running()) "ON" else "OFF")
            }
            if (observation.possible.cpu) register("cpu_governor", stateCpuGov) {
                if (!capabilityShape.current().live.cpu) known("unknown")
                else cpu.currentTier()?.let(::known) ?: known("unknown")
            }
            for (n in 1..observation.possible.relays) {
                register("relay$n", "ha-paneld/$panel/relay$n/state") {
                    if (capabilityShape.current().live.relays < n) known("unknown")
                    else relay.read(n)?.let { known(if (it) "ON" else "OFF") } ?: known("unknown")
                }
            }
            for (n in 1..observation.possible.buttonLeds) {
                register("button_led$n", "ha-paneld/$panel/button_led$n/state") {
                    if (capabilityShape.current().live.buttonLeds < n) known("unknown")
                    else relay.ledRead(n - 1)?.let { known(if (it) "ON" else "OFF") } ?: known("unknown")
                }
            }
            observation
        }

    fun start() {
        // A successor that has not restored the panel's identity yet must not connect under the one it
        // generated for itself; the process restarts from the restored configuration and starts then.
        if (io.github.maxlyth.hapaneld.migration.IdentityMigrationGate.holdsNetworkIdentity()) return
        lifecycle.runIfOpen(Unit, ::startOpen)
    }

    private fun startOpen() {
        // Linearize the explicit fresh-client boundary before clearing bridge state. A delayed event from
        // the prior client must not overwrite this attempt while it is waiting for its first CONNACK.
        connectionEventDispatcher.supersede()
        val credentials = MqttCredentialsSnapshot(
            runtimeBroker,
            runtimeMqttUser,
            runtimeMqttPassword,
            runtimeMqttAddressFamily,
        )
        authRecovery.configure(
            "${credentials.broker}\u0000${credentials.user}\u0000${credentials.password}\u0000${credentials.addressFamily}",
        )
        activeConnection = null
        connectionGeneration.clear()
        announcementReadiness.clear()
        publishRecoveryLifecycleStateWithAddressFamily("connecting", null)
        var broker = credentials.broker.trim()
        if (broker.isEmpty() && GuidedSetupPresence.activelyWalked(SystemClock.elapsedRealtime())) {
            // A person is mid-wizard on an unconfigured panel: do not probe at all. The probe's anonymous
            // failures accumulate against the broker for the whole journey and were the leading suspect
            // for the first credentialed connect being throttled; the wizard's own broker step is minutes
            // away from providing the real configuration.
            Log.i(TAG, "guided setup in progress — deferring broker discovery probe")
            activeBroker = ""
            publishRecoveryLifecycleState("discovering")
            return
        }
        if (broker.isEmpty()) {
            // No explicit broker — try to find HA on the LAN (mDNS) and default to its :1883.
            discoverHaIp()?.let {
                broker = "tcp://$it:1883"
                Log.i(TAG, "MQTT broker auto-discovered via mDNS (HA at $it): $broker")
            }
        }
        if (broker.isEmpty()) {
            Log.i(TAG, "no broker configured and none discovered — waiting for network discovery")
            activeBroker = ""
            publishRecoveryLifecycleState("discovering")
            return
        }
        activeBroker = broker
        try {
            val ep = BrokerEndpoint.endpoint(broker)
            if (ep == null) {
                connectionGeneration.clear()
                publishRecoveryLifecycleState("config-error")
                Log.e(TAG, "invalid or unsupported MQTT broker URL")
                return
            }
            val (host, port) = ep.host to ep.port
            tlsActive = ep.tls
            val familyIdentity = checkNotNull(mqttFamilyBrokerIdentity(broker))
            val familyPolicy = MqttAddressFamilyPolicy.fromConfig(credentials.addressFamily)
            val announcementIdentity = mqttAnnouncementRecoveryIdentity(
                familyIdentity,
                panel,
                profileIdentity,
                credentials.user,
                credentials.password,
                familyPolicy.configValue,
            )
            synchronized(announcementBudgetLock) {
                // This latch is process-wide rather than bridge-wide: a runtime replacement can complete
                // during the restart grace, but it must not rearm the already-scheduled boundary.
                ANNOUNCEMENT_BOUNDARY_CONSUMED_HERE.getAndUpdate { consumed ->
                    consumed?.takeIf { it == announcementIdentity }
                }
                announcementRecoveryIdentity = announcementIdentity
                announcementProcessRecoveryAvailable =
                    config.mqttAnnouncementBoundaryAvailable(announcementIdentity)
            }
            val selectedRoute = synchronized(familyRecoveryLock) {
                // Only building a fresh client consumes a staged route. Hive automatic reconnect may
                // create many sessions on that client, but cannot make owner retry flip back.
                val attempt = recoveryAuthority.beginConnectAttempt(
                    state = "connecting",
                    connectionGeneration = connectionGeneration.currentOrNull(),
                )
                val learned = familyPreference.selectForConnect(familyIdentity, attempt)
                val selected = if (familyPolicy == MqttAddressFamilyPolicy.AUTOMATIC) {
                    learned
                } else {
                    familyPolicy.initialPreferIpv4
                }
                MqttSelectedFamilyRoute(familyIdentity, attempt, selected).also {
                    selectedFamilyRoute = it
                }
            }
            val routePlanner = MqttRoutePlanner(
                logicalHost = host,
                port = port,
                policy = familyPolicy,
                initialPreferIpv4 = selectedRoute.preferIpv4,
                // A retained Automatic route gets the established watchdog grace. All other first
                // attempts may suppress one failed family immediately, without changing reconnect backoff.
                rapidInitialFallbackAllowed = familyPolicy != MqttAddressFamilyPolicy.AUTOMATIC ||
                    !familyPreference.awaitingProgress,
            )
            val initialRoute = routePlanner.resolveInitial()
            if (initialRoute == null) {
                connectionGeneration.clear()
                publishRecoveryLifecycleState("unreachable")
                Log.w(TAG, "MQTT Force IPv4 found no IPv4 address for the configured broker")
                return
            }
            val connectAddressFamily = initialRoute.family
            if (!recoveryAuthority.updateAddressFamily(selectedRoute.connectAttempt, connectAddressFamily)) return

            // The client lifecycle — build, connect, the connected/disconnected listeners, and the
            // superseded-client generation guard — lives in the transport (see HiveMqTransport). This
            // bridge only supplies the connection config + callbacks and keeps the HA semantics.
            if (buttonSubscription == null) {
                buttonSubscription = ButtonBus.subscribe { event -> publishButton(event) }
            }
            // There is no heartbeat authority until this attempt reaches onConnected. HiveMQ can also
            // reconnect this client internally, so successful connection callbacks own generation changes.
            transport.connect(
                MqttConnectConfig(
                    host = host,
                    port = port,
                    tls = ep.tls,
                    clientId = "ha-paneld-$panel",
                    user = credentials.user.ifEmpty { null },
                    password = credentials.password,
                    keepAliveSeconds = KEEPALIVE_SEC,
                    willTopic = availabilityTopic,
                    willPayload = "offline",
                    // The credential-less discovery probe must not own a transport-level retry loop: its
                    // repeated anonymous failures poison the broker's throttle before the user ever types
                    // credentials, and a leaked probe client used to reconnect forever (broker-log
                    // evidence). Configured, credentialed connections keep auto-reconnect.
                    automaticReconnect = credentials.user.isNotEmpty() || configuredBroker.isNotEmpty(),
                    routePlanner = routePlanner,
                ),
                object : MqttCallbacks {
                    override fun onConnected(
                        connection: MqttConnectionLease,
                        addressFamily: MqttAddressFamily?,
                    ) = this@MqttBridge.onConnected(connection, addressFamily)
                    override fun onDisconnected(
                        connection: MqttConnectionLease?,
                        causeMessage: String?,
                    ) = this@MqttBridge.onDisconnected(connection, causeMessage)
                },
            )
            Log.i(
                TAG,
                "MQTT connecting to $host:$port " +
                    "(policy=${familyPolicy.configValue}, route=${connectAddressFamily?.label ?: "DNS"}) for $panel",
            )
        } catch (e: Exception) {
            Log.w(TAG, "MQTT connect failed", e)
        }
    }

    private fun scheduleAuthRetry(atMs: Long) {
        if (!lifecycle.isOpen()) return
        val generation = authRecovery.beginRetry()
        val delay = (atMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        runCatching {
            authScheduler.schedule({
                if (authRecovery.isCurrentRetry(generation) && isAuthRecoveryState(state)) {
                    Log.i(TAG, "MQTT auth retry — building fresh client with unchanged address family")
                    reconnect()
                }
            }, delay, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.onFailure { if (lifecycle.isOpen()) Log.w(TAG, "failed to schedule MQTT auth retry", it) }
    }

    /**
     * Select and persist the alternate route before recovery is handed to [ServiceRuntimeOwner]. The
     * owner's bounded recovery worker is intentionally allowed to wedge without entering its callback;
     * staging here means the controlled process boundary still restores the route we meant to try.
     *
     * This method never takes the bridge mutation gate: the watchdog must not wait behind the MQTT call
     * it is trying to recover. The tuple is broker-scoped, so a concurrent runtime replacement for a
     * different broker cannot consume it.
     */
    internal fun recoveryTicketForReconnect(expected: MqttRecoveryTicket): MqttRecoveryTicket? =
        expected.takeIf {
            lifecycle.isOpen() && recoveryAuthority.isCurrent(it) &&
                state != "disabled" && state != "config-error"
        }

    internal fun stageAlternateFamilyForReconnect(baseline: MqttRecoveryTicket): MqttRecoveryTicket? {
        if (MqttAddressFamilyPolicy.fromConfig(runtimeMqttAddressFamily) !=
            MqttAddressFamilyPolicy.AUTOMATIC
        ) {
            // Explicit policy is the durable bound. A steady-state watchdog rebuild may retry it, but
            // must not persist an opposing learned preference behind the user's back.
            return recoveryTicketForReconnect(baseline)
        }
        var identity: String? = null
        val selected = synchronized(familyRecoveryLock) {
            if (recoveryTicketForReconnect(baseline) == null) return null
            val brokerIdentity = mqttFamilyBrokerIdentity(activeBroker) ?: return null
            identity = brokerIdentity
            familyPreference.stageAlternate(
                brokerIdentity,
                baselineConnectAttempt = baseline.familyConnectAttempt,
            )
        }
        if (!selected.changed && selected.durable) {
            Log.i(
                TAG,
                "MQTT recovery retained the already-staged ${if (selected.preferIpv4) "IPv4" else "IPv6"} route after owner rejection",
            )
        } else if (selected.durable) {
            Log.i(
                TAG,
                "MQTT recovery staged and retained ${if (selected.preferIpv4) "IPv4" else "IPv6"} for the active broker",
            )
        } else {
            Log.w(
                TAG,
                "MQTT recovery cannot proceed: ${if (selected.preferIpv4) "IPv4" else "IPv6"} was selected in memory but is not durable",
            )
        }
        // Fail closed: owner work that may never enter is admitted only after the alternate route is
        // known to survive the process boundary. The same choice is retried next tick without flipping.
        return baseline.copy(stagedBrokerIdentity = identity).takeIf { selected.durable }
    }

    /** Owner rejected before entering. No fresh client consumed this stage, so cancel it unless a newer
     * attempt now owns the route; a still-stale connection can stage the same alternate again next tick. */
    internal fun reconcileRejectedRecovery(ticket: MqttRecoveryTicket): Boolean =
        synchronized(familyRecoveryLock) {
            reconcileRejectedMqttRecovery(recoveryAuthority, ticket) {
                ticket.stagedBrokerIdentity?.let { identity ->
                    familyPreference.cancelStaged(identity, ticket.familyConnectAttempt)
                } ?: true
            }
        }

    /** Durably consume this configuration's sole pre-readiness process boundary. */
    internal fun consumeAnnouncementProcessRecovery(ticket: MqttRecoveryTicket): Boolean =
        synchronized(announcementBudgetLock) {
            val identity = announcementRecoveryIdentity ?: return@synchronized false
            val before = recoveryAuthority.snapshot()
            if (!recoveryAuthority.isCurrent(ticket) || before.state != "announcing" ||
                !announcementProcessRecoveryAvailable
            ) return@synchronized false
            if (!config.consumeMqttAnnouncementBoundary(identity)) return@synchronized false
            ANNOUNCEMENT_BOUNDARY_CONSUMED_HERE.set(identity)
            announcementProcessRecoveryAvailable = false
            val after = recoveryAuthority.snapshot()
            if (after.state != "announcing" ||
                recoveryAuthority.claim(ticket) != MqttRecoveryAuthority.Claim.CLAIMED
            ) {
                // Progress won during the durable write. Give the still-unspent boundary back.
                if (config.clearMqttAnnouncementBoundary(identity)) {
                    ANNOUNCEMENT_BOUNDARY_CONSUMED_HERE.compareAndSet(identity, null)
                    announcementProcessRecoveryAvailable = true
                }
                return@synchronized false
            }
            true
        }

    /**
     * Force a fresh connection attempt, disposing any existing client first. Called by the service-level
     * reconnect watchdog and the connectivity-regained callback when HiveMQ's built-in auto-reconnect has
     * stalled — e.g. after a transient `NOT_AUTHORIZED` during an HA/broker restart (broker back up before
     * its auth backend is ready), or when the reconnect thread is deferred by Android power management.
     * Unlike [stop] it does NOT publish a retained "offline" — the availability LWT already covered the
     * drop and we're trying to come back, so we must not flap HA to offline on every retry.
     */
    fun reconnect() {
        lifecycle.runIfOpen(Unit) reconnect@{
            if (state == "disabled" || state == "config-error") return@reconnect
            // Detach the old client FIRST and tear it down on a throwaway daemon thread: disconnect() on a
            // WEDGED client (half-open socket, frozen reactor — the very case that triggers a liveness
            // rebuild) can block on an internal client monitor, and that must never delay the replacement
            // connection. In the wedged case the old reactor is frozen anyway, so it won't fight the new
            // client's session; in the healthy case the background disconnect completes normally.
            transport.disconnectDetached()
            startOpen()
        }
    }

    /** Execute only while the exact stale epoch that requested recovery still owns this bridge. */
    internal fun reconnect(ticket: MqttRecoveryTicket): MqttRecoveryOutcome {
        return lifecycle.runIfOpen(MqttRecoveryOutcome.NOT_ADMITTED) reconnect@{
            if (state == "disabled" || state == "config-error") {
                return@reconnect MqttRecoveryOutcome.NOT_ADMITTED
            }
            when (recoveryAuthority.claim(ticket)) {
                MqttRecoveryAuthority.Claim.CONSUMED_BY_NEW_ATTEMPT -> {
                    // Another fresh-client start consumed the selection. Never roll it back.
                    return@reconnect MqttRecoveryOutcome.NO_LONGER_NEEDED
                }
                MqttRecoveryAuthority.Claim.STALE_SAME_ATTEMPT -> {
                // The original attempt made real broker progress while owner work waited. Ordinary
                // reconnect churn on a dead address family is NOT progress and must not reach here:
                // rolling the staged alternate back on every disconnect is what pinned a panel to a
                // black-holed family. Restore the actual route while the lifecycle gate excludes a
                // newer startOpen from consuming the staged alternate.
                    ticket.stagedBrokerIdentity?.let { identity ->
                        val rolledBack = synchronized(familyRecoveryLock) {
                            familyPreference.cancelStaged(identity, ticket.familyConnectAttempt)
                        }
                        if (!rolledBack) {
                            Log.w(TAG, "MQTT recovered before fallback entry, but the staged family rollback was not durable")
                        }
                    }
                    return@reconnect MqttRecoveryOutcome.NO_LONGER_NEEDED
                }
                MqttRecoveryAuthority.Claim.CLAIMED -> Unit
            }
            transport.disconnectDetached()
            startOpen()
            MqttRecoveryOutcome.REBUILT
        }
    }

    /**
     * HiveMQ connected-listener boundary. It runs on the event loop that must process PUBACKs, so this
     * method performs one nonblocking fixed-cardinality enqueue and nothing else.
     */
    private fun onConnected(connection: MqttConnectionLease, addressFamily: MqttAddressFamily?) {
        connectionEventDispatcher.submit(MqttConnectionEvent.Connected(connection, addressFamily))
    }

    /** Runs on the connection-event owner, never HiveMQ's event loop. */
    private fun performConnectionEvent(sequenced: SequencedMqttConnectionEvent) {
        if (!connectionEventDispatcher.isCurrent(sequenced)) return
        when (val event = sequenced.event) {
            is MqttConnectionEvent.Connected -> {
                val announcement = lifecycle.runIfOpen<MqttConnectAnnouncement?>(null) {
                    if (!connectionEventDispatcher.isCurrent(sequenced) ||
                        !transport.isCurrent(event.connection)
                    ) return@runIfOpen null
                    val generation = connectionGeneration.advance()
                    event.addressFamily?.let { connectedFamily ->
                        selectedFamilyRoute = selectedFamilyRoute?.copy(
                            preferIpv4 = connectedFamily == MqttAddressFamily.IPV4,
                        )
                    }
                    MqttConnectAnnouncement(generation, event.connection).also {
                        activeConnection = event.connection
                        authRecovery.authenticated(SystemClock.elapsedRealtime())
                        // CONNACK is transport liveness, not application readiness.
                        announcementReadiness.begin(it)
                        recordMqttConnack(
                            authority = recoveryAuthority,
                            connectionGeneration = generation,
                            applicationReadyEver = recoveryAuthority.snapshot().applicationReadyEver,
                            addressFamily = event.addressFamily,
                            nowMs = SystemClock.elapsedRealtime(),
                        )
                        stateConverger.markAllDirty()
                    }
                } ?: return
                when (connectAnnouncementDispatcher.submit(announcement)) {
                    LatestDispatcher.Admission.COALESCED ->
                        Log.i(TAG, "MQTT connect announcement advanced to generation ${announcement.generation}")
                    LatestDispatcher.Admission.CLOSED ->
                        Log.w(TAG, "MQTT connect announcement rejected during retirement")
                    LatestDispatcher.Admission.ACCEPTED,
                    LatestDispatcher.Admission.REJECTED -> Unit // single slot never rejects
                }
            }
            is MqttConnectionEvent.Disconnected -> lifecycle.runIfOpen(Unit) {
                if (!connectionEventDispatcher.isCurrent(sequenced)) return@runIfOpen
                // A queued disconnect from a superseded automatic-reconnect session owns no state in
                // the replacement generation. A pre-CONNACK failure owns state only before any session.
                if (!mqttDisconnectOwnsBridgeState(activeConnection, event.connection)) return@runIfOpen
                activeConnection = null
                connectionGeneration.clear()
                announcementReadiness.clear()
                // This point is reached only for a disconnect that OWNS bridge state — sequence-current
                // and not a superseded session — so a stale callback cannot retire a live claim. An
                // MQTT-sourced outage must not outlive the channel it was heard on: the birth that
                // retracts it is not retained by default, so one missed during the gap is missed forever.
                haLifecycleLease?.let(HaLifecycleRuntime::observeMqttChannelLost)
                if (event.state == "auth-failed" && runtimeMqttUser.isEmpty() && configuredBroker.isEmpty()) {
                    // A credential-LESS discovery probe (fresh panel, broker unconfigured, anonymous
                    // connect to the mDNS-found broker) getting NOT_AUTHORIZED is the broker requiring
                    // credentials, not credentials being wrong. Publishing "auth-failed" here pre-armed
                    // the exact state the setup wizard's credential verdict keys on. No retry either —
                    // the earlier cadence hammered the broker with anonymous failures for the whole
                    // first-run journey (broker-log evidence), and the auth-retry guard would drop a
                    // "discovering" retry anyway. This probe answered its question: a broker exists and
                    // wants credentials. The real connection arrives with the user's configuration.
                    publishRecoveryLifecycleState("discovering")
                } else if (event.state == "auth-failed") {
                    val now = SystemClock.elapsedRealtime()
                    val retryAt = authRecovery.rejected(now)
                    publishRecoveryLifecycleState(authRecovery.snapshot(now).state)
                    scheduleAuthRetry(retryAt)
                } else publishRecoveryLifecycleState(event.state)
                Log.w(TAG, "MQTT disconnected ($state): ${event.causeMessage}")
                stateConverger.markAllDirty()
            }
        }
    }

    /** Runs off HiveMQ's event loop on every current (re)connect. */
    private fun performConnectAnnouncement(announcement: MqttConnectAnnouncement) {
        if (!connectionAnnouncementIsCurrent(announcement)) return
        announcementConnection.set(announcement.connection)
        try {
            performConnectAnnouncementBound(announcement)
        } finally {
            announcementConnection.remove()
        }
    }

    private fun performConnectAnnouncementBound(announcement: MqttConnectAnnouncement) {
        // Capture before command subscriptions can initialize the state-channel registry. Every entity
        // in this announcement sees the same immutable values; later bounded snapshots may add channels
        // when a transiently unavailable live probe recovers.
        val capabilitySnapshot = discoveryCapabilities.snapshot()
        synchronized(discoveryAnnouncementLock) {
            if (!connectionAnnouncementIsCurrent(announcement)) return@synchronized
            subscribe("ha-paneld/$panel/+/set") { topic, payload, retained -> onCommand(topic, payload, retained) }
            // Re-announce discovery when HA (re)starts — its birth message on homeassistant/status. With
            // non-retained discovery this is what rebuilds our entities after an HA restart. The retained
            // online delivered while this connect announcement is still running is suppressed below.
            subscribe("homeassistant/status") { _, payload, retained ->
                if (mqttIsHaOnline(payload)) requestReAnnounce()
                // Second lifecycle source. Home Assistant deliberately disconnects UNGRACEFULLY on
                // shutdown so the broker publishes this will, which is why it works at all — and it needs
                // no WebSocket subscription, so it survives the non-administrator accounts panels use.
                haLifecycleLease?.let { lease ->
                    haLifecycleFromMqttStatus(payload, retained)
                        ?.let { HaLifecycleRuntime.observeMqtt(lease, it) }
                }
            }
            mqttStalePanelCleanup(stalePanelId, panel).forEach {
                publish(it.topic, it.payload, retain = it.retain)
            }
            publishDiscovery(capabilitySnapshot)
            // On a core upgrade or active-profile revision switch, actively clear any entity the previous
            // runtime published but this one no longer does. A profile switch can remove hardware-backed
            // entities without changing Config.VERSION, so both immutable identities belong in the marker.
            // publishDiscovery above populated publishedConfigTopics for the current capability set.
            val brokerIdentity = mqttFamilyBrokerIdentity(activeBroker) ?: activeBroker.trim()
            val discoveryMarker = mqttDiscoveryCleanupMarker(Config.VERSION, profileIdentity, brokerIdentity)
            if (config.lastDiscoveryVersion != discoveryMarker) {
                pruneStaleDiscovery { acknowledged ->
                    if (acknowledged && connectionAnnouncementIsCurrent(announcement)) {
                        config.setLastDiscoveryVersion(discoveryMarker)
                    } else if (lifecycle.isOpen()) {
                        Log.w(TAG, "discovery prune was not fully acknowledged; retrying after reconnect")
                    }
                }
            }
            proximityPublication.serialized {
                val proximityAvailable = learnedProximityEligible() && available == true
                if (proximityAvailable) {
                    // Current retained states must precede the entity-specific online edge. These are the
                    // first state-converger admissions on this connection, so the bounded outbox has room.
                    stateConverger.reconcile("proximity", force = true)
                    stateConverger.reconcile("proximity_level", force = true)
                    publish(proximityAvailabilityTopic, "online", retain = true)
                } else {
                    publish(proximityAvailabilityTopic, "offline", retain = true)
                    // Old releases retained a guessed ON/OFF value. Remove it while the learner is not
                    // authoritative so HA cannot display a stale per-firmware heuristic as current data.
                    publish(stateProximity, "", retain = true)
                    publish(stateProximityLevel, "", retain = true)
                }
            }
            restoreAndPublishStates()
            stateConverger.reconcileAll()
            // Entity-specific availability and retained state must converge before the panel-wide
            // online edge; otherwise HA can briefly resurrect a previous process's proximity value.
            publish(availabilityTopic, "online", retain = true) { acknowledged ->
                if (acknowledged && connectionAnnouncementIsCurrent(announcement)) {
                    completeAnnouncementIfReady(announcementReadiness.acknowledgeOnline(announcement))
                } else if (lifecycle.isOpen() && connectionGeneration.isCurrent(announcement.generation)) {
                    Log.w(TAG, "MQTT connect announcement online edge was not acknowledged")
                }
            }
            // A retained birth admitted during subscribe is already covered by this later announcement.
            // Invalidate that queued generation while still holding the shared announcement lock.
            reannounceDispatcher.suppressPending()
        }
        if (!connectionAnnouncementIsCurrent(announcement)) return
        reconcileZigbeeOnConnect() // boot-restore: start the gateway if left ON and nothing else has
        // A reconnect storm must not create one blocked privileged thread per callback.
        adbReassertWorker.submit(Unit)
        maybeResolveHaLink() // native HA session first, MQTT credential fallback; off-thread, best-effort
    }

    private fun connectionAnnouncementIsCurrent(announcement: MqttConnectAnnouncement): Boolean =
        lifecycle.isOpen() && (state == "announcing" || state == "connected") &&
            connectionGeneration.isCurrent(announcement.generation) &&
            transport.isCurrent(announcement.connection)

    /** PUBACK callbacks only enqueue this constant state transition; they never wait on bridge work. */
    private fun completeAnnouncementIfReady(announcement: MqttConnectAnnouncement?) {
        announcement ?: return
        io.github.maxlyth.hapaneld.mqtt.StateConverger.dispatch {
            lifecycle.runIfOpen(Unit) {
                if (state == "announcing" && connectionAnnouncementIsCurrent(announcement)) {
                    publishRecoveryLifecycleState("connected", applicationReadyEver = true)
                    // Restored-family grace ends only at application readiness, never at CONNACK or an
                    // unrelated heartbeat ACK that can succeed while discovery remains wedged.
                    selectedFamilyRoute
                        ?.takeIf {
                            MqttAddressFamilyPolicy.fromConfig(runtimeMqttAddressFamily) ==
                                MqttAddressFamilyPolicy.AUTOMATIC
                        }
                        ?.let { selected ->
                        val confirmed = synchronized(familyRecoveryLock) {
                            familyPreference.confirmConnectedRoute(
                                selected.brokerIdentity,
                                selected.connectAttempt,
                                selected.preferIpv4,
                            )
                        }
                        if (!confirmed) {
                            Log.w(TAG, "MQTT readiness could not durably confirm its selected address family")
                        }
                    }
                    familyPreference.markBrokerProgress()
                    synchronized(announcementBudgetLock) {
                        announcementRecoveryIdentity?.let { identity ->
                            when (reconcileMqttAnnouncementReadinessBudget(
                                consumedHere = ANNOUNCEMENT_BOUNDARY_CONSUMED_HERE.get() == identity,
                                clearInheritedBoundary = {
                                    config.clearMqttAnnouncementBoundary(identity)
                                },
                            )) {
                                MqttAnnouncementReadinessBudgetResult.PRESERVED_PENDING_BOUNDARY -> {
                                    announcementProcessRecoveryAvailable = false
                                    Log.i(TAG, "MQTT readiness arrived after its process boundary was committed; preserving the spent token")
                                }
                                MqttAnnouncementReadinessBudgetResult.REARMED ->
                                    announcementProcessRecoveryAvailable = true
                                MqttAnnouncementReadinessBudgetResult.CLEAR_FAILED -> {
                                    announcementProcessRecoveryAvailable = false
                                    Log.w(TAG, "MQTT readiness could not durably clear its process-recovery budget")
                                }
                            }
                        }
                    }
                    Log.i(TAG, "MQTT connected — online + state acknowledged for $panel")
                }
            }
        }
    }

    /** Pure classification plus one bounded enqueue; HiveMQ's event loop never enters bridge work. */
    private fun onDisconnected(
        connection: MqttConnectionLease?,
        causeMessage: String?,
    ): Boolean {
        val classified = classifyDisconnect(causeMessage)
        val admission = connectionEventDispatcher.submit(
            MqttConnectionEvent.Disconnected(connection, classified, causeMessage),
        )
        // A stop-racing callback sees the closed owner and must suppress Hive's automatic reconnect.
        return mqttAutomaticReconnectAllowed(classified, admission)
    }

    /** Re-publish discovery + current states — on HA's `online` birth (non-retained discovery must be
     *  re-sent when HA restarts). No-op if not connected. */
    private fun requestReAnnounce() {
        when (reannounceDispatcher.submit()) {
            LatestDispatcher.Admission.COALESCED ->
                FeatureCosts.registry.recordCoalesced(FeatureCostOperation.MQTT_DISCOVERY_REANNOUNCE)
            LatestDispatcher.Admission.REJECTED, // unreachable for a single slot
            LatestDispatcher.Admission.CLOSED ->
                FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_DISCOVERY_REANNOUNCE)
            LatestDispatcher.Admission.ACCEPTED -> Unit
        }
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.MQTT_DISCOVERY_REANNOUNCE,
            reannounceDispatcher.pendingCount(),
        )
    }

    private fun performReAnnounce(generation: Long) {
        synchronized(discoveryAnnouncementLock) {
            FeatureCosts.registry.setBacklog(FeatureCostOperation.MQTT_DISCOVERY_REANNOUNCE, 0)
            val cost = FeatureCosts.registry.span(FeatureCostOperation.MQTT_DISCOVERY_REANNOUNCE)
            try {
                if (!lifecycle.isOpen() || !isConnected() || !reannounceDispatcher.isCurrent(generation)) {
                    cost.outcome(FeatureCostOutcome.CANCELLED)
                    return@synchronized
                }
                // HA frequently emits a retained birth immediately after our connect announcement and may
                // repeat births while its MQTT integration settles. Reuse one monotonic-TTL snapshot across
                // that bounded burst instead of repeating privileged probes for byte-identical discovery.
                publishDiscovery(discoveryCapabilities.snapshot(REANNOUNCE_CAPABILITY_TTL_MS))
                restoreAndPublishStates()
                cost.work(units = publishedConfigTopics.size.toLong())
                Log.i(TAG, "HA online — re-announced discovery for $panel")
            } catch (e: Exception) {
                cost.outcome(FeatureCostOutcome.FAILURE)
                Log.w(TAG, "HA discovery reannouncement failed", e)
            } finally {
                cost.close()
            }
        }
    }

    /** Republish discovery only when the live panel URL changed. Called by Android network callbacks;
     * reconnect and HA-birth paths already call [publishDiscovery] and evaluate the same supplier. */
    internal fun refreshDiscoveryAddress() {
        val current = configUrl()?.takeIf(String::isNotBlank)
        if (!shouldRepublishDiscoveryAddress(isConnected(), lastPublishedConfigUrl, current)) return
        requestReAnnounce()
    }

    /** Re-evaluate transport-dependent discovery after Android changes the default network. */
    internal fun refreshNetworkState() {
        if (!isConnected()) return
        discoveryCapabilities.invalidate()
        requestReAnnounce()
    }

    /** Re-project live Config into discovery/state without replacing a healthy broker connection. */
    internal fun refreshConfiguration(reannounce: Boolean, resolveHaLink: Boolean) {
        if (reannounce) requestReAnnounce()
        if (resolveHaLink) maybeResolveHaLink()
    }

    /**
     * Resolve this panel's HA device-settings URL. A configured built-in renderer is authoritative: use
     * its exact endpoint and current access token, even when MQTT is disabled or its broker credentials
     * belong to another HA instance. MQTT username/password remain a compatibility fallback only when no
     * native renderer connection exists. Legacy cache entries have no [Config.haDeviceLinkTarget], so an
     * upgrade immediately repairs them rather than trusting their old random registry id for another 6h.
     */
    internal fun maybeResolveHaLink() {
        lifecycle.runIfOpen(Unit) admission@{
            if (haLinkResolutionThread.get() != null) return@admission
            lateinit var worker: Thread
            worker = Thread {
                try {
                    val nativeBase = config.haUrl.trim().trimEnd('/')
                    if (nativeBase.isNotBlank()) {
                        val target = HaLink.resolutionTarget(nativeBase, panel)
                        if (config.haDeviceLinkIsFresh(target, System.currentTimeMillis(), HA_LINK_TTL_MS)) return@Thread
                        val stillCurrent = {
                            lifecycle.isOpen() &&
                                config.haUrl.trim().trimEnd('/') == nativeBase && config.panelId == panel
                        }
                        val token = DashboardAuth.forConfig(
                            config,
                            stillCurrent = stillCurrent,
                            persistRefresh = { snapshot, access, expiry ->
                                lifecycle.runIfOpen(false) {
                                    stillCurrent() && config.setHaRefreshedTokenIfOwned(snapshot, access, expiry)
                                }
                            },
                        ).session?.accessToken ?: return@Thread
                        val familyPolicy = MqttAddressFamilyPolicy.fromConfig(config.mqttAddressFamily)
                        val link = HaLink.resolveWithAccessToken(
                            nativeBase, token, listOf(panel, runtimeFriendlyName),
                            preferIpv4 = familyPolicy.initialPreferIpv4,
                            ipv4Only = familyPolicy.ipv4Only,
                        ) ?: return@Thread
                        lifecycle.runIfOpen(Unit) {
                            if (stillCurrent()) config.setHaDeviceUrl(link, target)
                        }
                        return@Thread
                    }

                    val credentials = config.mqttCredentialsSnapshot()
                    if (credentials.user.isBlank() || credentials.password.isBlank()) return@Thread
                    // Prefer mDNS (broker-matched). Else derive HA from the broker HOST: a working broker is
                    // likely the HA server too. HaLink reads /api/config for its canonical browser link.
                    val base = discoverHaUrl(credentials.broker) ?: brokerHttpsUrl(credentials.broker) ?: return@Thread
                    val target = HaLink.resolutionTarget(base, panel)
                    if (config.haDeviceLinkIsFresh(target, System.currentTimeMillis(), HA_LINK_TTL_MS)) return@Thread
                    val linkFamilyPolicy = MqttAddressFamilyPolicy.fromConfig(config.mqttAddressFamily)
                    val link = HaLink.resolve(
                        base, credentials.user, credentials.password, listOf(panel, runtimeFriendlyName),
                        preferIpv4 = linkFamilyPolicy.initialPreferIpv4,
                        ipv4Only = linkFamilyPolicy.ipv4Only,
                    ) ?: return@Thread
                    lifecycle.runIfOpen(Unit) {
                        if (config.haUrl.isBlank() && config.panelId == panel &&
                            config.mqttCredentialsSnapshot() == credentials
                        ) config.setHaDeviceUrl(link, target)
                    }
                } finally {
                    haLinkResolutionThread.compareAndSet(worker, null)
                }
            }.apply { isDaemon = true; name = "ha-device-link" }
            if (!haLinkResolutionThread.compareAndSet(null, worker)) return@admission
            try {
                worker.start()
            } catch (failure: Throwable) {
                haLinkResolutionThread.compareAndSet(worker, null)
                throw failure
            }
        }
    }

    /** `https://<broker-host>` when the broker is a hostname (wildcard/SAN certs make HTTPS verifiable);
     *  null for a raw IP broker (cert would mismatch) or no broker. */
    private fun brokerHttpsUrl(broker: String): String? {
        val host = broker.substringAfter("://").substringBefore(":").substringBefore("/").trim()
        if (host.isBlank() || host.contains(":") || host.matches(Regex("[0-9.]+"))) return null
        return "https://$host"
    }

    /**
     * Sync HA to the panel's actual state on (re)connect, so the UI isn't stale after a reboot
     * (hardware resets, but HA holds the last retained state). Re-applies the last LED colour and
     * publishes the current screen/volume/navigate states.
     */
    private fun restoreAndPublishStates() {
        volume.getPercent().let { lastPublishedVolume = it }
        // Reconnect is just another reconciliation trigger; the registry observes physical state.
        stateConverger.reconcile("screen", force = true)
        // Navigate: last pushed path, else default to the dashboard root "/" so the entity shows a
        // sensible local path instead of "unknown" before anything has been navigated.
        // LED: re-apply the last colour to the hardware (reset on reboot) and publish it.
        reapplyStoredLed()
        if (hasButtonBacklight) {
            config.lastButtonBacklight.takeIf { it >= 0 }?.let { HelperClient.send("BTN $it") }
        }
    }

    // ---- command dispatch ----

    private fun onCommand(topic: String, payloadBytes: ByteArray, retained: Boolean) {
        val retired = !lifecycle.isOpen()
        if (!mqttAcceptsCommand(retired, retained, config.panelAssistantAuthority)) {
            if (!retired && !retained) {
                // A panel cut over to the native transport takes no command from MQTT, session or not.
                FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_COMMAND_DISPATCH)
                if (nativeAuthorityDropLogged.compareAndSet(false, true)) {
                    Log.w(TAG, "ignoring MQTT commands: Panel Assistant holds command authority for this panel")
                }
            }
            if (retained && !retired) {
                FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_COMMAND_DISPATCH)
                Log.w(TAG, "ignoring RETAINED command on $topic (${payloadBytes.size} bytes)")
            }
            return
        }
        val kind = commandKind(topic)
        // State commands conflate by their transport-neutral channel, derived here at the MQTT edge.
        val channel = if (kind == CommandKind.LATEST) mqttCommandChannel(panel, topic) else null
        if (kind == null || kind == CommandKind.LATEST && channel == null || payloadBytes.size > MAX_COMMAND_PAYLOAD_BYTES) {
            FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_COMMAND_DISPATCH)
            return
        }

        // HiveMQ reuses callback-owned values. Copy only after the cheap retained/topic/size gates, then
        // return immediately so a root call or slow controller can never pin its network callback.
        val payload = payloadBytes.copyOf()
        val command = {
            // Authority may have changed while this command waited behind a slow handler.
            if (mqttAcceptsCommand(!lifecycle.isOpen(), retained, config.panelAssistantAuthority)) {
                consumeCommand(topic, payload, MQTT_PEER)
            } else {
                FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_COMMAND_DISPATCH)
            }
            Unit
        }
        val admission = when (kind) {
            CommandKind.LATEST -> commandDispatcher.submitLatest(requireNotNull(channel), command)
            CommandKind.ACTION -> commandDispatcher.submitAction(command)
        }
        recordCommandAdmission(admission)
    }

    private fun commandKind(topic: String): CommandKind? = when {
        topic in stateCommandTopics -> CommandKind.LATEST
        topic in actionCommandTopics -> CommandKind.ACTION
        indexedCommandTopic(topic, "ha-paneld/$panel/relay") -> CommandKind.LATEST
        indexedCommandTopic(topic, "ha-paneld/$panel/button_led") -> CommandKind.LATEST
        else -> null
    }

    private fun indexedCommandTopic(topic: String, prefix: String): Boolean {
        if (!topic.startsWith(prefix) || !topic.endsWith("/set")) return false
        return topic.substring(prefix.length, topic.length - 4).toIntOrNull() in 1..MAX_DYNAMIC_COMMAND_INDEX
    }

    private fun recordCommandAdmission(admission: MqttCommandDispatcher.Admission) {
        when (admission) {
            MqttCommandDispatcher.Admission.ACCEPTED -> Unit
            MqttCommandDispatcher.Admission.COALESCED ->
                FeatureCosts.registry.recordCoalesced(FeatureCostOperation.MQTT_COMMAND_DISPATCH)
            MqttCommandDispatcher.Admission.REJECTED,
            MqttCommandDispatcher.Admission.CLOSED ->
                FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_COMMAND_DISPATCH)
        }
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.MQTT_COMMAND_DISPATCH,
            commandDispatcher.pendingCount(),
        )
    }

    /**
     * The registry key whose newer external intent became durable during the dispatch running on this
     * thread, set by a handler that persists before it actuates.
     *
     * Thread-scoped rather than a plain field because HTTP live settings reach the same handlers through
     * the same dispatcher: a shared field could be set by one path and read by another. [consumeCommand]
     * clears it on entry and exit, so a value can only ever describe the dispatch that is running.
     */
    private val externalIntentCommitted = ThreadLocal<String?>()

    /**
     * The approval principal of the dispatch running on this thread: `mqtt` or `panel_assistant`. The broker
     * binds an approval to its peer, so an approval primed by one transport cannot be consumed by the other.
     */
    private val commandPeer = ThreadLocal<String?>()

    private val nativeAuthorityDropLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Run one Panel Assistant command on the same ordered authority MQTT commands use, keyed by the same
     * channel, so a command for one channel conflates identically whichever transport delivered it. [done]
     * receives exactly one result. Action channels (reload, reboot, updates) are not accepted here.
     *
     * A temporary adapter: until the common handlers take a channel identity, it reaches them through the
     * channel's MQTT command topic, with the payload `PanelAssistantCommandTranslation` mapped from the
     * channel's declared options.
     */
    internal fun submitPanelAssistantCommand(
        command: PanelAssistantCommand,
        done: (PanelAssistantCommandResult) -> Unit,
    ) {
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish = { result: PanelAssistantCommandResult -> if (answered.compareAndSet(false, true)) done(result) }
        if (!lifecycle.isOpen()) {
            finish(PanelAssistantCommandResult.Failed(PanelAssistantCommandProcessor.CODE_FAILED))
            return
        }
        val topic = "ha-paneld/$panel/${command.channel}/set"
        when (commandKind(topic)) {
            null -> return finish(PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_UNKNOWN_CHANNEL))
            CommandKind.ACTION ->
                return finish(PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_NOT_COMMANDABLE))
            CommandKind.LATEST -> Unit
        }
        val payload = command.payload.toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_COMMAND_PAYLOAD_BYTES) {
            return finish(PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_INVALID_VALUE))
        }
        val admission = commandDispatcher.submitLatest(
            key = command.channel,
            onSkipped = { execution ->
                finish(
                    if (execution == MqttCommandDispatcher.Execution.SUPERSEDED) {
                        PanelAssistantCommandResult.Superseded
                    } else {
                        PanelAssistantCommandResult.Failed(PanelAssistantCommandProcessor.CODE_FAILED)
                    },
                )
            },
        ) {
            val refusal = command.admit()
            if (refusal != null) {
                finish(refusal)
            } else {
                finish(panelAssistantCommandResult(consumeCommand(topic, payload, PANEL_ASSISTANT_PEER)))
            }
        }
        recordCommandAdmission(admission)
    }

    /** Returns the failure the command handler raised, already logged, or null when it applied. */
    private fun consumeCommand(topic: String, payloadBytes: ByteArray, peer: String): Exception? {
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.MQTT_COMMAND_DISPATCH,
            commandDispatcher.pendingCount(),
        )
        val cost = FeatureCosts.registry.span(FeatureCostOperation.MQTT_COMMAND_DISPATCH)
            .work(units = 1, bytes = payloadBytes.size.toLong())
        externalIntentCommitted.remove()
        commandPeer.set(peer)
        try {
            dispatchCommand(topic, payloadBytes)
            externalLiveSettingKey(panel, topic)?.let { key ->
                check(onExternalSettingApplied(key)) { "failed to supersede pending HTTP setting $key" }
            }
            return null
        } catch (e: Exception) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            Log.w(TAG, "command failed on $topic (${payloadBytes.size} bytes)", e)
            // The handler may have made newer intent durable before failing to actuate it. Retire the
            // older queued HTTP value anyway — leaving it lets startup replay commit the stale value
            // over the newer one. Best-effort and logged: this is already the failure path, and a
            // second throw here would only replace one diagnosis with another.
            supersededKeyAfterFailedDispatch(externalLiveSettingKey(panel, topic), externalIntentCommitted.get())
                ?.let { key ->
                    if (!onExternalSettingApplied(key)) {
                        Log.w(TAG, "failed to supersede pending HTTP setting $key after a failed command")
                    }
                }
            return e
        } finally {
            commandPeer.remove()
            externalIntentCommitted.remove()
            cost.close()
        }
    }

    private fun dispatchCommand(topic: String, payloadBytes: ByteArray) {
        val payload = String(payloadBytes, Charsets.UTF_8)
        // NEVER act on a RETAINED command. Command topics are fire-and-forget; a retained payload is
        // always stale — e.g. a broker- or automation-retained screen-off replayed on every (re)subscribe,
        // which is exactly what stranded a panel dark after a reconnect. Our own state/discovery stays
        // retained; inbound commands must be fresh.
        // Relay + button-LED topics are dynamic (relay1/…, button_led1/…) — match before the fixed set.
        if (topic.startsWith("ha-paneld/$panel/relay") && topic.endsWith("/set")) {
            handleRelay(topic, payload); return
        }
        if (topic.startsWith("ha-paneld/$panel/button_led") && topic.endsWith("/set")) {
            handleButtonLed(topic, payload); return
        }
        // Every registry-declared live setting, whether it arrived from MQTT or HTTP, reaches the same
        // exhaustive owner mapping. Action commands and non-live camera control remain below.
        externalLiveSettingKey(panel, topic)?.let { key ->
            dispatchLiveSetting(key = key, value = payload, handlers = this)
            return
        }
        when (topic) {
            cmdScreen -> handleScreen(payload)
            cmdLed -> handleLed(payload)
            cmdNavigate -> handleNavigate(payload)
            cmdVolume -> handleVolume(payload)
            cmdReload -> {
                authorizeRemoteSensitive(
                    SensitiveOperation.DASHBOARD_RELOAD,
                    payload,
                    "Reload the dashboard renderer from Home Assistant",
                )
                handleReload()
            }
            cmdReboot -> {
                authorizeRemoteSensitive(
                    SensitiveOperation.DEVICE_REBOOT,
                    payload,
                    "Reboot this panel from Home Assistant",
                )
                system.reboot()
            }
            cmdButtons -> if (hasButtonBacklight) handleButtons(payload)
            cmdUpdateCompanion -> handleSoftwareCommand(SoftwareComponent.COMPANION, payload) {
                authorizeRemoteSensitive(
                    SensitiveOperation.APK_INSTALL,
                    "companion\u0000$payload",
                    "Install the Home Assistant Companion update from Home Assistant",
                )
                onUpdateCompanion()
            }
            cmdUpdatePaneld -> handleSoftwareCommand(SoftwareComponent.PANELD, payload) {
                authorizeRemoteSensitive(
                    SensitiveOperation.APK_INSTALL,
                    "paneld\u0000$payload",
                    "Install the ha-paneld update from Home Assistant",
                )
                onSelfUpdate(true)
            }
            cmdCameraEnabled -> handleCameraEnabled(payload)
            else -> Log.d(TAG, "unhandled command topic $topic")
        }
    }

    /**
     * The update topics carry two senders for one release: the legacy button's `PRESS` keeps its
     * existing behaviour, the update entity's `install` takes strict admission, and every other payload
     * is refused before anything is resolved, downloaded or approved.
     */
    private fun handleSoftwareCommand(component: SoftwareComponent, payload: String, legacy: () -> Unit) {
        val outcome = SoftwareUpdateEntities.route(
            payload = payload,
            inputs = { softwareUpdateInputs(component) },
            legacy = legacy,
            authorize = { tag ->
                authorizeRemoteSensitive(
                    SensitiveOperation.APK_INSTALL,
                    "${component.wire}\u0000install\u0000$tag",
                    "Install ${component.entityName} $tag from Home Assistant",
                )
            },
            install = { tag -> onSoftwareInstall(component, tag) },
        )
        when (outcome) {
            SoftwareCommandOutcome.Legacy -> return
            is SoftwareCommandOutcome.Started ->
                Log.i(TAG, "${component.wire} install of ${outcome.tag} started from Home Assistant")
            is SoftwareCommandOutcome.Busy ->
                Log.w(TAG, "${component.wire} install of ${outcome.tag} not started: another destructive operation is running")
            is SoftwareCommandOutcome.Refused ->
                Log.w(TAG, "refusing ${component.wire} update command from Home Assistant: ${outcome.reason} (${payload.length} chars)")
        }
        // Home Assistant must see the truth now: progress if it started, unchanged state otherwise.
        reconcileSoftwareUpdates(announcing = false)
    }

    /** Re-evaluate both update entities: catalog refreshes, install start and finish, package changes. */
    fun publishSoftwareUpdates() {
        dispatchStateWork { reconcileSoftwareUpdates(announcing = false) }
    }

    private fun softwareUpdateInputs(component: SoftwareComponent): SoftwareUpdateInputs? {
        val sources = softwareUpdateSources ?: return null
        val snapshot = try {
            sources()
        } catch (e: Exception) {
            Log.w(TAG, "update entity inputs unavailable", e)
            return null
        }
        return snapshot.inputs(component, discoveryCapabilities.cached()?.canInstallVerifiedApps == true)
    }

    /**
     * Converge Home Assistant's view of both update entities. Discovery is republished only when the
     * entity's shape changes (or on an announcement), and an entity that has lost `latest_version` is
     * recreated, because Home Assistant keeps a value that a later state message omits.
     */
    private fun reconcileSoftwareUpdates(announcing: Boolean) {
        if (softwareUpdateSources == null) return
        val discovery = softwareUpdateDiscovery ?: return
        synchronized(softwareUpdateLock) {
            for (component in SoftwareComponent.entries) {
                val inputs = softwareUpdateInputs(component) ?: return
                val current = SoftwareUpdateEntities.shape(inputs)
                val previous = if (component in softwareShapes) softwareShapes[component]
                else SoftwareDiscoveryShape.decode(config.softwareUpdateDiscoveryShape(component.wire))
                val configTopic = SoftwareUpdateEntities.configTopic(panel, component)
                val step = SoftwareUpdateEntities.transition(previous, current, announcing)
                val plan = SoftwareUpdateEntities.discoveryPlan(
                    panel, inputs, step, discovery.availability, discovery.device,
                )
                for (publication in plan) {
                    if (publication.topic == configTopic) {
                        // Records the topic for stale-discovery pruning; retains exactly the tombstones.
                        publishConfig("update", SoftwareUpdateEntities.uniqueId(panel, component), publication.payload)
                    } else {
                        publish(publication.topic, publication.payload, retain = publication.retain)
                    }
                }
                softwareShapes[component] = current
                if (previous != current) {
                    config.setSoftwareUpdateDiscoveryShape(component.wire, current.encode())
                }
                stateConverger.reconcile(
                    SoftwareUpdateEntities.stateChannelKey(component),
                    force = announcing || step != SoftwareDiscoveryStep.NONE,
                )
            }
        }
    }

    /** Publish screen=ON to HA after a LOCAL wake (e.g. wake-on-wave), so `light.<panel>_screen` tracks
     *  reality instead of staying OFF. No-op if the broker isn't connected yet. */
    fun publishScreenOn() {
        dispatchStateWork { publishScreenBrightness(brightness.getCommanded().coerceAtLeast(1)) }
    }

    /** Reconcile the existing physical screen light after a service-owned automatic transition. */
    fun publishScreenState() {
        dispatchStateWork {
            if (screen.isIntendedOff()) publishScreenOff()
            else publishScreenBrightness(brightness.getCommanded().coerceAtLeast(1))
        }
    }

    /** Publish the current volume to HA after a LOCAL change (e.g. navbar Volume ±), so
     *  `number.<panel>_volume` tracks reality. No-op if the broker isn't connected yet. */
    fun publishVolume() {
        dispatchStateWork { stateConverger.reconcile("volume", force = true) }
    }

    private fun handleScreen(payload: String) {
        val command = io.github.maxlyth.hapaneld.control.ScreenCommandPolicy.parse(payload)
        if (!command.on) {
            screen.sleep()
            publishScreenOff()
            return
        }
        // Capture explicit intent before wake completion can reapply automatic control.
        command.brightness?.let {
            autoBright.noteExternalBrightness(it, io.github.maxlyth.hapaneld.control.BrightnessPreferenceOrigin.HOME_ASSISTANT)
        }
        // A bare ON on a lit panel changes nothing: ensureOn skips the wake path, whose brightness
        // fallback would otherwise restore a stale saved level on the brightness-zero route.
        val delivered = io.github.maxlyth.hapaneld.control.ScreenCommandPolicy.executeOn(
            command,
            ensureOn = screen::ensureOn,
            setBrightness = brightness::setBrightness,
            commandedLevel = brightness::getCommanded,
            noteLevel = screen::noteLevel,
            publish = ::publishScreenBrightness,
        )
        check(delivered) { "screen wake failed; ON not published" }
    }

    /**
     * Drive the LED from persisted intent and record whether the actuation was confirmed. Reconnect uses
     * it because the LED resets on reboot; the camera indicator uses it to give the LED back after
     * holding it, because persisted intent is the only source that also reflects a command that arrived
     * while the hold refused ordinary writes.
     */
    fun reapplyStoredLed() {
        val desiredLed = LedCommandPolicy.stored(config.lastLed, config.lastLedEffect)
        ledActuationKnown = false
        ledActuationKnown = if (desiredLed.on && desiredLed.effect != null) {
            ledEffect.start(desiredLed.effect, desiredLed.red, desiredLed.green, desiredLed.blue, desiredLed.brightness)
        } else if (desiredLed.on) {
            ledEffect.setSolid(
                desiredLed.red * desiredLed.brightness / 255,
                desiredLed.green * desiredLed.brightness / 255,
                desiredLed.blue * desiredLed.brightness / 255,
            )
        } else {
            // Force the hardware off too — the LED can power up to a default on reboot, so publishing
            // OFF without driving it leaves HA and the physical LED disagreeing (seen on rk3576).
            ledEffect.setOff()
        }
        stateConverger.reconcile("led", force = true)
    }

    private fun handleLed(payload: String) {
        val desired = LedCommandPolicy.command(
            payload,
            LedCommandPolicy.stored(config.lastLed, config.lastLedEffect),
        )
        // Persist user intent even when hardware is temporarily unreachable so reconnect can retry it,
        // but clear observation authority before exposing the new desired state.
        ledActuationKnown = false
        config.lastLed = desired.storedColor()
        config.lastLedEffect = desired.storedEffect()
        ledActuationKnown = if (!desired.on) {
            ledEffect.setOff()
        } else if (desired.effect != null) {
            ledEffect.start(desired.effect, desired.red, desired.green, desired.blue, desired.brightness)
        } else {
            ledEffect.setSolid(
                desired.red * desired.brightness / 255,
                desired.green * desired.brightness / 255,
                desired.blue * desired.brightness / 255,
            )
        }
        stateConverger.reconcile("led", force = true)
    }

    override fun handleWakeOnWave(payload: String) {
        if (!hasProximity) {
            Log.w(TAG, "ignoring wake_on_wave command without a proximity source")
            return
        }
        val on = payload.trim().equals("ON", ignoreCase = true)
        config.setWakeOnWave(on)
        stateConverger.reconcile("wake_on_wave", force = true)
    }

    override fun handleAutoSleep(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        applyAutoSleepSetting(on, config::setAutoSleep, onAutoSleepConfigChanged)
        stateConverger.reconcile("auto_sleep", force = true)
    }

    override fun handleAutoSleepSource(payload: String) {
        config.setRaw(requireNotNull(SettingsRegistry.spec("auto_sleep_source")), payload)
        onAutoSleepConfigChanged(config.autoSleep)
        stateConverger.reconcile("auto_sleep", force = true)
    }

    override fun handlePreventIdleDim(payload: String, approvalRequired: Boolean) {
        val on = requireNotNull(PowerSafetyMutationPolicy.parseGuardSwitch(payload)) {
            "prevent_idle_dim accepts only ON or OFF"
        }
        if (approvalRequired && PowerSafetyMutationPolicy.requestsSafetyReduction(
                keepAwake = config.keepAwake,
                requestedKeepAwake = null,
                preventIdleDim = config.preventIdleDim,
                requestedPreventIdleDim = on,
            )
        ) {
            authorizeRemoteSensitive(
                SensitiveOperation.POWER_CONFIGURATION,
                "prevent_idle_dim\u0000$payload",
                "Disable the panel's native screen-timeout guard from Home Assistant",
            )
        }
        config.setPreventIdleDim(on)
        brightness.applyPreventIdleDim(on, config)
        stateConverger.reconcile("prevent_idle_dim", force = true)
    }

    override fun handleTouchSound(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        // Persist the intent before actuating it, like every other live setting. The state channel now
        // reports the persisted value, so a change that only reached the controller would publish the old
        // value straight back and flip the switch in Home Assistant. The HTTP path has already committed
        // the same value through applyLiveSettingObserved, which makes this idempotent rather than
        // redundant: MQTT reaches this handler without it.
        check(config.commitTouchSound(on)) { "touch sound intent could not be persisted" }
        // Durable from here, so a failed actuation below must not leave an older queued HTTP value alive
        // to be replayed over this one. The actuation throws past consumeCommand's ordinary supersession.
        externalIntentCommitted.set("touch_sound")
        requireControlApplied("touch_sound", touchSound.apply(on)) { "touch sound transition failed" }
        stateConverger.reconcile("touch_sound", force = true)
    }

    override fun handleWatchdog(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        config.setWatchdogEnabled(on)
        watchdog.apply(on)
        stateConverger.reconcile("watchdog", force = true)
    }

    // The wake-word listener / Assist pipeline itself is owned by the voice-coordinator lane: this only
    // persists the switch and publishes its state, exactly like every other config-only entity here.
    // Disabling does not force-reconcile voice_state — that stays whatever the coordinator's
    // VoiceStateAuthority currently reports (its own default is OFF, and the coordinator is expected to
    // fall back there once it observes the setting go off).
    //
    // The command topic is subscribed unconditionally (ha-paneld/$panel/+/set), so HA discovery
    // gating alone is not a write guard: a directly-published ON must still be refused here when this
    // bridge generation has no microphone, exactly like handleWakeOnWave refuses without hasProximity.
    // Unlike that ignore-only refusal, this one also force-reconciles: voice_enabled's own channel now
    // reports OFF whenever !hasMicrophone (see createStateConverger), so the reconcile call overwrites
    // any stale retained ON left over from before the capability disappeared (e.g. a profile switch)
    // rather than merely declining to persist the new one.
    //
    // A refusal or a failed durable commit THROWS rather than returning silently. dispatchSetting runs
    // inside the HTTP live-setting path's command-dispatcher lane, whose exception handling is what
    // converts a thrown failure into LiveSettingApplyResult.FAILED — a silent return here previously let
    // both cases report execution SUCCEEDED, so a refused or unpersisted ON came back to the caller as
    // APPLIED. The decision itself is the pure, directly-testable applyAcknowledgedVoiceEnabled (mirrors
    // applyAcknowledgedKioskSetting), which always reconciles before reporting failure.
    override fun handleVoiceEnabled(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        if (on && !hasMicrophone) Log.w(TAG, "ignoring voice_enabled ON command without a microphone")
        val accepted = applyAcknowledgedVoiceEnabled(
            on = on,
            hasMicrophone = hasMicrophone,
            commit = config::commitVoiceEnabled,
            reconcile = { stateConverger.reconcile("voice_enabled", force = true) },
        )
        check(accepted) {
            if (on && !hasMicrophone) "this panel has no microphone capability"
            else "voice_enabled setting commit failed"
        }
    }

    override fun handleKiosk(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        check(onDirectKioskSetting(on)) { "kiosk setting was not durably acknowledged" }
        stateConverger.reconcile("kiosk_lock", force = true)
    }

    /** Publish the kiosk-lock state — used by the on-device unlock gesture, which turns it OFF outside the
     *  MQTT/HTTP command path and must still tell HA. */
    fun publishKioskState() {
        dispatchStateWork { stateConverger.reconcile("kiosk_lock", force = true) }
    }

    /** Publish the current voice-assistant phase — called from VoiceStateAuthority's change listener,
     *  which the voice-coordinator lane drives outside any MQTT/HTTP command path. */
    fun publishVoiceState() {
        dispatchStateWork { stateConverger.reconcile("voice_state", force = true) }
    }

    /** Publish only the already-committed channel. Staged self-update transactions call this after the
     * exact candidate has passed compatibility admission, never while the preference is still pending. */
    fun publishSelfUpdateChannelState() {
        dispatchStateWork { stateConverger.reconcile("update_channel", force = true) }
    }

    override fun handleCompanionAuto(payload: String, approvalRequired: Boolean) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        if (on && approvalRequired) authorizeRemoteSensitive(
            SensitiveOperation.APK_INSTALL,
            "companion_auto_update\u0000enable",
            "Allow automatic Home Assistant Companion updates",
        )
        config.setCompanionAutoUpdate(on)
        stateConverger.reconcile("companion_auto_update", force = true)
    }

    override fun handleSelfUpdate(payload: String, approvalRequired: Boolean) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        if (on && approvalRequired) authorizeRemoteSensitive(
            SensitiveOperation.APK_INSTALL,
            "self_update\u0000enable",
            "Allow automatic ha-paneld updates",
        )
        config.setSelfUpdate(on)
        stateConverger.reconcile("self_update", force = true)
    }

    override fun handleWebViewAuto(payload: String, approvalRequired: Boolean) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        if (on && approvalRequired) authorizeRemoteSensitive(
            SensitiveOperation.APK_INSTALL,
            "webview_auto_update\u0000enable",
            "Allow automatic System WebView updates",
        )
        config.setWebViewAutoUpdate(on)
        stateConverger.reconcile("webview_auto_update", force = true)
    }

    /** The camera master switch, commanded from Home Assistant. Enabling arms the trial and nothing more:
     *  Android still withholds the camera until somebody grants the permission at the panel, and every
     *  enable direction waits for a local approval first, so no remote message alone can put this panel's
     *  camera into service. Disabling ends a live session immediately. */
    private fun handleCameraEnabled(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        requireCameraEnableAdmission(on, hasCamera()) {
            authorizeRemoteSensitive(
                SensitiveOperation.CAMERA_ENABLE,
                "camera_enabled\u0000enable",
                "Serve this panel's camera to Home Assistant",
                always = true,
            )
        }
        config.setCameraEnabled(on)
        // The same actuation the HTTP reconfigure path performs, so both routes converge on one owner.
        onCameraEnabledChanged()
        stateConverger.reconcile("camera_enabled", force = true)
        publishCameraSnapshot(refreshUrl = on)
    }

    /** Availability for the snapshot image entity, and — on a fresh enable only — the URL republish that
     *  makes Home Assistant drop its cached frame. The URL topic never carries an empty payload: HA
     *  validates every message on it as a URL and logs an error for anything else, so "the camera is off"
     *  is said on the availability topic instead of by clearing the URL. Nothing here is periodic; the
     *  camera opens when a person looks at the card, never because the panel published. */
    private fun publishCameraSnapshot(refreshUrl: Boolean) {
        cameraSnapshotPublications(
            panel = panel,
            announced = cameraSnapshotAnnounced,
            enabled = config.cameraEnabled,
            url = cameraSnapshotUrl(),
            refreshUrl = refreshUrl,
        ).forEach { publish(it.topic, it.payload, retain = true) }
    }

    /** Republish the camera surface after a change made outside MQTT (the Configure page, a bundle
     *  import, provisioning). The service calls this from the same reconfigure fan-out that notifies the
     *  camera owner, so Home Assistant never keeps a switch position the panel has already left. */
    fun publishCameraState() {
        dispatchStateWork {
            stateConverger.reconcile("camera_enabled", force = true)
            publishCameraSnapshot(refreshUrl = config.cameraEnabled)
        }
    }

    override fun handleUpdateChannel(
        payload: String,
        previousValue: String?,
        approvalRequired: Boolean,
    ) {
        val was = previousValue ?: config.updateChannel
        val requested = normalizeSelfUpdateChannel(payload)
        if (approvalRequired && config.selfUpdate && requested != was) authorizeRemoteSensitive(
            SensitiveOperation.APK_INSTALL,
            "update_channel\u0000$requested",
            "Change the ha-paneld update channel and check for an update",
        )
        stageSelfUpdateChannelChange(
            current = was,
            requested = requested,
            selfUpdateEnabled = config.selfUpdate,
            requestAdmittedInstall = onSelfUpdateChannelChange,
            persist = config::setUpdateChannel,
            publishCurrent = { stateConverger.reconcile("update_channel", force = true) },
        )
    }

    override fun handleCompanionChannel(
        payload: String,
        previousValue: String?,
        approvalRequired: Boolean,
    ) {
        val was = previousValue ?: config.companionUpdateChannel
        val requested = payload.trim().trim('"')
        if (approvalRequired && config.companionAutoUpdate && requested != was) authorizeRemoteSensitive(
            SensitiveOperation.APK_INSTALL,
            "companion_update_channel\u0000$requested",
            "Change the Companion update channel and check for an update",
        )
        config.setCompanionUpdateChannel(requested)
        val now = config.companionUpdateChannel
        stateConverger.reconcile("companion_update_channel", force = true)
        // Apply the new channel now when auto-update is on (a forced check via the existing callback).
        if (config.companionAutoUpdate && now != was) onUpdateCompanion()
    }

    // HA select uses the capitalised labels; Config stores "stable"/"prerelease".
    private fun updateChannelLabel(): String = if (config.updateChannel == "prerelease") "Pre-release" else "Stable"
    private fun companionChannelLabel(): String = if (config.companionUpdateChannel == "prerelease") "Pre-release" else "Stable"

    override fun handleSilenceBootChime(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        requireControlApplied("silence_boot_chime", bootChime.apply(on)) { "boot chime transition failed" }
        stateConverger.reconcile("silence_boot_chime", force = true)
    }

    override fun handleAutoBright(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        config.setAutoBrightness(on)
        autoBright.reapplyLatest()
        onAutoBrightnessConfigChanged()
        stateConverger.reconcile("auto_brightness", force = true)
    }

    override fun handleAutoBrightnessSensitivity(payload: String) {
        val value = payload.trim().trim('"').toIntOrNull() ?: return
        config.setAutoBrightnessResponsePercent(value)
        autoBright.reapplyLatest()
    }

    override fun handleAutoBrightnessMinimum(payload: String) {
        val value = payload.trim().trim('"').toIntOrNull() ?: return
        config.setAutoBrightnessMinimumPercent(value)
        autoBright.reapplyLatest()
    }

    override fun handleAutoBrightnessHaEntity(payload: String) {
        config.setAutoBrightnessHaEntity(payload.trim().trim('"'))
        onAutoBrightnessConfigChanged()
        autoBright.reapplyLatest()
    }

    // Button backlight (e.g. TPA10): a brightness-only light, driven via the root daemon's BTN command
    // (same daemon that owns the sysfs LED). Daemon calls are short blocking I/O — fine on this thread.
    private fun handleButtons(payload: String) {
        val json = JSONObject(payload)
        val on = json.optString("state", "ON").equals("ON", ignoreCase = true)
        val level = if (!on) 0 else if (json.has("brightness")) json.getInt("brightness") else 255
        if (HelperClient.send("BTN $level") == "OK") {
            config.lastButtonBacklight = level
            stateConverger.reconcile("buttons", force = true)
        }
    }

    // Zigbee router toggle (Sonoff NSPanel Pro). ON starts the guard supervisor (→ mosquitto +
    // zgateway, ensures Repeater role); OFF stops the guard + zstack, freeing the radio.
    //
    // The vendor lifecycle is slow — OFF blocks ~8s in the stop script, and ON's gateway spawns on the
    // guard's ~30s timer (tens of seconds before it answers) — so it must NOT run on the MQTT callback
    // thread. We publish the commanded state optimistically, then reconcile to the real running state
    // on a background thread (polling for the slow ON) so HA ends up correct without stalling MQTT.
    override fun handleZigbee(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        if (on) onZigbeeExplicitRetry()
        config.setZigbeeRouterEnabled(on) // persist desired state so it survives a reboot (boot-restore)
        admitZigbee(on)
    }

    /** User-initiated join retry from the local HTML UI. The persisted switch must already be ON;
     *  this reasserts Repeater mode through the same serialized actuator without spawning another guard. */
    fun requestZigbeeJoin(): Boolean {
        if (!lifecycle.isOpen() || !config.zigbeeRouterConfigured || !config.zigbeeRouterEnabled) return false
        onZigbeeExplicitRetry()
        return admitZigbee(true) != LatestDispatcher.Admission.CLOSED
    }

    fun publishZigbeeRouterState() {
        lifecycle.runIfOpen(Unit) { stateConverger.reconcile("zigbee_router", force = true) }
    }

    fun publishZigbeeHealth(snapshot: ZigbeeHealthSnapshot? = null) {
        lifecycle.runIfOpen(Unit) {
            if (state != "connected") return@runIfOpen
            val current = snapshot ?: zigbeeHealth()
            publish(stateZigbeeHealth, current.state.wireValue, retain = true)
            publish(attrZigbeeHealth, current.mqttAttributes(), retain = true)
        }
    }

    /** Re-project the shared authority after a startup/daily observation or immediate database failure. */
    fun publishStorageHealth() {
        lifecycle.runIfOpen(Unit) {
            if (state != "connected") return@runIfOpen
            stateConverger.reconcile("storage_health", force = true)
            stateConverger.reconcile("storage_health_attributes", force = true)
        }
    }

    // Boot/connect RECONCILE for the Zigbee router. Vendor firmware boot-starts the NSPanel Pro gateway
    // independently of us (and on 120P/3.7.1 the vendor guard CPU-spins), so we drive it to the user's
    // explicit choice on every connect: start it if they left it ON and nothing has; STOP it if they
    // turned it OFF (otherwise the vendor-started gateway returns each reboot). Gated on the switch having
    // been CONFIGURED — we never disable a stock vendor gateway by our default. Slow lifecycle off-thread.
    private fun reconcileZigbeeOnConnect() {
        if (!capabilityShape.current().live.zigbee || !config.zigbeeRouterConfigured) return
        val want = config.zigbeeRouterEnabled
        if (want == zigbee.running()) return // already in the desired state
        admitZigbee(want)
    }

    private fun admitZigbee(desired: Boolean): LatestDispatcher.Admission {
        val admission = zigbeeWorker.submit(desired)
        when (admission) {
            LatestDispatcher.Admission.COALESCED ->
                FeatureCosts.registry.recordCoalesced(FeatureCostOperation.ZIGBEE_RECONCILE)
            LatestDispatcher.Admission.REJECTED, // unreachable for a single slot
            LatestDispatcher.Admission.CLOSED ->
                FeatureCosts.registry.recordDropped(FeatureCostOperation.ZIGBEE_RECONCILE)
            LatestDispatcher.Admission.ACCEPTED -> Unit
        }
        FeatureCosts.registry.setBacklog(FeatureCostOperation.ZIGBEE_RECONCILE, zigbeeWorker.pendingCount())
        return admission
    }

    private fun reconcileZigbeeDesired(desired: Boolean) {
        FeatureCosts.registry.setBacklog(FeatureCostOperation.ZIGBEE_RECONCILE, 0)
        val cost = FeatureCosts.registry.span(FeatureCostOperation.ZIGBEE_RECONCILE).work(units = 1)
        try {
            val execution = zigbeeActuation.executeIfCurrent(zigbeeLease) {
                val reconciled = zigbee.reconcile(desired)
                if (desired) for (i in 0 until 18) {
                    // A newer bridge generation or command is already authoritative. Stop waiting and
                    // release the shared actuator so its latest desired state can run after this one.
                    if (!lifecycle.isOpen() || !zigbeeActuation.isCurrent(zigbeeLease) ||
                        config.zigbeeRouterEnabled != desired || zigbee.running()
                    ) break
                    Thread.sleep(5_000)
                }
                reconciled to zigbee.running()
            }
            if (!execution.executed || !execution.currentAfter) {
                cost.outcome(FeatureCostOutcome.CANCELLED)
                return
            }
            val (reconciled, running) = requireNotNull(execution.value)
            if (!reconciled) cost.outcome(FeatureCostOutcome.FAILURE)
            if (lifecycle.isOpen()) stateConverger.reconcile("zigbee_router", force = true)
            Log.i(TAG, "zigbee reconcile -> ${if (desired) "on" else "off"}; running=$running")
        } catch (e: InterruptedException) {
            cost.outcome(FeatureCostOutcome.CANCELLED)
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            Log.w(TAG, "zigbee reconcile failed", e)
        } finally {
            cost.close()
        }
    }

    // On-board relay (Smatek S9E). topic = ha-paneld/<panel>/relay<N>/set; payload ON/OFF.
    // The write runs here on the ordered command worker; the physical read-back goes through the
    // generation-guarded gate so bursts coalesce and a late read-back never publishes a superseded state.
    private fun handleRelay(topic: String, payload: String) {
        val n = topic.substringAfter("/relay").substringBefore("/set").toIntOrNull() ?: return
        val on = payload.trim().let { it.equals("ON", ignoreCase = true) || it == "1" }
        if (!relayReadbackGate.command("relay$n") { relay.set(n, on) }) {
            Log.w(TAG, "relay$n write failed; read-back will publish the physical state")
        }
    }

    // S9E button LED. topic = ha-paneld/<panel>/button_led<N>/set (N 1-based); payload ON/OFF.
    private fun handleButtonLed(topic: String, payload: String) {
        val n = topic.substringAfter("/button_led").substringBefore("/set").toIntOrNull() ?: return
        val on = payload.trim().let { it.equals("ON", ignoreCase = true) || it == "1" }
        if (!relayReadbackGate.command("button_led$n") { relay.ledSet(n - 1, on) }) {
            Log.w(TAG, "button_led$n write failed; read-back will publish the physical state")
        }
    }

    // CPU scaling governor (select). Quick su write; publishes the read-back governor.
    override fun handleCpuGov(payload: String) {
        val tier = payload.trim().trim('"')   // "Performance" | "Efficiency" | "Auto"
        check(cpu.setTier(tier)) { "CPU governor transition failed" }
        stateConverger.reconcile("cpu_governor", force = true)
    }

    // Persistent network adb (switch). Restarts adbd to apply; that only affects adb, not MQTT.
    override fun handleNetAdb(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        if (on && config.hardenedSecurityEnabled) {
            throw HardenedModeRefusalException("network ADB cannot be enabled while Hardened mode is active")
        }
        check(adb.set(on)) { "network adb transition failed" }
        stateConverger.reconcile("network_adb", force = true)
    }

    /** Commands from Home Assistant have no HTTP peer to bind. Keep their one-shot panel approval under
     * the fixed principal of the transport that delivered them; service-side replays of already-approved
     * HTTP writes bypass this helper. */
    private fun authorizeRemoteSensitive(
        operation: SensitiveOperation,
        payload: String,
        summary: String,
        always: Boolean = false,
    ) {
        if (!always && !config.hardenedSecurityEnabled) return
        val peer = commandPeer.get() ?: MQTT_PEER
        val (decision, approvalId) = LocalApprovalBroker.instance.request(operation, peer, payload, summary)
        if (decision != ApprovalBroker.Decision.APPROVED) {
            throw SensitiveApprovalPendingException(
                approvalId,
                "approval required on the panel before ${operation.label.lowercase()}",
            )
        }
    }

    // Soft navbar mode (select). Persist and publish only after the asynchronous platform actuation
    // acknowledges both privileged display state and the main-thread overlay mutation.
    override fun handleNavbar(payload: String) {
        val previous = config.navbarMode
        // Unlike HTTP, this path coerces rather than rejects — NavbarController.normalise maps anything
        // unrecognised onto Off and the result is then persisted. "Native" IS recognised, so without this
        // guard a command aimed at the wrong panel would take away its drawn bar and leave no navigation
        // at all. Keep the panel where it is and let the reconcile republish the canonical value, which
        // snaps the Home Assistant select back.
        if (!navbarModePermitted(payload.trim().trim('"'), config.hasNativeNavbar)) {
            Log.w(TAG, "navbar: refusing $payload — this panel has no native navigation bar")
            stateConverger.reconcile("navbar", force = true)
            return
        }
        applyAcknowledgedNavbarMode(
            payload = payload,
            previousMode = previous,
            actuate = navbar::applyAsync,
            rollback = navbar::rollbackAsync,
            persist = { applied ->
                config.commitRaw(requireNotNull(SettingsRegistry.spec("navbar_mode")), applied)
            },
            reconcile = { stateConverger.reconcile("navbar", force = true) },
        )
    }

    override fun handleHomeDashboard(payload: String, previousValue: String?) {
        // One authority decides what a home dashboard may be: the setting's own validator, which the
        // config API and both pickers already use. This function serves two callers with different
        // histories — an MQTT command arrives unvalidated, live apply arrives already canonical — and the
        // former URL-to-local-path conversion was wrong for both. It let MQTT retain a route the API
        // would have refused, and it rewrote an accepted route whose query or fragment merely CONTAINED
        // "://" into a different dashboard. Neither caller wants a second, weaker opinion here.
        val spec = SettingsRegistry.spec("home_dashboard")
        val target = when (val validated = spec?.let { SettingValue.validate(it, payload) }) {
            is Validation.Ok -> validated.normalized
            else -> {
                // Refuse rather than coerce, then republish so the entity cannot go on showing a value
                // the panel did not accept. The route itself is not logged; it names the owner's rooms.
                Log.w(TAG, "home_dashboard command refused: not a well-formed dashboard route")
                stateConverger.reconcile("home_dashboard", force = true)
                return
            }
        }
        val changed = target != (previousValue ?: config.homeDashboard)
        config.setHomeDashboard(target)
        if (changed) onDashboardTargetChanged()
        stateConverger.reconcile("home_dashboard", force = true)
    }

    /** Discovery embeds suggested_area on its next publish; the server owns HA registry write-back. */
    override fun handleHaAreaPublishOnly() = Unit

    // Reload: keep the hard restart (the right recovery for a wedged WebView), but if a per-panel home
    // dashboard is set, deep-link back to it once the frontend has cold-started — so reload lands on THIS
    // panel's dashboard, not the Companion's user-default. The delayed nav runs off the MQTT thread.
    private fun handleReload() {
        // Built-in renderer: reload returns to the configured home dashboard (clear any navigate path),
        // and the WebView reloads its own view — no Companion deep-link re-navigation is needed.
        if (config.dashboardPackage.isBlank() || config.dashboardPackage == SystemController.BUILTIN_DASHBOARD) {
            BuiltinDashboard.navPath = null
            system.reloadDashboard(config.dashboardPackage)
            return
        }
        system.reloadDashboard(config.dashboardPackage)
        // Already canonical by construction, so it is the local path to deep-link as stored. Re-running
        // it through scheme stripping would corrupt a legal route whose query happens to contain "://".
        val home = config.homeDashboard
        if (home.isNotBlank() && home != "/") {
            reloadNavigationFuture?.cancel(false)
            reloadNavigationFuture = try {
                authScheduler.schedule({
                    recordCommandAdmission(commandDispatcher.submitAction {
                        navigate.navigate("homeassistant://navigate$home")
                        config.lastNavigate = home
                        stateConverger.reconcile("navigate", force = true)
                        Log.i(TAG, "reload -> re-navigated to intended dashboard $home")
                    })
                }, RELOAD_NAV_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                null
            }
        }
    }

    private fun handleNavigate(payload: String) {
        // Local navigation only: strip any scheme + host so an external URL can't be pushed (the HA
        // Companion opens a disorienting in-app WebView for those). We keep just the path.
        val path = toLocalPath(payload)
        if (path.isEmpty()) return
        if (config.dashboardPackage.isBlank() || config.dashboardPackage == SystemController.BUILTIN_DASHBOARD) {
            // Built-in renderer: set the target path and (re)launch DashboardActivity, which loads it —
            // the deep link targets the Companion and would no-op on a builtin-only panel.
            BuiltinDashboard.navPath = path
            system.launchHome(config.dashboardPackage)
            config.lastNavigate = path
            stateConverger.reconcile("navigate", force = true)
            return
        }
        if (path == config.lastNavigate) {
            // Already on this path — the deeplink is a no-op, so reload the dashboard instead.
            system.reloadDashboard(config.dashboardPackage)
        } else {
            navigate.navigate("homeassistant://navigate$path")
            config.lastNavigate = path
            stateConverger.reconcile("navigate", force = true)
        }
    }

    /** Reduce any posted value to a leading-slash local path: drop `scheme://` and the `host:port`
     *  authority, keep the path (+ query/fragment). `http://ha.local:8123/lovelace/0` → `/lovelace/0`;
     *  `lovelace/0` → `/lovelace/0`; `/lovelace/0` unchanged. */
    private fun toLocalPath(raw: String): String {
        var s = raw.trim().trim('"')
        if (s.isEmpty()) return ""
        val scheme = s.indexOf("://")
        if (scheme >= 0) {
            s = s.substring(scheme + 3)            // strip scheme://
            val slash = s.indexOf('/')             // drop the host[:port] authority
            s = if (slash >= 0) s.substring(slash) else "/"
        }
        if (!s.startsWith("/")) s = "/$s"
        return s
    }

    private fun handleVolume(payload: String) {
        // number entity sends a plain numeric string (0..100).
        val pct = payload.trim().trim('"').toDoubleOrNull()?.toInt() ?: return
        volume.setPercent(pct)
        volume.getPercent().let { lastPublishedVolume = it }
        stateConverger.reconcile("volume", force = true)
    }

    private fun publishButton(event: String) {
        lifecycle.runIfOpen(Unit) { publish(eventButton, """{"event_type":"$event"}""") }
    }

    // Sensor readings are NOT retained: a fresh sample arrives shortly, so retaining only adds broker
    // clutter + a brief stale value on reconnect. (Occupancy/proximity IS retained — it has no periodic
    // sample between transitions, so the last state must survive a reconnect. Stateful controls too.)
    fun publishLight(lux: Int) {
        lifecycle.runIfOpen(Unit) {
            lastIlluminance = lux
            stateConverger.reconcile("illuminance")
        }
    }

    /** Publish fleet-normalized proximity. Null means the learner cannot currently make a trustworthy
     * statement; entity-specific availability is taken offline and retained guessed values are cleared. */
    fun publishProximity(
        near: Boolean?,
        normalizedLevel: Int?,
        reportMask: Int = ProximityReportGate.BOTH,
    ) {
        val nextNear = near
        lifecycle.runIfOpen(Unit) publish@{
            proximityPublication.serialized proximity@{
                if (!learnedProximityEligible()) {
                    clearProximityStateLocked()
                    return@proximity
                }
                val level = normalizedLevel?.coerceIn(0, 100)
                val available = nextNear != null && level != null
                val admitted = admit(nextNear, level, reportMask)
                val availabilityChanged = admitted and ProximityPublicationState.AVAILABILITY_CHANGED != 0
                if (availabilityChanged) {
                    publish(proximityAvailabilityTopic, if (available) "online" else "offline", retain = true)
                }
                if (!available) {
                    if (availabilityChanged) {
                        publish(stateProximity, "", retain = true)
                        publish(stateProximityLevel, "", retain = true)
                    }
                    return@proximity
                }
                if (admitted and ProximityReportGate.PRESENCE != 0) {
                    stateConverger.reconcile("proximity", force = availabilityChanged)
                }
                if (admitted and ProximityReportGate.LEVEL != 0) {
                    stateConverger.reconcile("proximity_level", force = availabilityChanged)
                }
            }
        }
    }

    /** Apply an empirical mode-change notification without accepting truth from its producer. */
    /** Re-announce discovery when the light sensor's real availability changes, so an illuminance
     *  entity advertised optimistically at startup is withdrawn once activation is known to have failed. */
    internal fun notifyLightAvailabilityChanged() {
        lifecycle.runIfOpen(Unit) {
            discoveryCapabilities.invalidate()
            requestReAnnounce()
        }
    }

    internal fun notifyLearnedProximityChanged() {
        lifecycle.runIfOpen(Unit) {
            discoveryCapabilities.invalidate()
            refreshLearnedProximityFromAuthority(
                admitted = true,
                eligible = learnedProximityEligibility,
                clearIneligibleState = {
                    clearProximityState(force = true)
                },
                reannounce = ::requestReAnnounce,
            )
        }
    }

    private fun learnedProximityEligible(): Boolean =
        runCatching(learnedProximityEligibility).getOrDefault(false)

    private fun clearProximityState(force: Boolean = false) {
        proximityPublication.serialized { clearProximityStateLocked(force) }
    }

    private fun ProximityPublicationState.clearProximityStateLocked(force: Boolean = false) {
        val hadState = clear()
        if (force || hadState) {
            publish(proximityAvailabilityTopic, "offline", retain = true)
            publish(stateProximity, "", retain = true)
            publish(stateProximityLevel, "", retain = true)
        }
    }

    // Rounded at publish (1dp temp, integer humidity) so precision wobble can't create recorder rows.
    fun publishTemperature(celsius: Float) {
        lifecycle.runIfOpen(Unit) {
            lastTemperature = celsius
            stateConverger.reconcile("temperature")
        }
    }

    fun publishHumidity(percent: Float) {
        lifecycle.runIfOpen(Unit) {
            lastHumidity = percent
            stateConverger.reconcile("humidity")
        }
    }

    /** Re-sample and publish the policy entity after a decision, health, source or suppression change. */
    fun publishAutoSleepActivity() {
        dispatchStateWork(::publishAutoSleepActivitySnapshot)
    }

    private fun publishAutoSleepActivitySnapshot() {
        val exposed = config.haExposed("auto_sleep_activity", true)
        val snapshot = if (!exposed) AutoSleepActivitySnapshot() else runCatching(autoSleepActivity)
            .onFailure { Log.w(TAG, "auto-sleep policy snapshot failed; publishing unavailable", it) }
            .getOrDefault(AutoSleepActivitySnapshot())
        autoSleepMqttPublications(panel, exposed, snapshot).forEach { publication ->
            publish(publication.topic, publication.payload, retain = publication.retain)
        }
    }

    /**
     * Apply a setting from a NON-MQTT source (the HTTP `/api/v1/config` API) through the SAME
     * side-effect + state-publish path an MQTT command takes, so a value set over HTTP behaves
     * identically to one set from Home Assistant (persist → drive hardware → publish retained state).
     * [value] is the registry-normalized string ("true"/"false", a number, or an enum label).
     * Reports whether the setting applied, was not admitted because this bridge is draining, or failed
     * after admission. Only the admission failure is safe for the service to acknowledge as deferred.
     */
    internal fun applySetting(key: String, value: String, previousValue: String? = null): LiveSettingApplyResult {
        return applySettingObserved(key, value, previousValue).initial
    }

    internal fun applySettingObserved(
        key: String,
        value: String,
        previousValue: String? = null,
    ): LiveSettingApplication {
        if (key !in APPLY_SETTING_KEYS) return LiveSettingApplication.immediate(LiveSettingApplyResult.FAILED)
        if (!lifecycle.isOpen()) return LiveSettingApplication.immediate(LiveSettingApplyResult.DEFERRED)
        var started = false
        // Written on the dispatcher thread by the handler below, read by this caller and by the late
        // completion observer, so it must be published across both.
        val unavailable = java.util.concurrent.atomic.AtomicBoolean(false)
        val result = commandDispatcher.runLatestResult(
            key = "http:$key",
            onAdmission = ::recordCommandAdmission,
        ) {
            started = true
            val cost = FeatureCosts.registry.span(FeatureCostOperation.MQTT_COMMAND_DISPATCH)
                .work(units = 1, bytes = value.toByteArray().size.toLong())
            try {
                // This path is used only by the HTTP/service live-setting authority. Its owning HTTP
                // request was already approved against the real peer before it became durable or queued.
                dispatchLiveSetting(
                    key = key,
                    value = value,
                    previousValue = previousValue,
                    sensitiveApprovalRequired = false,
                    handlers = this,
                )
            } catch (e: LiveSettingUnavailableException) {
                unavailable.set(true)
                cost.outcome(FeatureCostOutcome.FAILURE)
                throw e
            } catch (e: Exception) {
                cost.outcome(FeatureCostOutcome.FAILURE)
                throw e
            } finally {
                cost.close()
            }
        }
        if (!result.executed && !started &&
            (result.admission == MqttCommandDispatcher.Admission.REJECTED ||
                result.admission == MqttCommandDispatcher.Admission.CLOSED)
        ) {
            FeatureCosts.registry.span(FeatureCostOperation.MQTT_COMMAND_DISPATCH)
                .outcome(FeatureCostOutcome.REJECTED)
                .work(bytes = value.toByteArray().size.toLong())
                .close()
        }
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.MQTT_COMMAND_DISPATCH,
            commandDispatcher.pendingCount(),
        )
        return liveSettingApplication(result, unavailable::get)
    }

    /** Publish the already-durable auto-sleep value without running its mutating command handler.
     * The generation check happens inside the dispatcher lane, after any concurrent direct MQTT
     * command, so stale queued OFF work can never overwrite a newer ON. */
    internal fun convergeAutoSleep(expectedGeneration: Long, expectedValue: Boolean): LiveSettingApplyResult {
        if (!lifecycle.isOpen()) return LiveSettingApplyResult.DEFERRED
        val result = commandDispatcher.runLatestResult(
            key = "http:auto_sleep",
            onAdmission = ::recordCommandAdmission,
        ) {
            if (config.autoSleepGeneration == expectedGeneration && config.autoSleep == expectedValue) {
                stateConverger.reconcile("auto_sleep", force = true)
            }
        }
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.MQTT_COMMAND_DISPATCH,
            commandDispatcher.pendingCount(),
        )
        return liveSettingApplyResult(result)
    }

    // ---- discovery ----

    /**
     * Publish — or, when the user has hidden it via the per-panel "expose to HA" toggle, CLEAR — one
     * discovery entity. Hiding publishes an empty payload to the config topic, which removes the
     * entity from HA entirely (zero recorder / state-machine cost); with the 0.8.5 un-retained
     * discovery model nothing on the broker can resurrect it, and [reAnnounce] re-evaluates this gate
     * on every HA birth. [publishState] runs only when the entity is exposed. This is what makes the
     * HA footprint configurable per panel.
     */
    private fun exposable(
        key: String,
        component: String,
        objectId: String,
        payload: () -> String,
        capabilitySnapshot: Capabilities?,
        availableOverride: Boolean? = null,
        publishState: () -> Unit,
    ) {
        // Honour the spec's per-setting default so a setting declared haExposedByDefault=false is
        // local-only (HTTP UI) until the user opts in via the expose pip — matching what the Configure
        // UI/schema shows. Falls back to true for keys with no registry spec (e.g. relay/button_led).
        // An UNAVAILABLE setting (availableWhen false — e.g. Companion auto-update with no Companion
        // installed) publishes the empty config, removing any stale HA entity, regardless of the pip.
        val spec = SettingsRegistry.spec(key)
        val default = spec?.haExposedByDefault ?: true
        val available = availableOverride
            ?: capabilitySnapshot?.let { spec?.availableWhen?.invoke(it) }
            ?: true
        if (available && config.haExposed(key, default)) {
            publishConfig(component, objectId, payload())
            publishState()
        } else {
            publishConfig(component, objectId, "")
            // Read-only sensor states are retained unless explicitly declared otherwise. Removing only
            // discovery would leave sensitive values such as an SSID stored on the broker after opt-out.
            if (key == "auto_sleep_activity") {
                autoSleepMqttPublications(panel, exposed = false, snapshot = AutoSleepActivitySnapshot())
                    .forEach { publication -> publish(publication.topic, publication.payload, retain = publication.retain) }
            } else hiddenReadOnlyStateTopic(key, panel)?.let { stateTopic ->
                if (key in WIFI_DIAGNOSTIC_KEYS) {
                    // The converger serializes this clear behind any older in-flight value for the same
                    // topic, so a delayed SSID/RSSI acknowledgement cannot win after the tombstone.
                    stateConverger.reconcile(key, force = true)
                } else {
                    publish(stateTopic, "", retain = true)
                }
            }
        }
    }

    private fun publishDiscovery(capabilitySnapshot: Capabilities?) {
        val channelShape = ensureCapabilityChannels(capabilitySnapshot).possible
        if (config.panelAssistantMqttDiscovery == PanelAssistantTransportProtocol.MQTT_DISCOVERY_WITHDRAW) {
            withdrawDiscovery()
            return
        }
        fun exposable(
            key: String,
            component: String,
            objectId: String,
            payload: () -> String,
            availableOverride: Boolean? = null,
            publishState: () -> Unit,
        ) = this@MqttBridge.exposable(
            key, component, objectId, payload, capabilitySnapshot, availableOverride, publishState,
        )

        // device.name = the configurable friendly name; entity names are the capability ONLY, so HA
        // composes a clean `<domain>.<panel>_<cap>` entity_id without doubling the panel id.
        // configuration_url -> HA renders a "Visit" link on the device page (the panel's info UI).
        val currentConfigUrl = configUrl()?.takeIf(String::isNotBlank)
        lastPublishedConfigUrl = currentConfigUrl
        val cu = currentConfigUrl?.let { ""","configuration_url":"${jsonEsc(it)}"""" } ?: ""
        val name = jsonEsc(runtimeFriendlyName)
        val mfr = jsonEsc(config.manufacturer)
        val mdl = jsonEsc(config.model)
        // hw_version = panel firmware/build; surfaces in HA's device-info section (sw_version is
        // ha-paneld's own version). serial_number = stable Android id.
        val hw = jsonEsc("Android ${Build.VERSION.RELEASE} · ${Build.DISPLAY}")
        // Two device identifiers: the panel_id one (historical primary — existing registrations match
        // on it) plus this installation's minted identity. HA merges a device on ANY matching
        // identifier, so a later panel_id change re-attaches to the SAME HA device instead of minting a
        // duplicate.
        //
        // The minted identity replaced `ha-paneld-aid-<ANDROID_ID>` (#155). Panels flashed from one
        // factory image share the Android 8.1 SSAID seed, so that identifier was identical across a
        // fleet and Home Assistant merged every such panel into a single device. Because HA UNIONS
        // identifiers and never removes one, a shared identifier published even once is permanent for
        // that device, and republishing it would re-merge those panels as soon as the merged device is
        // deleted — so it is published at most once more, by an installation that was already
        // registered under it, and then retired below.
        val legacyAid = if (config.legacyAidBridgePending) config.androidId else ""
        val identifiers = mqttDeviceIdentifiers(panel, config.deviceUid, legacyAid)
        val ids = identifiers.joinToString(",", "[", "]") { """"${jsonEsc(it)}"""" }
        // suggested_area applies only when HA first registers the device (it auto-creates the area and
        // never overrides a manual move) — exactly the semantics of the local ha_area REQUEST, whose
        // canonical source stays Home Assistant once the device exists.
        val sa = config.haArea.takeIf(String::isNotBlank)?.let { ""","suggested_area":"${jsonEsc(it)}"""" } ?: ""
        val softwareVersion = jsonEsc(mqttDeviceSoftwareVersion(Config.VERSION, BuildConfig.VERSION_CODE))
        val device = """"device":{"identifiers":$ids,"name":"$name","manufacturer":"$mfr","model":"$mdl","sw_version":"$softwareVersion","hw_version":"$hw","serial_number":"${config.androidId}"$sa$cu}"""
        val avail = """"availability_topic":"$availabilityTopic","payload_available":"online","payload_not_available":"offline""""
        val proximityAvail = """"availability":[{"topic":"$availabilityTopic","payload_available":"online","payload_not_available":"offline"},{"topic":"$proximityAvailabilityTopic","payload_available":"online","payload_not_available":"offline"}],"availability_mode":"all""""
        val cameraSnapshotAvail = dualAvailabilityFragment(availabilityTopic, cameraSnapshotAvailability)

        // Every HA entity backed by a SettingsRegistry descriptor derives its identity and discovery
        // payload from that ONE declaration (HaEntity.buildDiscoveryJson) — byte-identical to the
        // legacy hand-written JSON and pinned by DiscoveryParityTest. Runtime command/state handling
        // remains intentionally separate because it is controller-owned rather than presentation data.
        fun registryExposable(
            key: String,
            availability: String = avail,
            availableOverride: Boolean? = null,
            publishState: () -> Unit,
        ) {
            val spec = requireNotNull(SettingsRegistry.spec(key)) { "setting missing from registry: $key" }
            val entity = requireNotNull(spec.ha) { "HA entity missing from registry: $key" }
            // A spec with per-choice gates renders its {options} placeholder from the same capability
            // snapshot the Configure form uses, so HA never offers a choice the panel would reject.
            // Specs without gates carry no placeholder and keep their fixed payload bytes.
            // Fail closed when the snapshot is unavailable: an all-false Capabilities drops every gated
            // choice rather than offering one the panel may not honour, and always yields a valid array
            // so a missing snapshot can never emit `"options":,`.
            val optionsJson = spec.discoveryOptionsJson(capabilitySnapshot ?: Capabilities())
            exposable(
                key = key,
                component = entity.component,
                objectId = "${panel}_${entity.objectSuffix}",
                payload = { entity.buildDiscoveryJson(panel, availability, device, optionsJson = optionsJson) },
                capabilitySnapshot = capabilitySnapshot,
                availableOverride = availableOverride,
                publishState = publishState,
            )
        }

        registryExposable("screen") {
            stateConverger.reconcile("screen", force = true)
        }
        val autoSleepAvail = autoSleepAvailabilityFragment(availabilityTopic, autoSleepAvailabilityTopic)

        if (led.available()) {
            val modes = if (led.colorCapable()) """["rgb"]""" else """["brightness"]"""
            publishConfig(
                "light", "${panel}_led",
                """{"name":"LED","object_id":"${panel}_led","unique_id":"${panel}_led","schema":"json","brightness":true,"supported_color_modes":$modes,"effect":true,"effect_list":["none","strobe","blink","pulse"],"command_topic":"$cmdLed","state_topic":"$stateLed",$avail,$device}""",
            )
        }

        publishConfig(
            "text", "${panel}_navigate",
            """{"name":"Navigate","object_id":"${panel}_navigate","unique_id":"${panel}_navigate","command_topic":"$cmdNavigate","state_topic":"$stateNavigate","mode":"text","icon":"mdi:monitor-dashboard",$avail,$device}""",
        )

        // Per-panel intended "home" dashboard path (e.g. /lovelace/0) — reload re-navigates here once the
        // frontend is back up. Empty = keep the Companion default. Config category.
        publishConfig(
            "text", "${panel}_home_dashboard",
            """{"name":"Home dashboard","object_id":"${panel}_home_dashboard","unique_id":"${panel}_home_dashboard","command_topic":"$cmdHomeDashboard","state_topic":"$stateHomeDashboard","mode":"text","icon":"mdi:home-search","entity_category":"config",$avail,$device}""",
        )
        stateConverger.reconcile("home_dashboard", force = true)

        // The button event entity surfaces a11y key capture AND daemon-instrumented evdev buttons
        // (e.g. the WF1589T power key), so publish it whenever either source exists.
        if (buttonsEnabled || hasEvdevButtons) {
            val eventTypes = mqttButtonEventTypes(profileButtonEventTypes)
                .joinToString(",") { "\"${jsonEsc(it)}\"" }
            publishConfig(
                "event", "${panel}_button",
                """{"name":"Button","object_id":"${panel}_button","unique_id":"${panel}_button","state_topic":"$eventButton","event_types":[$eventTypes],$avail,$device}""",
            )
        }
        // TTS/announce playback volume (STREAM_MUSIC). HA has no MQTT media_player platform, so
        // volume is a number entity rather than a media_player slider.
        registryExposable("volume") {
            stateConverger.reconcile("volume", force = true)
        }

        // Panel sensors — exposed as data only; room sensors stay the occupancy/lux authority. Their
        // registry descriptors are shared with Configure/API availability and remain the sole schema.
        // No availableOverride: a static constructor snapshot cannot see a light sensor that failed
        // to activate after the bridge was built. The live capability snapshot is the shared answer.
        registryExposable("illuminance") {
            stateConverger.reconcile("illuminance", force = true)
        }
        val learnedProximity = capabilitySnapshot?.hasLearnedProximity == true
        registryExposable("proximity", proximityAvail, hardwareAvailability("proximity", learnedProximity)) {
            stateConverger.reconcile("proximity", force = true)
        }
        registryExposable("proximity_level", proximityAvail, hardwareAvailability("proximity_level", learnedProximity)) {
            stateConverger.reconcile("proximity_level", force = true)
        }
        registryExposable("auto_sleep_activity", autoSleepAvail, availableOverride = config.autoSleep) {
            publishAutoSleepActivitySnapshot()
        }
        // SensorManager climate. On CHT8305 panels (TPA10) these are suppressed in favour of the
        // daemon-read room_temp/room_humidity, so publish an EMPTY config (tombstone) instead of
        // skipping — a panel upgrading from a version that DID expose them then sheds the now-duplicate,
        // stale entity from HA (SensorManager never streams a value on that chip). Empty on a panel that
        // never had the sensor is a harmless no-op.
        registryExposable("temperature", availableOverride = hardwareAvailability("temperature", null)) {
            stateConverger.reconcile("temperature", force = true)
        }
        registryExposable("humidity", availableOverride = hardwareAvailability("humidity", null)) {
            stateConverger.reconcile("humidity", force = true)
        }
        if (hasButtonBacklight) {
            publishConfig(
                "light", "${panel}_buttons",
                """{"name":"Button backlight","object_id":"${panel}_buttons","unique_id":"${panel}_buttons","schema":"json","brightness":true,"supported_color_modes":["brightness"],"command_topic":"$cmdButtons","state_topic":"$stateButtons",$avail,$device}""",
            )
        }
        // Config switches/numbers — each honours its per-panel "expose to HA" toggle (hidden → the
        // retained discovery payload is cleared, so the entity leaves HA with zero recorder cost).
        // Registry-backed entities are built from SettingsRegistry (the single source of truth,
        // golden-tested for byte-parity with these payloads by DiscoveryParityTest).
        registryExposable("wake_on_wave") {
            stateConverger.reconcile("wake_on_wave", force = true)
        }
        registryExposable("auto_sleep") {
            stateConverger.reconcile("auto_sleep", force = true)
        }
        registryExposable("touch_sound") {
            stateConverger.reconcile("touch_sound", force = true)
        }
        // Voice assistant — the switch AND the state sensor both require hasMicrophone (spec.availableWhen),
        // so both tombstone together on a panel with no microphone. The wake-word/Assist runtime itself is
        // the voice-coordinator lane's; this bridge only persists the switch and republishes the phase the
        // coordinator's VoiceStateAuthority reports.
        registryExposable("voice_enabled") {
            stateConverger.reconcile("voice_enabled", force = true)
        }
        registryExposable("voice_state") {
            publishDiag("voice_state")
        }

        // HA Companion app auto-update — installs/updates the minimal Companion over root (the
        // only update path on these no-Play panels). Off by default; the button forces it on demand.
        registryExposable("companion_auto_update") {
            stateConverger.reconcile("companion_auto_update", force = true)
        }
        publishConfig(
            "button", "${panel}_update_companion",
            """{"name":"Update Companion app","object_id":"${panel}_update_companion","unique_id":"${panel}_update_companion","command_topic":"$cmdUpdateCompanion","icon":"mdi:home-assistant","entity_category":"config",$avail,$device}""",
        )

        // ha-paneld self-update — follows the update channel; installs a newer build of itself over root.
        // Off by default; the update_paneld button forces it on demand.
        registryExposable("companion_update_channel") {
            stateConverger.reconcile("companion_update_channel", force = true)
        }
        publishConfig(
            "button", "${panel}_update_paneld",
            """{"name":"Update ha-paneld","object_id":"${panel}_update_paneld","unique_id":"${panel}_update_paneld","command_topic":"$cmdUpdatePaneld","icon":"mdi:package-up","entity_category":"config",$avail,$device}""",
        )
        // Update entities for ha-paneld and the Companion app. The buttons above stay for one release
        // and share these command topics; see SoftwareUpdateEntities.
        softwareUpdateDiscovery = SoftwareUpdateDiscoveryContext(avail, device)
        reconcileSoftwareUpdates(announcing = true)
        // System WebView auto-update — advances to the profile's pinned build (webview-mirror) over root.
        // Auto-gated on webViewManaged (removed on Play-updated panels with no recommended pin).
        registryExposable("webview_auto_update") {
            stateConverger.reconcile("webview_auto_update", force = true)
        }

        registryExposable("kiosk_lock") {
            stateConverger.reconcile("kiosk_lock", force = true)
        }
        // Auto-brightness — optional on-panel adaptive engine. Sensitivity and an optional native HA
        // illuminance subscription are configured on the panel; HA retains only the enable switch.
        registryExposable("auto_brightness") {
            stateConverger.reconcile("auto_brightness", force = true)
        }
        // Camera trial (experimental, off by default, offered only where the profile declares a camera).
        // The switch is the master privacy stop: with it off the hardware does not open for anything, and
        // turning it on here arms nothing more — Android still withholds the camera until the permission
        // is granted at the panel.
        registryExposable("camera_enabled") {
            stateConverger.reconcile("camera_enabled", force = true)
        }
        // The snapshot as an `image` entity carrying a URL. Home Assistant stores the URL and fetches a
        // frame only when somebody looks at the card, so a still exists without the panel ever taking one
        // — and no frame is parked on the broker, which the camera contract forbids outright. Its own
        // availability topic carries the master switch, so an off camera reads `unavailable` instead of
        // showing a stale frame that would imply the panel is still watching.
        // The master switch is opt-in, so the image follows it rather than the hardware. Announcing on
        // capability alone published a permanently unavailable image beside no control able to arm it.
        cameraSnapshotAnnounced = capabilitySnapshot?.hasCamera == true &&
            config.haExposed("camera_enabled", false)
        // Published unconditionally: the empty payload is the retained tombstone that actually removes
        // the entity. Skipping the publish instead would leave an image announced earlier in this bridge
        // generation live in Home Assistant, because published topics accumulate within a generation and
        // stale pruning only runs when the discovery marker changes. `publishCameraSnapshot` below
        // clears the retained URL and drives availability offline, so discovery and state withdraw
        // together.
        publishConfig(
            "image", "${panel}_camera_snapshot",
            cameraSnapshotConfigPayload(cameraSnapshotAnnounced) {
                cameraSnapshotDiscoveryJson(panel, cameraSnapshotAvail, device)
            },
        )
        publishCameraSnapshot(refreshUrl = true)
        publishConfig(
            "sensor", "${panel}_storage_health",
            """{"name":"Storage health","object_id":"${panel}_storage_health","unique_id":"${panel}_storage_health","state_topic":"$stateStorageHealth","json_attributes_topic":"$attrStorageHealth","icon":"mdi:database-alert","entity_category":"diagnostic",$avail,$device}""",
        )
        stateConverger.reconcile("storage_health", force = true)
        stateConverger.reconcile("storage_health_attributes", force = true)
        // Zigbee router — only on panels with the Sonoff gateway package (NSPanel Pro). present()
        // costs a su exec; safe here because onConnected runs off the main thread.
        if (channelShape.zigbee) {
            publishConfig(
                "sensor", "${panel}_zigbee_gateway_health",
                """{"name":"Zigbee gateway health","object_id":"${panel}_zigbee_gateway_health","unique_id":"${panel}_zigbee_gateway_health","state_topic":"$stateZigbeeHealth","json_attributes_topic":"$attrZigbeeHealth","icon":"mdi:zigbee","entity_category":"diagnostic",$avail,$device}""",
            )
            publishZigbeeHealth()
        }

        // On-board relays (Smatek S9E `st_relay`). count() probes sysfs via su — off-main-thread here.
        for (n in 1..channelShape.relays) {
            publishConfig(
                "switch", "${panel}_relay$n",
                """{"name":"Relay $n","object_id":"${panel}_relay$n","unique_id":"${panel}_relay$n","command_topic":"ha-paneld/$panel/relay$n/set","state_topic":"ha-paneld/$panel/relay$n/state","icon":"mdi:electric-switch",$avail,$device}""",
            )
            stateConverger.reconcile("relay$n", force = true)
        }

        // S9E button LEDs (gpio147-150) — on/off lights, gated on the gpio nodes being present.
        for (n in 1..channelShape.buttonLeds) {
            publishConfig(
                "light", "${panel}_button_led$n",
                """{"name":"Button LED $n","object_id":"${panel}_button_led$n","unique_id":"${panel}_button_led$n","command_topic":"ha-paneld/$panel/button_led$n/set","state_topic":"ha-paneld/$panel/button_led$n/state","icon":"mdi:led-on",$avail,$device}""",
            )
            stateConverger.reconcile("button_led$n", force = true)
        }

        // CPU governor (select) — su panels with cpufreq. Three intent-based tiers (Performance /
        // Efficiency / Auto) rather than raw kernel governor names; CpuController maps each to this
        // SoC's governor (Auto = its dynamic governor — ramps up on interaction, idles low).
        if (channelShape.cpu) {
            registryExposable("cpu_governor", availableOverride = true) {
                cpu.currentTier()?.let { lastPublishedGovTier = it }
                stateConverger.reconcile("cpu_governor", force = true)
            }
        }

        // Soft navbar (select) — overlay Back/Home/Recents bar for panels whose firmware hides the
        // native navbar. Published on all panels; Off by default, the user opts a panel in. Drawing
        // needs SYSTEM_ALERT_WINDOW (root-granted by NavbarController); a no-op select otherwise.
        registryExposable("navbar_mode") {
            stateConverger.reconcile("navbar", force = true)
        }

        // Persistent network adb (switch) — opt-in; root panels only. Standing LAN adb port when ON.
        if (channelShape.networkAdb) {
            registryExposable("network_adb", availableOverride = true) {
                stateConverger.reconcile("network_adb", force = true)
            }
        }

        // Diagnostic sensors (read-only) — all OPT-IN (haExposedByDefault=false), so a panel is silent
        // in HA until a pip is enabled. Publish the current value on expose; syncLocalState() refreshes
        // them each heartbeat tick with a deadband. Boot time is constant, so it's published only here.
        for (key in DIAG_KEYS) {
            registryExposable(key) { publishDiag(key) }
        }

        // Room climate (CHT8305 panels only, e.g. TPA10) — real environmental sensors reported by
        // default from the Sensors card. Same publish machinery (deadbanded heartbeat refresh).
        if (hasCht8305) for (key in ROOM_KEYS) {
            registryExposable(key) { publishDiag(key) }
        }

        // Panel actions (root via su; graceful no-op without it).
        publishConfig(
            "button", "${panel}_reload",
            """{"name":"Reload dashboard","object_id":"${panel}_reload","unique_id":"${panel}_reload","command_topic":"$cmdReload","icon":"mdi:web-refresh",$avail,$device}""",
        )
        publishConfig(
            "button", "${panel}_reboot",
            """{"name":"Reboot","object_id":"${panel}_reboot","unique_id":"${panel}_reboot","command_topic":"$cmdReboot","device_class":"restart","icon":"mdi:restart",$avail,$device}""",
        )

        // Every config topic above carried the device block, so the legacy Android-id identifier has now
        // been published for the last time and Home Assistant has had its chance to union the minted
        // identity onto the existing device. Retire it here, at the end of a complete pass, rather than
        // where the block is built: a pass that threw part-way has not published the whole device.
        if (legacyAid.isNotBlank()) config.retireLegacyAidBridge()
    }

    private fun jsonEsc(s: String): String = Json.esc(s)

    // Discovery configs are published NON-retained. Entities still rebuild on an HA restart because we
    // re-announce on HA's `homeassistant/status` = online birth message (+ on our own every connect) —
    // but a deleted/renamed/decommissioned entity's config no longer lingers retained on the broker to
    // resurrect it. (State topics + the availability LWT stay retained.)
    // Every discovery config topic published THIS session — recorded as we publish, so teardown/prune act
    // on exactly what we announced (no hardcoded drift). Thread-safe: publishConfig runs on the MQTT
    // thread; prune/clear may run from reconfigure on another thread.
    private val publishedConfigTopics = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * The Panel Assistant integration holds this panel's entities natively, so every discovery topic this
     * panel has ever announced receives a retained empty payload instead of a config: Home Assistant removes
     * the MQTT entities and keeps them removed across reconnects and its own restarts, because this runs
     * wherever discovery would. State, availability and the command subscription are untouched; commands
     * are already refused under the native authority. The value is persisted, so an outage or an
     * uninstalled integration never brings the entities back; only a hello reply that releases them does.
     */
    private fun withdrawDiscovery() {
        lastPublishedConfigUrl = configUrl()?.takeIf(String::isNotBlank)
        // The update entities converge on the heartbeat only while an announcement described them.
        softwareUpdateDiscovery = null
        // The historical superset plus whatever this generation announced outside it.
        val topics = knownConfigTopics() + synchronized(publishedConfigTopics) { publishedConfigTopics.toSet() }
        topics.forEach { topic ->
            publishedConfigTopics.add(topic)
            publish(topic, "", retain = true)
        }
        Log.i(TAG, "MQTT discovery withdrawn: ${topics.size} tombstones for $panel")
    }

    /** The persisted discovery value changed under a live connection; the next connect reads it anyway. */
    internal fun refreshPanelAssistantDiscovery() = requestReAnnounce()

    private fun publishConfig(component: String, objectId: String, payload: String) {
        val topic = "homeassistant/$component/$objectId/config"
        publishedConfigTopics.add(topic)
        // Live configs are deliberately non-retained. Empty payloads are different: retaining the
        // tombstone clears any config an older retain=true release left on the broker, so a hidden or
        // unavailable entity cannot resurrect while this panel is offline.
        publish(topic, withDefaultEntityId(component, objectId, payload), retain = mqttDiscoveryRetain(payload))
    }

    /**
     * The historical SUPERSET of every entity ha-paneld has ever published for a panel — the tombstone
     * list. KEEP entities here even after they're removed from [publishDiscovery], so an upgrade can
     * actively clear a now-refactored-away entity (see [pruneStaleDiscovery]) instead of zombie-ing it.
     */
    private fun knownConfigTopics(): Set<String> = mqttKnownConfigTopics(panel)

    /**
     * Active upgrade migration: clear any KNOWN entity we did NOT publish this session — i.e. one a prior
     * previous runtime announced but this runtime refactored away (or no longer has as a capability) —
     * so it is removed from HA instead of lingering as a zombie. Called once after a core-version or
     * profile-revision change. Empty retained payload also clears configs an older, retain=true version
     * left on the broker.
     */
    private fun pruneStaleDiscovery(onComplete: (Boolean) -> Unit) {
        val published = synchronized(publishedConfigTopics) { publishedConfigTopics.toSet() }
        val stale = knownConfigTopics().filter { it !in published } + mqttRetiredStateTopics(panel)
        if (stale.isEmpty()) {
            onComplete(true)
            return
        }
        val remaining = java.util.concurrent.atomic.AtomicInteger(stale.size)
        val allAcknowledged = AtomicBoolean(true)
        stale.forEach { topic ->
            publish(topic, "", retain = true) { acknowledged ->
                if (!acknowledged) allAcknowledged.set(false)
                if (remaining.decrementAndGet() == 0) onComplete(allAcknowledged.get())
            }
        }
        Log.i(TAG, "discovery prune: submitted ${stale.size} refactored-away/absent entities for $panel")
    }

    private fun publish(
        topic: String,
        payload: String,
        retain: Boolean = false,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        if (!lifecycle.isOpen()) {
            onComplete?.invoke(false)
            return
        }
        // Attribute the ACK to the connection that submitted this publication. The transport filters
        // superseded clients, and this second generation check closes the detach-between-check-and-callback
        // race before broker progress reaches the watchdog.
        val publishedGeneration = connectionGeneration.currentOrNull()
        transport.publish(
            topic = topic,
            payload = payload.toByteArray(),
            retain = retain,
            onComplete = { acknowledged ->
                if (acknowledged && publishedGeneration != null &&
                    connectionGeneration.isCurrent(publishedGeneration)
                ) {
                    // HiveMQ completes this callback on its network event loop. The generation check is the
                    // complete admission proof needed by markOk; never wait there for the bridge mutation gate.
                    markOk(publishedGeneration)
                }
                onComplete?.invoke(acknowledged)
            },
            expectedConnection = announcementConnection.get(),
        )
    }

    private fun subscribe(
        topicFilter: String,
        onMessage: (topic: String, payload: ByteArray, retained: Boolean) -> Unit,
    ) {
        transport.subscribe(
            topicFilter = topicFilter,
            expectedConnection = announcementConnection.get(),
            onMessage = onMessage,
        )
    }

    private val localObservationPending = AtomicBoolean(false)

    /** One queued/running local pass per bridge, independent of broker heartbeat admission. */
    internal fun requestLocalObservation(stillCurrent: () -> Boolean) {
        if (!lifecycle.isOpen() || !localObservationPending.compareAndSet(false, true)) return
        io.github.maxlyth.hapaneld.mqtt.StateConverger.dispatch {
            try {
                lifecycle.runIfOpen(Unit) {
                    if (stillCurrent()) runCatching { syncLocalState() }
                }
            } finally {
                localObservationPending.set(false)
            }
        }
    }

    /**
     * Liveness probe: publish a monotonic-independent `last_seen_at` (epoch seconds) so a healthy link keeps
     * [lastOkMs] fresh even when nothing else is publishing, and a dead half-open link stops ACKing and
     * goes stale (→ watchdog reconnect). Non-retained (a stale retained heartbeat would be misleading).
     * Local observations have separate watchdog admission; this only probes the broker connection.
     */
    fun heartbeat() {
        if (!lifecycle.isOpen()) return
        if (state == "disabled" || state == "config-error") return
        // The transport probe remains sacrificial: if HiveMQ wedges, it owns no lifecycle mutation lock.
        runCatching { publish("ha-paneld/$panel/last_seen_at", (System.currentTimeMillis() / 1000).toString()) }
    }

    /**
     * Local-state observation: panel values can change OUTSIDE ha-paneld's API (auto-brightness and
     * any local app writing the setting, hardware volume keys, vendor firmware dimming the backlight
     * node), and HA must track them without being flooded. One pass per heartbeat tick per channel:
     * publish only when the value differs from the LAST PUBLISHED beyond the channel's deadband AND
     * has settled (change since the previous tick within the settle band) — fast oscillation
     * publishes nothing until it stops, a slow ramp publishes at most once per tick, steady state
     * publishes zero MQTT messages. Runs on the convergence pump (su-safe, off-main).
     */
    private fun syncLocalState() {
        runCatching {
            val capabilityObservation = ensureCapabilityChannels(
                discoveryCapabilities.snapshot(CAPABILITY_RECOVERY_PROBE_MS),
            )
            capabilityObservation.changedKeys().forEach {
                stateConverger.reconcile(it, force = true)
            }
            if (capabilityObservation.grew) requestReAnnounce()
            if (!capabilityObservation.previousLive.zigbee && capabilityObservation.live.zigbee) {
                reconcileZigbeeOnConnect()
            }
        }
        val observedOffGeneration = screen.currentOffGeneration()
        val physicallyDark = screen.observedDark()
        if (physicallyDark == true) screen.noteObservedDark(observedOffGeneration)
        val becameOff = physicallyDark == true && lastScreenBrightness >= 0
        if (becameOff) {
            Log.i(TAG, "screen became physically dark outside MQTT — syncing OFF")
            syncLog.record(SystemClock.elapsedRealtime(), "screen →OFF (physical)")
            publishScreenOff()
        } else if (physicallyDark == false && lastScreenBrightness < 0) {
            val reconciled = !screen.isIntendedOff() || screen.reconcileObservedLit(observedOffGeneration)
            if (reconciled) {
                val level = brightness.getCommanded().coerceAtLeast(1)
                Log.i(TAG, "screen became physically lit outside MQTT — syncing ON")
                syncLog.record(SystemClock.elapsedRealtime(), "screen →ON (physical)")
                publishScreenBrightness(level)
            }
        }

        // Channel: commanded brightness (Android setting — the scale HA commands in). Catches
        // auto-brightness and any local actor. Skipped while the screen is deliberately off.
        if (!becameOff && !screen.isIntendedOff() && lastScreenBrightness >= 0
        ) {
            val cur = brightness.getCommanded()
            if (settled(cur, prevTickBrightness, lastScreenBrightness, deadband = 3)) {
                Log.i(TAG, "local brightness changed outside MQTT: $lastScreenBrightness -> $cur — syncing")
                syncLog.record(SystemClock.elapsedRealtime(), "brightness $lastScreenBrightness→$cur")
                publishScreenBrightness(cur.coerceAtLeast(1))
            }
            prevTickBrightness = cur

            // Channel: effective backlight vs its post-command baseline — ONLY when the commanded
            // setting hasn't moved (else the channel above owns it). Catches firmware node-dims. The
            // node scale differs from the setting scale on curve-mapped panels, so drift is reported
            // back-mapped proportionally into the commanded scale.
            if (cur == lastScreenBrightness || kotlin.math.abs(cur - lastScreenBrightness) <= 3) {
                val eff = brightness.getBrightness()
                if (eff >= 0) {
                    val base = screenEffectiveBaseline
                    if (base < 0) {
                        screenEffectiveBaseline = eff   // command settled — remember its hardware level
                    } else if (kotlin.math.abs(eff - base) > SCREEN_DRIFT) {
                        val reported = (lastScreenBrightness.toLong() * eff / base.coerceAtLeast(1))
                            .toInt().coerceIn(1, 255)
                        Log.i(TAG, "screen backlight moved externally: baseline $base -> $eff — reporting $reported")
                        syncLog.record(SystemClock.elapsedRealtime(), "backlight →$reported (firmware dim)")
                        publishScreenBrightness(reported)
                        screenEffectiveBaseline = eff
                    }
                }
            }
        }

        // Channel: volume — hardware keys / local apps change it outside MQTT.
        runCatching {
            val cur = volume.getPercent()
            if (settled(cur, prevTickVolume, lastPublishedVolume, deadband = 1)) {
                Log.i(TAG, "local volume changed outside MQTT: $lastPublishedVolume -> $cur — syncing")
                syncLog.record(SystemClock.elapsedRealtime(), "volume $lastPublishedVolume→$cur")
                lastPublishedVolume = cur
                stateConverger.reconcile("volume", force = true)
            }
            prevTickVolume = cur
        }

        // Channel: CPU governor — a thermal daemon or another app can change the scaling governor;
        // currentTier() reads the LIVE sysfs governor, so publish when the mapped tier no longer matches
        // what HA last saw. Categorical (not numeric) — publish on any change vs the baseline, no deadband.
        // The baseline is set at announce / on command, so this only fires on a genuinely external change.
        runCatching {
            if (capabilityShape.current().live.cpu) {
                val tier = cpu.currentTier()
                if (tier != null && lastPublishedGovTier != null && tier != lastPublishedGovTier) {
                    Log.i(TAG, "cpu governor changed outside MQTT: $lastPublishedGovTier -> $tier — syncing")
                    syncLog.record(SystemClock.elapsedRealtime(), "cpu_governor $lastPublishedGovTier→$tier")
                    lastPublishedGovTier = tier
                    stateConverger.reconcile("cpu_governor", force = true)
                }
            }
        }

        // Diagnostic sensors — refresh each exposed one. Deadbands still publish meaningful changes;
        // selected measurement sensors also have a bounded maximum broker-ACK silence. Boot time is
        // constant and is therefore published only at exposure/reconnect.
        runCatching { syncDiagnostics() }

        // Update entities: a Companion installed or removed outside ha-paneld, an expired Panel
        // Assistant lease or a changed install route all surface here within one tick.
        runCatching { reconcileSoftwareUpdates(announcing = false) }

        // Architectural safety net: audit every registered state channel from its declared authority.
        // Stable acknowledged values cost no publish; failed sends stay dirty and retry next heartbeat.
        runCatching { stateConverger.reconcileAll() }
    }

    // Current string value for a diagnostic sensor, or null when unavailable on this panel.
    private fun diagValue(key: String): String? = when (key) {
        "diag_ip" -> PanelMetrics.shared.ipAddress()
        "diag_cpu" -> PanelMetrics.shared.systemSnapshot().cpuOverall?.toString()
        "diag_memory" -> PanelMetrics.shared.systemSnapshot().memPercent?.toString()
        "diag_soc_temp" -> PanelMetrics.shared.systemSnapshot().socTempC?.let { String.format(java.util.Locale.US, "%.1f", it) }
        "diag_boot" -> PanelMetrics.shared.bootTime()
        "diag_wifi_ssid" -> wifiDiagnostics(WifiDiagnosticDemand(
            ssid = true,
            privilegedRoute = capabilityShape.current().live.networkAdb,
        )).ssid
        "diag_wifi_rssi" -> wifiDiagnostics(WifiDiagnosticDemand(
            rssi = true,
            privilegedRoute = capabilityShape.current().live.networkAdb,
        )).rssiDbm?.toString()
        // Rolling outage counts stay published (retained last value) through a Wi-Fi dropout —
        // that dropout is exactly what they exist to report — so they are not WIFI_DIAGNOSTIC_KEYS.
        "diag_wifi_outages_24h" -> wifiOutages()?.last24h?.toString()
        // Room climate — apply the calibration offset to temperature; humidity is reported whole-percent.
        "room_temp" -> PanelMetrics.shared.roomClimate()?.tempC?.let { String.format(java.util.Locale.US, "%.1f", it + config.roomTempOffsetC) }
        "room_humidity" -> PanelMetrics.shared.roomClimate()?.humidityPct?.let { String.format(java.util.Locale.US, "%.0f", it) }
        else -> null
    }

    /** Publish a diagnostic sensor's current value (unconditional — used at expose time). */
    private fun publishDiag(key: String) {
        stateConverger.reconcile(key, force = true)
    }

    /** Per-tick refresh of the numeric/IP diagnostic sensors, gated on expose + a per-metric deadband.
     *  Measurement sensors may additionally publish one unchanged value after the bounded maximum
     *  silence. Boot time (constant) is published only at expose. */
    private fun syncDiagnostics() {
        val keys = if (hasCht8305) DIAG_KEYS + ROOM_KEYS else DIAG_KEYS
        for (key in keys) {
            val exposedByDefault = requireNotNull(SettingsRegistry.spec(key)).haExposedByDefault
            if (!config.haExposed(key, exposedByDefault)) continue
            stateConverger.reconcile(key)
        }
    }

    /** Sync predicate — delegates to the pure [io.github.maxlyth.hapaneld.mqtt.SyncGate] (unit-tested):
     *  [cur] differs from [published] beyond [deadband] AND isn't still moving fast (within SETTLE_BAND of
     *  the previous tick's read — a mid-flight value waits for the next tick). */
    private fun settled(cur: Int, prevTick: Int, published: Int, deadband: Int): Boolean =
        io.github.maxlyth.hapaneld.mqtt.SyncGate.settled(cur, prevTick, published, deadband, SETTLE_BAND)

    /** Recent local-state sync events (newest first) for the info page + /diag. Empty when nothing has
     *  changed outside MQTT since boot. */
    fun recentSyncEvents(): List<String> = syncLog.recent(SystemClock.elapsedRealtime())

    fun convergenceStatus(): String = stateConverger.status().let {
        "${it.channels} channels · ${it.dirty} dirty · ${it.inFlight} in-flight · ${it.unknown} unknown · " +
            "ack ${it.successes}/${it.failures} · pending ${it.pending.joinToString(",").ifEmpty { "none" }}"
    }

    /** Publish screen=OFF and reset the brightness baseline used by local-state reconciliation. */
    private fun publishScreenOff() {
        lastScreenBrightness = -1
        screenEffectiveBaseline = -1
        stateConverger.reconcile("screen", force = true)
    }

    /** Publish screen=ON at [level] and remember it as the last-reported brightness (the reconcile
     *  in [syncLocalState] compares the effective backlight against this). */
    private fun publishScreenBrightness(level: Int) {
        lastScreenBrightness = level
        screenEffectiveBaseline = -1   // re-capture on the next tick, after the framework settles
        stateConverger.reconcile("screen", force = true)
    }

    fun stop(
        deadline: MonotonicDeadline,
        publishOffline: Boolean = true,
        clearDiscovery: Boolean = false,
    ): MqttRetirement {
        val result = MqttRetirement()
        if (!retirement.compareAndSet(null, result)) return checkNotNull(retirement.get())

        // Fence every mutation source before spending the shared budget waiting on any one of them.
        lifecycle.closeAdmission()
        announcementReadiness.clear()
        connectionEventDispatcher.close()
        connectAnnouncementDispatcher.close()
        stateConverger.close()
        zigbeeActuation.retire(zigbeeLease)
        val cancelledCommands = commandDispatcher.close()
        val queuedZigbee = zigbeeWorker.pendingCount()
        haLinkResolutionThread.get()?.interrupt()
        authRecovery.supersedeRetry()
        authScheduler.shutdownNow()
        reloadNavigationFuture?.cancel(false)
        reloadNavigationFuture = null
        zigbeeWorker.closeAndJoin(0L)
        adbReassertWorker.closeAndJoin(0L)
        reannounceDispatcher.close()

        if (cancelledCommands > 0) {
            FeatureCosts.registry.recordDropped(
                FeatureCostOperation.MQTT_COMMAND_DISPATCH,
                cancelledCommands.toLong(),
            )
        }
        if (queuedZigbee > 0) {
            FeatureCosts.registry.recordDropped(FeatureCostOperation.ZIGBEE_RECONCILE, queuedZigbee.toLong())
        }
        FeatureCosts.registry.setBacklog(FeatureCostOperation.MQTT_COMMAND_DISPATCH, 0)
        FeatureCosts.registry.setBacklog(FeatureCostOperation.ZIGBEE_RECONCILE, 0)
        FeatureCosts.registry.setBacklog(FeatureCostOperation.MQTT_DISCOVERY_REANNOUNCE, 0)

        try {
            val lifecycleDrained = lifecycle.awaitDrained(deadline)
            val connectionEventDrained = connectionEventDispatcher.closeAndJoin(deadline.remainingMs())
            val connectAnnouncementDrained = connectAnnouncementDispatcher.closeAndJoin(deadline.remainingMs())
            val stateDrained = stateConverger.closeAndDrain(deadline)
            val commandsDrained = commandDispatcher.awaitDrained(deadline)
            val haLinkDrained = haLinkResolutionThread.get().interruptAndJoin(deadline)
            val authDrained = authScheduler.shutdownNowAndAwait(deadline)
            val zigbeeDrained = zigbeeWorker.closeAndJoin(deadline.remainingMs())
            val adbReassertDrained = adbReassertWorker.closeAndJoin(deadline.remainingMs())
            val reannounceDrained = reannounceDispatcher.closeAndJoin(deadline.remainingMs())
            val ownersDrained = lifecycleDrained && connectionEventDrained && connectAnnouncementDrained && stateDrained &&
                commandsDrained && haLinkDrained && authDrained && zigbeeDrained &&
                adbReassertDrained && reannounceDrained

            // An admitted start may have created these immediately before the lifecycle gate drained.
            activeConnection = null
            connectionGeneration.clear()
            buttonSubscription?.close()
            buttonSubscription = null
            publishRecoveryLifecycleStateWithAddressFamily("disabled", null)
            result.ownersDrained.complete(ownersDrained)

            if (!ownersDrained) {
                Log.w(
                    TAG,
                    "MQTT retirement did not drain all mutation owners " +
                        "(lifecycle=$lifecycleDrained connection=$connectionEventDrained " +
                        "announce=$connectAnnouncementDrained " +
                        "state=$stateDrained commands=$commandsDrained " +
                        "haLink=$haLinkDrained auth=$authDrained zigbee=$zigbeeDrained " +
                        "adb=$adbReassertDrained reannounce=$reannounceDrained)",
                )
                transport.disconnectDetached()
                result.finalization.completeExceptionally(
                    IllegalStateException("MQTT mutation owners did not drain"),
                )
                return result
            }

            val finalPublications = buildList {
                if (clearDiscovery) knownConfigTopics().forEach {
                    add(MqttFinalPublish(it, byteArrayOf(), retain = true))
                }
                if (publishOffline) {
                    add(MqttFinalPublish(availabilityTopic, "offline".toByteArray(), retain = true))
                }
            }
            val transportFinalization = if (finalPublications.isNotEmpty()) {
                val remainingMs = deadline.remainingMs()
                if (remainingMs <= 0L) {
                    transport.disconnectDetached()
                    result.finalization.completeExceptionally(
                        IllegalStateException("MQTT retirement deadline expired before final publication"),
                    )
                    return result
                }
                transport.publishThenDisconnect(
                    finalPublications,
                    timeoutMs = remainingMs,
                )
            } else {
                transport.disconnectDetached()
            }
            transportFinalization.whenComplete { _, failure ->
                if (failure == null) result.finalization.complete(Unit)
                else result.finalization.completeExceptionally(failure)
            }
        } catch (failure: Throwable) {
            result.ownersDrained.complete(false)
            runCatching { transport.disconnectDetached() }
            result.finalization.completeExceptionally(failure)
        }
        return result
    }

    companion object {
        private const val TAG = "ha-paneld/mqtt"
        private val ANNOUNCEMENT_BOUNDARY_CONSUMED_HERE = AtomicReference<String?>(null)
        private const val MAX_COMMAND_PAYLOAD_BYTES = 64 * 1024
        private const val MAX_DYNAMIC_COMMAND_INDEX = 64
        /** Approval principals of the two remote command transports. */
        private const val MQTT_PEER = "mqtt"
        private const val PANEL_ASSISTANT_PEER = "panel_assistant"
        private const val HA_LINK_TTL_MS = 6 * 3_600_000L // re-resolve the "Open in HA" link at most every 6h

        /** Keys accepted by [applySetting], declared once by [SettingsRegistry]. */
        internal val APPLY_SETTING_KEYS = SettingsRegistry.liveApplyKeys().toSet()

        /**
         * Inject `default_entity_id` = `<component>.<objectId>` as the FIRST key of a discovery payload —
         * the field HA actually honours to pin the entity_id. HA's `object_id` discovery key is IGNORED
         * (it isn't in the MQTT schema; verified against live HA 2026.7.1 source + registry): MQTT entities
         * are ALWAYS `has_entity_name=True`, so without this the entity_id derives from the mutable
         * device/friendly name (`slug(device.name + entity.name)`) and drifts when the friendly name is
         * renamed. `default_entity_id` anchors it to the stable panel_id instead, while `device.name` stays
         * the pretty friendly name. HA applies it at REGISTRATION only, so existing entities keep their id
         * (no churn). Empty payload = tombstone (returned unchanged). Idempotent. Pure — tested in
         * DefaultEntityIdTest.
         */
        fun withDefaultEntityId(component: String, objectId: String, payload: String): String =
            if (payload.isNotEmpty() && !payload.contains("\"default_entity_id\"")) {
                """{"default_entity_id":"$component.$objectId",""" + payload.substring(1)
            } else {
                payload
            }

        private const val SCREEN_DRIFT = 2   // reconcile threshold (0-255 scale) — ignore rounding jitter
        private const val SETTLE_BAND = 6    // "still moving" if the value shifted more than this since last tick
        // Diagnostic sensors published via the opt-in exposable() gate (all default local-only).
        private val DIAG_KEYS = listOf(
            "diag_ip", "diag_cpu", "diag_memory", "diag_soc_temp", "diag_boot",
            "diag_wifi_ssid", "diag_wifi_rssi", "diag_wifi_outages_24h",
        )
        // Room climate sensors — available only on panels with a CHT8305 (see hasCht8305).
        private val ROOM_KEYS = listOf("room_temp", "room_humidity")
        // How long to wait after a reload before deep-linking to the intended dashboard — lets the WebView
        // cold-start + the HA frontend load so the navigate deeplink isn't swallowed.
        private const val RELOAD_NAV_DELAY_MS = 8_000L
        // MQTT keepalive: PINGREQ every this-many idle seconds. Short enough to detect a dead link within
        // ~1.5× this, well under the service liveness-watchdog's stale threshold.
        private const val KEEPALIVE_SEC = 30
        private const val REANNOUNCE_DEBOUNCE_MS = 250L
        private const val REANNOUNCE_CAPABILITY_TTL_MS = 5_000L
        private const val CAPABILITY_RECOVERY_PROBE_MS = 30_000L
    }
}

internal data class MqttCapabilityChannels(
    val zigbee: Boolean = false,
    val cpu: Boolean = false,
    val networkAdb: Boolean = false,
    val relays: Int = 0,
    val buttonLeds: Int = 0,
) {
    fun keys(): Set<String> = buildSet {
        if (zigbee) add("zigbee_router")
        if (cpu) add("cpu_governor")
        if (networkAdb) add("network_adb")
        for (n in 1..relays) add("relay$n")
        for (n in 1..buttonLeds) add("button_led$n")
    }
}

internal data class MqttCapabilityObservation(
    val possible: MqttCapabilityChannels,
    val live: MqttCapabilityChannels,
    val previousLive: MqttCapabilityChannels,
    val grew: Boolean,
) {
    fun changedKeys(): Set<String> = buildSet {
        if (live.zigbee != previousLive.zigbee && possible.zigbee) add("zigbee_router")
        if (live.cpu != previousLive.cpu && possible.cpu) add("cpu_governor")
        if (live.networkAdb != previousLive.networkAdb && possible.networkAdb) add("network_adb")
        if (live.relays != previousLive.relays) {
            for (n in minOf(live.relays, previousLive.relays) + 1..maxOf(live.relays, previousLive.relays)) {
                add("relay$n")
            }
        }
        if (live.buttonLeds != previousLive.buttonLeds) {
            for (n in minOf(live.buttonLeds, previousLive.buttonLeds) + 1..
                maxOf(live.buttonLeds, previousLive.buttonLeds)
            ) {
                add("button_led$n")
            }
        }
    }
}

/**
 * Separates monotonic channel shape from the latest live probe. Once hardware has been confirmed its
 * MQTT identity stays stable; a later unavailable snapshot gates its state observer instead of deleting
 * the entity. Newly confirmed channels can be registered and announced by the existing bridge.
 */
internal class MqttCapabilityShape(private val maxDynamicChannels: Int = 64) {
    init {
        require(maxDynamicChannels >= 0)
    }

    private var possible = MqttCapabilityChannels()
    private var live = MqttCapabilityChannels()

    @Synchronized
    fun observe(capabilities: Capabilities): MqttCapabilityObservation {
        val previousLive = live
        live = MqttCapabilityChannels(
            zigbee = capabilities.zigbeePresent,
            cpu = capabilities.cpuGovernors,
            networkAdb = capabilities.networkAdb,
            relays = capabilities.relays.coerceIn(0, maxDynamicChannels),
            buttonLeds = capabilities.buttonLeds.coerceIn(0, maxDynamicChannels),
        )
        val nextPossible = MqttCapabilityChannels(
            zigbee = possible.zigbee || live.zigbee,
            cpu = possible.cpu || live.cpu,
            networkAdb = possible.networkAdb || live.networkAdb,
            relays = maxOf(possible.relays, live.relays),
            buttonLeds = maxOf(possible.buttonLeds, live.buttonLeds),
        )
        val grew = nextPossible != possible
        possible = nextPossible
        return MqttCapabilityObservation(possible, live, previousLive, grew)
    }

    @Synchronized
    fun current(): MqttCapabilityObservation =
        MqttCapabilityObservation(possible, live, live, grew = false)
}

/** One immutable capability value per discovery announcement, with a short monotonic burst cache. */
internal class MqttDiscoveryCapabilitySource(
    private val supplier: (() -> Capabilities)?,
    private val onFailure: (Exception) -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val initial = AtomicReference<Capabilities?>()
    private var latest: Capabilities? = null
    private var latestAtMs = Long.MIN_VALUE

    /** The last snapshot taken, without probing. Null before the first one. */
    @Synchronized
    fun cached(): Capabilities? = latest

    @Synchronized
    fun snapshot(maxAgeMs: Long = 0L): Capabilities? {
        val source = supplier ?: return null
        val now = nowMs()
        if (maxAgeMs > 0L && latestAtMs != Long.MIN_VALUE) {
            val age = now - latestAtMs
            if (age in 0..maxAgeMs) return latest
        }
        val value = try {
            source()
        } catch (e: Exception) {
            onFailure(e)
            // A failed live probe must not abort the complete discovery announcement. All-false is the
            // fail-closed settings view; hardware entities driven by constructor/profile facts continue.
            Capabilities()
        }
        initial.compareAndSet(null, value)
        latest = value
        latestAtMs = nowMs()
        return value
    }

    fun initialSnapshot(): Capabilities? = initial.get()

    @Synchronized
    fun invalidate() {
        latest = null
        latestAtMs = Long.MIN_VALUE
    }
}

/** Debounces a burst into one latest request while keeping callback admission constant-space. */
internal class MqttReannounceDispatcher(
    private val debounceMs: Long,
    private val pause: (Long) -> Unit = Thread::sleep,
    private val perform: (Long) -> Unit,
) {
    private val requestedGeneration = AtomicLong()
    private val closed = AtomicBoolean()
    private val worker = LatestDispatcher.singleSlot<Long>("mqtt-discovery-reannounce", consume = worker@{ generation ->
        try {
            pause(debounceMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return@worker
        }
        if (isCurrent(generation)) perform(generation)
    })

    fun submit(): LatestDispatcher.Admission = worker.submit(requestedGeneration.incrementAndGet())

    fun pendingCount(): Int = worker.pendingCount()

    fun isCurrent(generation: Long): Boolean = !closed.get() && generation == requestedGeneration.get()

    /** Invalidate a retained birth that arrived while a newer connect announcement was in progress. */
    fun suppressPending() {
        requestedGeneration.incrementAndGet()
    }

    fun close() {
        closed.set(true)
        worker.closeAndJoin(0L)
    }

    fun closeAndJoin(timeoutMs: Long): Boolean {
        close()
        return worker.closeAndJoin(timeoutMs)
    }
}

/** Serializes Zigbee actuation across replaceable bridge generations. Invalidation is non-blocking;
 * a newer lease waits behind any old uninterruptible call and therefore always owns the final state. */
internal class MqttZigbeeActuationCoordinator {
    class Lease internal constructor()
    data class Execution<T>(val executed: Boolean, val currentAfter: Boolean, val value: T?)

    private val current = AtomicReference<Lease?>()
    private val actuationLock = Any()

    fun activate(): Lease = Lease().also { replacement -> current.getAndSet(replacement) }

    fun retire(lease: Lease) {
        current.compareAndSet(lease, null)
    }

    fun isCurrent(lease: Lease): Boolean = current.get() === lease

    fun <T> executeIfCurrent(lease: Lease, action: () -> T): Execution<T> = synchronized(actuationLock) {
        if (!isCurrent(lease)) return@synchronized Execution(false, false, null)
        val value = action()
        Execution(true, isCurrent(lease), value)
    }
}

private object MqttZigbeeActuationCoordinators {
    private val controllers = WeakHashMap<ZigbeeController, MqttZigbeeActuationCoordinator>()

    @Synchronized
    fun forController(controller: ZigbeeController): MqttZigbeeActuationCoordinator =
        controllers.getOrPut(controller) { MqttZigbeeActuationCoordinator() }
}

/**
 * The historical discovery superset is deliberately explicit: if a registry entity is later removed,
 * its topic must remain here so an upgrade or panel-id change can still delete the old HA entity. The
 * coverage test requires every current registry entity to be added before it can ship.
 */
internal fun mqttKnownConfigTopics(panel: String): Set<String> = listOf(
    "light" to "${panel}_screen", "light" to "${panel}_led",
    "text" to "${panel}_navigate", "text" to "${panel}_home_dashboard", "event" to "${panel}_button",
    "button" to "${panel}_back", "button" to "${panel}_recents",
    "number" to "${panel}_volume", "sensor" to "${panel}_illuminance",
    "binary_sensor" to "${panel}_proximity",
    "sensor" to "${panel}_proximity_level",
    "binary_sensor" to "${panel}_auto_sleep_activity",
    "sensor" to "${panel}_temperature", "sensor" to "${panel}_humidity",
    "sensor" to "${panel}_room_temp", "sensor" to "${panel}_room_humidity",
    "light" to "${panel}_buttons", "switch" to "${panel}_wake_on_wave", "switch" to "${panel}_auto_sleep",
    "switch" to "${panel}_touch_sound", "switch" to "${panel}_watchdog", "switch" to "${panel}_kiosk_lock",
    "switch" to "${panel}_silence_boot_chime", "switch" to "${panel}_prevent_idle_dim",
    "switch" to "${panel}_companion_auto_update", "button" to "${panel}_update_companion",
    "select" to "${panel}_companion_update_channel",
    "switch" to "${panel}_self_update", "select" to "${panel}_update_channel",
    "button" to "${panel}_update_paneld",
    "update" to "${panel}_ha_paneld_update", "update" to "${panel}_ha_companion_update",
    "switch" to "${panel}_webview_auto_update",
    "switch" to "${panel}_zigbee_router",
    "switch" to "${panel}_auto_brightness", "number" to "${panel}_brightness_bias",
    "number" to "${panel}_ambient_lux",
    "switch" to "${panel}_relay1", "switch" to "${panel}_relay2",
    "switch" to "${panel}_relay3", "switch" to "${panel}_relay4",
    "light" to "${panel}_button_led1", "light" to "${panel}_button_led2",
    "light" to "${panel}_button_led3", "light" to "${panel}_button_led4",
    "select" to "${panel}_cpu_governor", "select" to "${panel}_navbar",
    "switch" to "${panel}_network_adb",
    "switch" to "${panel}_voice_assistant", "sensor" to "${panel}_voice_state",
    "sensor" to "${panel}_diag_ip", "sensor" to "${panel}_diag_cpu",
    "sensor" to "${panel}_diag_memory", "sensor" to "${panel}_diag_soc_temp",
    "sensor" to "${panel}_diag_boot", "sensor" to "${panel}_diag_wifi_ssid",
    "sensor" to "${panel}_diag_wifi_rssi",
    "sensor" to "${panel}_diag_wifi_outages_24h", "sensor" to "${panel}_diag_wifi_outages_7d",
    "sensor" to "${panel}_diag_schema_reconcile",
    "sensor" to "${panel}_storage_health",
    "switch" to "${panel}_camera_enabled", "image" to "${panel}_camera_snapshot",
    "button" to "${panel}_reload", "button" to "${panel}_reboot",
    "button" to "${panel}_launcher", "button" to "${panel}_home",
    "button" to "${panel}_admin_launcher",
).mapTo(linkedSetOf()) { (comp, obj) -> "homeassistant/$comp/$obj/config" }

internal fun mqttButtonEventTypes(profileEventTypes: Set<String>): List<String> =
    (setOf(
        "KEYCODE_POWER", "KEYCODE_MUTE", "KEYCODE_F", "KEYCODE_F1", "KEYCODE_F2", "KEYCODE_F3",
        "KEYCODE_F4", "KEYCODE_BACK", "KEYCODE_HOME", "KEYCODE_DPAD_CENTER", "KEYCODE_VOLUME_UP",
        "KEYCODE_VOLUME_DOWN",
    ) + profileEventTypes).sorted()

internal data class MqttCleanupPublication(val topic: String, val payload: String, val retain: Boolean)

/**
 * Durable identity for the discovery shape which last completed stale-topic pruning. The explicit
 * shape revision forces cleanup when entities are retired without an app-version change. A non-empty
 * profile identity is length-prefixed so arbitrary profile ids cannot collide with the other fields.
 */
internal fun mqttDiscoveryCleanupMarker(
    coreVersion: String,
    profileIdentity: String,
    brokerIdentity: String,
    discoveryShapeRevision: Int = MQTT_DISCOVERY_SHAPE_REVISION,
): String {
    require(discoveryShapeRevision > 0) { "discovery shape revision must be positive" }
    val shape = "$coreVersion|d$discoveryShapeRevision|b${brokerIdentity.length}:$brokerIdentity"
    return if (profileIdentity.isEmpty()) shape else "$shape|p${profileIdentity.length}:$profileIdentity"
}

private const val MQTT_DISCOVERY_SHAPE_REVISION = 5

/** Cleanup for a renamed panel is owned by the replacement connection and therefore retries with it. */
internal fun mqttStalePanelCleanup(stalePanel: String?, currentPanel: String): List<MqttCleanupPublication> {
    if (stalePanel == null || stalePanel == currentPanel) return emptyList()
    return buildList {
        mqttKnownConfigTopics(stalePanel).forEach { add(MqttCleanupPublication(it, "", retain = true)) }
        mqttRetiredStateTopics(stalePanel).forEach { add(MqttCleanupPublication(it, "", retain = true)) }
        add(MqttCleanupPublication("ha-paneld/$stalePanel/availability", "offline", retain = true))
    }
}

internal fun mqttRetiredStateTopics(panel: String): Set<String> = setOf(
    "ha-paneld/$panel/diag_schema_reconcile/state",
    // Retired before release; its retained state must be cleared too, or a same-version upgrade
    // leaves a ghost entity holding a stale count.
    "ha-paneld/$panel/diag_wifi_outages_7d/state",
    "ha-paneld/$panel/diag_wifi_outages_7d/attributes",
)

/** Avoid a late offline/online race on an in-place rebuild; retire identities or old brokers explicitly.
 * Broker sameness uses the single family-recovery canonicalizer; an unparseable broker falls back to its
 * trimmed literal so two genuinely different unusable strings still retire the old availability. */
internal fun mqttReconfigurePublishesOffline(
    oldPanel: String,
    newPanel: String,
    oldBroker: String,
    newBroker: String,
): Boolean = oldPanel != newPanel || mqttReconfigureBrokerIdentity(oldBroker) != mqttReconfigureBrokerIdentity(newBroker)

private fun mqttReconfigureBrokerIdentity(raw: String): String =
    mqttFamilyBrokerIdentity(raw) ?: raw.trim()

internal fun mqttAcceptsCommand(stopped: Boolean, retained: Boolean, panelAssistantAuthority: String = ""): Boolean =
    !stopped && !retained && panelAssistantAuthority != PanelAssistantTransportProtocol.AUTHORITY_NATIVE

/** A sensitive remote command is waiting for [approvalId] to be approved on the panel's own screen. */
internal class SensitiveApprovalPendingException(val approvalId: String, message: String) : IllegalStateException(message)

/** A remote command Hardened mode refuses outright, with no approval that could allow it. */
internal class HardenedModeRefusalException(message: String) : IllegalStateException(message)

/** How a command handler's outcome reads on the native transport. */
internal fun panelAssistantCommandResult(failure: Exception?): PanelAssistantCommandResult = when (failure) {
    null -> PanelAssistantCommandResult.Applied
    is SensitiveApprovalPendingException -> PanelAssistantCommandResult.ApprovalPending(failure.approvalId)
    is HardenedModeRefusalException ->
        PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_REFUSED_HARDENED)
    is LiveSettingUnavailableException ->
        PanelAssistantCommandResult.Failed(PanelAssistantCommandProcessor.CODE_HARDWARE_UNAVAILABLE)
    is IllegalArgumentException -> PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_INVALID_VALUE)
    else -> PanelAssistantCommandResult.Failed(PanelAssistantCommandProcessor.CODE_FAILED)
}

/**
 * Map Home Assistant's birth/will payload to a lifecycle observation, or null for anything else.
 *
 * Retained messages are ignored on purpose. A retained birth/will is HISTORY replayed to every new
 * subscriber, so honouring it would announce "back online" on each broker reconnect — the same defect
 * class as the retained screen-off command that once stranded a panel. Home Assistant's own default is
 * `retain: False`, but the broker or a user may retain it, and the reconnect path must not depend on that.
 *
 * Pure — unit-tested in `HaLifecycleMqttSourceTest`.
 */
internal fun haLifecycleFromMqttStatus(payload: ByteArray, retained: Boolean): HaLifecycleEvent? {
    if (retained) return null
    return when (String(payload, Charsets.UTF_8).trim().lowercase(java.util.Locale.ROOT)) {
        "online" -> HaLifecycleEvent.STARTED
        "offline" -> HaLifecycleEvent.STOP
        else -> null
    }
}

internal fun mqttIsHaOnline(payload: ByteArray): Boolean =
    String(payload, Charsets.UTF_8).trim().equals("online", ignoreCase = true)

internal fun mqttDiscoveryRetain(payload: String): Boolean = payload.isEmpty()

/** Human-readable software identity shown on the Home Assistant device page. */
internal fun mqttDeviceSoftwareVersion(versionName: String, versionCode: Int): String =
    "$versionName (build $versionCode)"

internal fun shouldRepublishDiscoveryAddress(
    connected: Boolean,
    lastPublishedUrl: String?,
    currentUrl: String?,
): Boolean = connected && lastPublishedUrl != currentUrl
