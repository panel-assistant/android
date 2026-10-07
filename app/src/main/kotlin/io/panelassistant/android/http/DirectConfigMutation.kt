package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.config.SettingValue
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.config.Validation
import io.panelassistant.android.util.LogShipEndpoint

internal data class DirectConfigMutationPlan(
    val changedKeys: Set<String>,
    val changedLive: List<Pair<String, String>>,
) {
    val isNoOp: Boolean get() = changedKeys.isEmpty()
    val requiresReconfigure: Boolean get() = changedKeys.any { it !in SettingsRegistry.liveApplyKeys() }
}

/** Direct settings whose real writer is a post-commit subsystem transition rather than Config's
 * registry writer. The list is exact: adding a key requires an owner callback and walker evidence. */
internal val DIRECT_CONFIG_DELEGATED_KEYS: Set<String> = setOf("dashboard_entity_learning")

/**
 * The one writer of ordinary direct-POST settings: each validated changed value reaches Config once, so no
 * registered setting can be silently read and dropped. `panel_id` and `dashboard_package` go through the
 * setters that also stage their secondary keys, as a bundle import does. The bespoke block which follows
 * owns only coupled credentials, keys with no spec and live keys.
 */
internal fun stageDirectConfigRegistryValues(
    config: Config,
    posted: Map<String, String>,
    changedKeys: Set<String>,
): Set<String> = buildSet {
    SettingsRegistry.directPostable().forEach { spec ->
        if (spec.liveApply || spec.key in DIRECT_CONFIG_DELEGATED_KEYS || spec.key !in changedKeys) return@forEach
        val raw = posted[spec.key] ?: return@forEach
        val normalized = (SettingValue.validate(spec, raw) as? Validation.Ok)?.normalized ?: return@forEach
        when (spec.key) {
            "panel_id" -> config.setPanelId(normalized)
            "dashboard_package" -> config.setDashboardPackage(normalized)
            else -> config.setRaw(spec, normalized)
        }
        add(spec.key)
    }
}

/** Route the exact post-commit owner-managed subset through an injected real owner. */
internal fun applyDirectConfigDelegatedSettings(
    posted: Map<String, String>,
    changedKeys: Set<String>,
    apply: (String, String) -> Boolean,
): Set<String> = buildSet {
    DIRECT_CONFIG_DELEGATED_KEYS.forEach { key ->
        if (key in changedKeys && posted[key]?.let { apply(key, it) } == true) add(key)
    }
}

internal data class DirectCredentialEffects(val haChanged: Boolean)

/** Stage the three coupled log destination fields through the endpoint owner which honours a scheme
 * or port embedded in the host value. */
internal fun stageDirectLogShipping(config: Config, posted: Map<String, String>) {
    val enabled = posted["log_ship_enabled"]?.toBooleanStrictOrNull()
    val host = posted["log_ship_host"]
    val port = posted["log_ship_port"]?.toIntOrNull()
    val protocol = posted["log_ship_protocol"]
    if (enabled != null || host != null || port != null || protocol != null) {
        config.setLogShipping(
            enabled ?: config.logShipEnabled,
            host ?: config.logShipHost,
            port ?: config.logShipPort,
            protocol ?: config.logShipProtocol,
        )
    }
}

/** Stage the direct form's coupled credential groups through their real Config owners. Blank secret
 * placeholders preserve existing credentials except where an owner field is explicitly cleared or the
 * Home Assistant origin changes. Kept production-used so mutations to those dependent clears reach the JVM
 * contract instead of surviving behind a per-key writer test. */
internal fun stageDirectCredentialSettings(
    config: Config,
    posted: Map<String, String>,
): DirectCredentialEffects {
    val broker = posted["mqtt_broker"]
    val user = posted["mqtt_user"]
    val mqttAddressFamily = posted["mqtt_address_family"]
    val brokerChanged = broker != null && broker != config.mqttBroker
    val password = when {
        user != null && user.isEmpty() -> ""
        brokerChanged && config.hardenedSecurityEnabled -> posted["mqtt_password"]?.takeIf(String::isNotEmpty) ?: ""
        else -> posted["mqtt_password"]?.takeIf(String::isNotEmpty)
    }
    if (broker != null || user != null || password != null || mqttAddressFamily != null) {
        config.setMqtt(
            broker ?: config.mqttBroker,
            user ?: config.mqttUser,
            password,
            mqttAddressFamily,
        )
    }

    val previousUrl = config.haUrl
    val previousToken = config.haToken
    val previousRefresh = config.haRefreshToken
    val previousExpiry = config.haTokenExpiry
    val previousClientId = config.haClientId
    val url = posted["ha_url"]
    val haOriginChange = url != null &&
        url.trimEnd('/') != previousUrl.trimEnd('/')
    val token = when {
        url != null && url.isEmpty() -> ""
        haOriginChange -> posted["ha_token"]?.takeIf(String::isNotEmpty) ?: ""
        else -> posted["ha_token"]?.takeIf(String::isNotEmpty)
    }
    if (url != null || token != null) config.setHaConnection(url ?: previousUrl, token)

    val clearingHa = url != null && url.isEmpty()
    val refresh = when {
        clearingHa -> ""
        haOriginChange -> posted["ha_refresh_token"]?.takeIf(String::isNotEmpty) ?: ""
        else -> posted["ha_refresh_token"]?.takeIf(String::isNotEmpty)
    }
    refresh?.let(config::setHaRefreshToken)
    val expiry = posted["ha_token_expiry"]?.toLongOrNull() ?: if (haOriginChange) 0L else null
    val clientId = posted["ha_client_id"]?.let { if (clearingHa) "" else it }
        ?: if (haOriginChange) "" else null
    expiry?.let(config::setHaTokenExpiry)
    clientId?.let(config::setHaClientId)
    if (clearingHa) config.setHaRefreshToken("")

    val refreshCleared = token != null && token.isNotEmpty() && refresh == null && previousRefresh.isNotEmpty()
    if (refreshCleared) {
        config.setHaRefreshToken("")
        config.setHaTokenExpiry(0L)
    }
    return DirectCredentialEffects(
        haChanged = (url != null && url != previousUrl) ||
            (token != null && token != previousToken) ||
            (refresh != null && refresh != previousRefresh) || refreshCleared ||
            (expiry != null && expiry != previousExpiry) ||
            (clientId != null && clientId != previousClientId),
    )
}

/** Production-used iteration seam between HTTP planning and the shared service dispatcher. */
internal fun dispatchDirectConfigLiveSettings(
    changedLive: List<Pair<String, String>>,
    dispatch: (String, String) -> Unit,
) {
    changedLive.forEach { (key, value) -> dispatch(key, value) }
}

internal data class DirectConfigOrdinaryOutcomes(
    val applied: Set<String>,
    val rejected: Set<String>,
)

/** Canonical values a successful direct write must read back. Compute this while the transaction still
 * sees pre-commit state: a legacy log host may carry an embedded scheme/port which outranks a partial
 * update, and that precedence is no longer recoverable after the owner canonicalizes the stored host. */
internal fun directConfigExpectedReadBack(
    config: Config,
    posted: Map<String, String>,
): Map<String, String> = posted.toMutableMap().apply {
    LogShipEndpoint.canonicalUpdate(
        posted,
        config.logShipHost,
        config.logShipPort,
        config.logShipProtocol,
    )?.forEach { (key, value) -> if (key in posted) put(key, value) }
}

/**
 * Describe durable outcomes from committed read-back, never from the planned changed-key set. This is
 * intentionally independent of which writer claimed a key: a read-then-drop handler and a setter whose
 * storage owner failed both compare unequal and therefore cannot be reported as applied.
 */
internal fun directConfigOrdinaryOutcomes(
    config: Config,
    posted: Map<String, String>,
    changedKeys: Set<String>,
    expectedReadBack: Map<String, String> = posted,
): DirectConfigOrdinaryOutcomes {
    val applied = linkedSetOf<String>()
    val rejected = linkedSetOf<String>()
    changedKeys.filterNot { it in SettingsRegistry.liveApplyKeys() }.forEach { key ->
        val expected = expectedReadBack[key] ?: return@forEach
        val spec = SettingsRegistry.spec(key)
        val actual = when {
            key in SettingsRegistry.directPostExcludedKeys -> null
            spec != null -> config.getRaw(spec)
            SettingsRegistry.parseExposure(key) != null -> {
                val exposed = requireNotNull(SettingsRegistry.parseExposure(key))
                config.haExposed(exposed.key, exposed.haExposedByDefault).toString()
            }
            key == "http_allowed_hosts" -> config.httpAllowedHostsRaw
            // Bespoke keys with no spec, read back from their own accessors so a handover is reported
            // applied on the evidence of what was stored rather than of what was planned.
            key == "ha_setup_handover" -> config.haSetupHandover.toString()
            key == "ha_url_handover" -> config.haUrlHandover
            key == "ha_url_handover_reason" -> config.haUrlHandoverReason
            else -> null
        }
        if (actual == expected) applied += key else rejected += key
    }
    return DirectConfigOrdinaryOutcomes(applied, rejected)
}

/** Name post-commit effect failures separately from their already-durable desired setting. */
internal fun directConfigEffectFailureOwner(entityLearningTransitionIncomplete: Boolean): String =
    if (entityLearningTransitionIncomplete) "dashboard_entity_learning_effect" else "renderer"

/**
 * The pending keys the Configure page may present as unappliable rather than about to apply.
 *
 * Every stalled key is also a pending key, and the page reads the desired value from `apply_pending`, so
 * a stall reported for anything outside it would mark a row the page is not showing as saved-and-waiting
 * — the value would appear unappliable with nothing to say what value. Ordered so the response is stable.
 */
internal fun stalledApplyKeys(pending: Set<String>, stalled: Set<String>): List<String> =
    stalled.filter { it in pending }.sorted()

/** Server-side equality is the transaction authority; browser dirty tracking is only a UX hint. */
internal fun planDirectConfigMutation(
    posted: Map<String, String>,
    before: Map<String, String>,
): DirectConfigMutationPlan {
    val liveKeys = SettingsRegistry.liveApplyKeys()
    val changed = linkedSetOf<String>()
    posted.forEach { (key, value) ->
        val spec = SettingsRegistry.spec(key)
        val unchangedSecretPlaceholder = spec?.secret == true && value.isEmpty()
        // "Unchanged" means the write would not alter STORED state, which is the canonical form the
        // validator produces compared against the raw value already held. Comparing renderer semantics
        // instead made canonicalization unreachable: replacing a stored `/` or `/?kiosk` with the blank
        // that means "follow the account default" resolves identically, so it was discarded as a no-op
        // and the stale spelling survived — reopening the picker in Custom after the user had chosen and
        // saved Auto. A stored value that is already canonical still compares equal, so re-saving an
        // unchanged setting remains a no-op.
        val equivalent = when (val validated = spec?.let { SettingValue.validate(it, value) }) {
            is Validation.Ok -> validated.normalized == before[key].orEmpty()
            else -> before[key] == value
        }
        if (!unchangedSecretPlaceholder && !equivalent) changed += key
    }
    return DirectConfigMutationPlan(
        changedKeys = changed,
        changedLive = liveKeys.mapNotNull { key ->
            posted[key]?.takeIf { key in changed }?.let { key to it }
        },
    )
}

internal fun configMutationWantsJson(accept: String?, contentType: String?): Boolean =
    accept?.contains("application/json", ignoreCase = true) == true ||
        contentType?.startsWith("application/json", ignoreCase = true) == true

internal fun configMutationHtml(message: String): String {
    return "<!doctype html><base href=\"/\"><meta charset=utf-8>" +
        "<meta http-equiv=refresh content='2;url=configure'>" +
        "<body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
        esc(message) + "</body>"
}
