package io.panelassistant.android.mqtt

import io.panelassistant.android.util.CompanionInstaller
import io.panelassistant.android.util.Json
import io.panelassistant.android.util.ReleaseCatalog
import io.panelassistant.android.util.UpdateChecker

/**
 * Home Assistant MQTT `update` entities for the two release-backed apps ha-paneld manages: itself and
 * the HA Companion app. Pure: the bridge supplies one [SoftwareUpdateInputs] per component and publishes
 * what these functions return, so discovery bytes, state, admission and discovery-shape transitions are
 * all decided here and unit-tested without a broker.
 *
 * The entities replace the stateless update buttons. For one release both are published, and they
 * share the buttons' command topics: Home Assistant's button sends `PRESS`, the update entity sends
 * [PAYLOAD_INSTALL], and nothing else is accepted.
 */
internal enum class SoftwareComponent(
    /** The component name used by the Install API and the button command topic (`update_<wire>`). */
    val wire: String,
    val objectSuffix: String,
    /** The [io.panelassistant.android.util.InstallProgress] component label while this app installs. */
    val progressLabel: String,
    val entityName: String,
) {
    PANELD("paneld", "ha_paneld", "ha-paneld", "ha-paneld"),
    COMPANION("companion", "ha_companion", "HA Companion", "HA Companion"),
}

/** One catalog release resolved under the panel's current channel (and, for Companion, safety cap). */
internal data class SoftwareTarget(
    val version: String,
    /** Exact release tag, the only value an admitted install passes to the installer. */
    val tag: String,
    val releaseUrl: String,
    /** Companion only: the target was held below the channel head by this panel's safety cap. */
    val capped: Boolean = false,
    val newestVersion: String? = null,
)

internal data class SoftwareUpdateInputs(
    val component: SoftwareComponent,
    /** Installed versionName, or null when the app is not installed (Companion only). */
    val installedVersion: String?,
    /** A full, Play-managed Companion: ha-paneld never replaces it, so it is read-only here. */
    val externallyManaged: Boolean = false,
    /** Resolved under the current [channel]/[cap] only; null when no same-policy resolution exists. */
    val target: SoftwareTarget?,
    val channel: String,
    val cap: String? = null,
    /** This panel has a verified privileged install route. */
    val canInstall: Boolean,
    /** This component owns the shared destructive-operation lane right now. */
    val installing: Boolean,
    /** A Panel Assistant config entry owns the ha-paneld update entity, so MQTT withholds its own. */
    val suppressed: Boolean = false,
    /** Whether the app is installed could not be read, so a null [installedVersion] is not a settled absence. */
    val presenceUnknown: Boolean = false,
)

/**
 * Everything the service knows about both components at one instant. The bridge adds only its own
 * install-capability observation, then projects one [SoftwareUpdateInputs] per component.
 */
internal data class SoftwareUpdateSources(
    val paneldVersion: String,
    val paneldChannel: String,
    val paneldTarget: SoftwareTarget?,
    /** Installed minimal Companion versionName, or null when the minimal package is absent. */
    val companionMinimalVersion: String?,
    /** Installed full Companion versionName, or null when the full package is absent. */
    val companionFullVersion: String?,
    val companionChannel: String,
    val companionCap: String?,
    val companionTarget: SoftwareTarget?,
    /** The InstallProgress component label while the shared lane is running, otherwise null. */
    val runningOperation: String?,
    val panelAssistantOwnsPaneldUpdate: Boolean,
    /** A Companion package lookup failed for a reason other than the package being absent. */
    val companionPresenceUnknown: Boolean = false,
) {
    fun inputs(component: SoftwareComponent, canInstall: Boolean): SoftwareUpdateInputs = when (component) {
        SoftwareComponent.PANELD -> SoftwareUpdateInputs(
            component = component,
            installedVersion = paneldVersion,
            target = paneldTarget,
            channel = paneldChannel,
            canInstall = canInstall,
            installing = runningOperation == component.progressLabel,
            suppressed = panelAssistantOwnsPaneldUpdate,
        )
        SoftwareComponent.COMPANION -> SoftwareUpdateInputs(
            component = component,
            // The full app wins: CompanionInstaller refuses to act while it is present.
            installedVersion = companionFullVersion ?: companionMinimalVersion,
            externallyManaged = companionFullVersion != null,
            target = companionTarget,
            channel = companionChannel,
            cap = companionCap,
            canInstall = canInstall,
            installing = runningOperation == component.progressLabel,
            presenceUnknown = companionPresenceUnknown,
        )
    }
}

internal enum class SoftwareCommand { INSTALL, LEGACY_PRESS, REJECTED }

internal sealed interface SoftwareCommandOutcome {
    data object Legacy : SoftwareCommandOutcome
    data class Started(val tag: String) : SoftwareCommandOutcome
    /** Admitted, but the shared destructive-operation lane was already owned. */
    data class Busy(val tag: String) : SoftwareCommandOutcome
    data class Refused(val reason: String) : SoftwareCommandOutcome
}

internal sealed interface SoftwareInstallAdmission {
    /** Install exactly [tag]; [downgrade] is the explicit Companion safety-cap remediation. */
    data class Admitted(val tag: String, val downgrade: Boolean = false) : SoftwareInstallAdmission
    data class Refused(val reason: String) : SoftwareInstallAdmission
}

/** What Home Assistant has been told about one entity. Persisted so a restart can still clear it. */
internal data class SoftwareDiscoveryShape(
    val announced: Boolean,
    val installable: Boolean,
    val hasLatest: Boolean,
) {
    fun encode(): String = "v1:" + listOf(announced, installable, hasLatest).joinToString("") { if (it) "1" else "0" }

    companion object {
        fun decode(raw: String?): SoftwareDiscoveryShape? {
            if (raw == null || raw.length != 6 || !raw.startsWith("v1:")) return null
            val bits = raw.substring(3).map {
                when (it) {
                    '1' -> true
                    '0' -> false
                    else -> return null
                }
            }
            return SoftwareDiscoveryShape(bits[0], bits[1], bits[2])
        }
    }
}

internal data class SoftwarePublication(val topic: String, val payload: String, val retain: Boolean)

internal enum class SoftwareDiscoveryStep {
    NONE,
    ANNOUNCE,
    /** Home Assistant cannot unset `latest_version` from state, so the entity is recreated. */
    CLEAR_THEN_ANNOUNCE,
    WITHDRAW,
}

internal object SoftwareUpdateEntities {
    const val PAYLOAD_INSTALL = "install"
    /** Home Assistant's default MQTT button payload; the legacy buttons send it to the same topics. */
    const val PAYLOAD_LEGACY_PRESS = "PRESS"
    const val NOT_INSTALLED = "not installed"
    private const val UNSUPPORTED_PREFIX = "unsupported "
    private const val MAX_SUMMARY_CHARS = 255

    fun objectId(panel: String, component: SoftwareComponent) = "${panel}_${component.objectSuffix}"
    fun uniqueId(panel: String, component: SoftwareComponent) = "${objectId(panel, component)}_update"
    fun configTopic(panel: String, component: SoftwareComponent) =
        "homeassistant/update/${uniqueId(panel, component)}/config"
    fun stateTopic(panel: String, component: SoftwareComponent) =
        "ha-paneld/$panel/update/${component.objectSuffix}/state"
    fun commandTopic(panel: String, component: SoftwareComponent) = "ha-paneld/$panel/update_${component.wire}/set"
    fun stateChannelKey(component: SoftwareComponent) = "software_update_${component.wire}"

    /** Payloads are matched exactly: a URL, a version, JSON or a changed case is never an install. */
    fun classifyCommand(payload: String): SoftwareCommand = when (payload) {
        PAYLOAD_INSTALL -> SoftwareCommand.INSTALL
        PAYLOAD_LEGACY_PRESS -> SoftwareCommand.LEGACY_PRESS
        else -> SoftwareCommand.REJECTED
    }

    /**
     * Route one command received on an update topic. The legacy button's `PRESS` keeps its existing
     * behaviour for the coexistence release; `install` is admitted against a fresh [inputs] read and
     * then approved and started with the catalog tag it resolved, never with anything from the payload;
     * every other payload is refused before inputs are read. [authorize] throws to refuse approval.
     */
    fun route(
        payload: String,
        inputs: () -> SoftwareUpdateInputs?,
        legacy: () -> Unit,
        authorize: (tag: String) -> Unit,
        install: (tag: String) -> Boolean,
    ): SoftwareCommandOutcome = when (classifyCommand(payload)) {
        SoftwareCommand.REJECTED -> SoftwareCommandOutcome.Refused("payload")
        SoftwareCommand.LEGACY_PRESS -> {
            legacy()
            SoftwareCommandOutcome.Legacy
        }
        SoftwareCommand.INSTALL -> when (val admission = inputs()?.let(::admit)
            ?: SoftwareInstallAdmission.Refused("unwired")) {
            is SoftwareInstallAdmission.Refused -> SoftwareCommandOutcome.Refused(admission.reason)
            is SoftwareInstallAdmission.Admitted -> {
                authorize(admission.tag)
                if (install(admission.tag)) SoftwareCommandOutcome.Started(admission.tag)
                else SoftwareCommandOutcome.Busy(admission.tag)
            }
        }
    }

    private fun normalized(component: SoftwareComponent, version: String): String =
        if (component == SoftwareComponent.COMPANION) UpdateChecker.stripVariant(version) else version.trim()

    private fun companionAboveCap(inputs: SoftwareUpdateInputs): Boolean {
        val installed = inputs.installedVersion?.takeIf { it.isNotBlank() } ?: return false
        return inputs.component == SoftwareComponent.COMPANION && !inputs.externallyManaged &&
            CompanionInstaller.exceedsCap(installed, inputs.cap)
    }

    /** The latest version Home Assistant is shown, or null when no truthful value exists. */
    fun latestVersion(inputs: SoftwareUpdateInputs): String? =
        if (inputs.externallyManaged) null else inputs.target?.version

    /**
     * A Companion entity exists only while a Companion app is installed. A panel without one is not
     * missing an update, so Home Assistant is shown nothing rather than a permanent "not installed"
     * offer under every panel; the Install page still adds the app.
     */
    fun companionAbsent(inputs: SoftwareUpdateInputs): Boolean =
        inputs.component == SoftwareComponent.COMPANION && inputs.installedVersion == null

    /** The entity is not announced at all: withheld for Panel Assistant, or no Companion to report on. */
    fun withheld(inputs: SoftwareUpdateInputs): Boolean = inputs.suppressed || companionAbsent(inputs)

    /** An install command is advertised only when this panel can actually carry one out. */
    fun installable(inputs: SoftwareUpdateInputs): Boolean =
        !withheld(inputs) && !inputs.externallyManaged && inputs.canInstall && inputs.target != null

    fun shape(inputs: SoftwareUpdateInputs): SoftwareDiscoveryShape =
        if (withheld(inputs)) SoftwareDiscoveryShape(announced = false, installable = false, hasLatest = false)
        else SoftwareDiscoveryShape(
            announced = true,
            installable = installable(inputs),
            hasLatest = latestVersion(inputs) != null,
        )

    /**
     * The discovery work needed to move Home Assistant from [previous] to [current]. [announcing] is a
     * connect or Home Assistant birth, where non-retained discovery must be sent again regardless.
     */
    fun transition(
        previous: SoftwareDiscoveryShape?,
        current: SoftwareDiscoveryShape,
        announcing: Boolean,
    ): SoftwareDiscoveryStep = when {
        !current.announced ->
            if (announcing || previous?.announced != false) SoftwareDiscoveryStep.WITHDRAW else SoftwareDiscoveryStep.NONE
        previous?.announced == true && previous.hasLatest && !current.hasLatest ->
            SoftwareDiscoveryStep.CLEAR_THEN_ANNOUNCE
        announcing || previous != current -> SoftwareDiscoveryStep.ANNOUNCE
        else -> SoftwareDiscoveryStep.NONE
    }

    /**
     * The exact MQTT publications, in order, that carry out [step] for [inputs]. The state channel's own
     * convergence follows them: the retained state for an announced entity, an empty payload for a
     * withdrawn one. Live discovery is non-retained like every other entity; tombstones are retained.
     */
    fun discoveryPlan(
        panel: String,
        inputs: SoftwareUpdateInputs,
        step: SoftwareDiscoveryStep,
        availability: String,
        device: String,
    ): List<SoftwarePublication> {
        val component = inputs.component
        val config = configTopic(panel, component)
        fun announce() = SoftwarePublication(
            config,
            discoveryJson(panel, component, installable(inputs), availability, device),
            retain = false,
        )
        val tombstone = SoftwarePublication(config, "", retain = true)
        return when (step) {
            SoftwareDiscoveryStep.NONE -> emptyList()
            SoftwareDiscoveryStep.ANNOUNCE -> listOf(announce())
            // The new retained state must be on the broker before the recreated entity subscribes, or
            // the entity would restore the very version this step exists to clear.
            SoftwareDiscoveryStep.CLEAR_THEN_ANNOUNCE -> listOf(
                SoftwarePublication(stateTopic(panel, component), stateJson(inputs), retain = true),
                tombstone,
                announce(),
            )
            SoftwareDiscoveryStep.WITHDRAW -> listOf(tombstone)
        }
    }

    fun discoveryJson(
        panel: String,
        component: SoftwareComponent,
        installable: Boolean,
        availability: String,
        device: String,
    ): String {
        val objectId = objectId(panel, component)
        val command = if (installable) {
            """"command_topic":"${commandTopic(panel, component)}","payload_install":"$PAYLOAD_INSTALL","""
        } else ""
        return """{"default_entity_id":"update.$objectId","name":"${component.entityName}",""" +
            """"object_id":"$objectId","unique_id":"${uniqueId(panel, component)}",""" +
            """"state_topic":"${stateTopic(panel, component)}",$command""" +
            """"entity_category":"config",$availability,$device}"""
    }

    private fun installedPresentation(inputs: SoftwareUpdateInputs): String {
        val installed = inputs.installedVersion ?: return NOT_INSTALLED
        if (installed.isBlank()) return "unknown"
        val version = normalized(inputs.component, installed)
        return if (companionAboveCap(inputs)) UNSUPPORTED_PREFIX + version else version
    }

    private fun title(inputs: SoftwareUpdateInputs): String = when {
        inputs.component == SoftwareComponent.PANELD -> "ha-paneld"
        inputs.installedVersion == null -> "HA Companion"
        inputs.externallyManaged -> "HA Companion (full)"
        else -> "HA Companion (minimal)"
    }

    private fun channelLabel(channel: String) = if (channel == "prerelease") "Pre-release" else "Stable"

    private fun summary(inputs: SoftwareUpdateInputs): String {
        if (inputs.externallyManaged) {
            return "Managed by Google Play. ha-paneld does not update the full Companion app."
        }
        val parts = mutableListOf("${channelLabel(inputs.channel)} channel.")
        val target = inputs.target
        val installed = inputs.installedVersion?.takeIf { it.isNotBlank() }
        when {
            target == null -> parts += "The latest release has not been checked yet."
            companionAboveCap(inputs) -> parts +=
                "Installed ${normalized(inputs.component, installed!!)} is above this panel's ${inputs.cap} safety cap; installing moves it to ${target.version}."
            else -> {
                if (target.capped) parts += "This panel's safety cap is ${inputs.cap}; the newest release is ${target.newestVersion}."
                if (installed != null && UpdateChecker.compareVersions(
                        normalized(inputs.component, target.version),
                        normalized(inputs.component, installed),
                    )?.let { it < 0 } == true
                ) parts += "This build is newer than the latest release."
            }
        }
        if (target != null && !inputs.canInstall) parts += "This panel has no install route; update it manually."
        return parts.joinToString(" ").take(MAX_SUMMARY_CHARS)
    }

    /** Retained JSON for the state topic. Omitted keys keep Home Assistant's previous value. */
    fun stateJson(inputs: SoftwareUpdateInputs): String = buildString {
        append("{\"installed_version\":").append(Json.str(installedPresentation(inputs)))
        latestVersion(inputs)?.let { append(",\"latest_version\":").append(Json.str(it)) }
        append(",\"title\":").append(Json.str(title(inputs)))
        append(",\"release_summary\":").append(Json.str(summary(inputs)))
        if (!inputs.externallyManaged) {
            inputs.target?.releaseUrl?.takeIf { it.startsWith("https://") }?.let {
                append(",\"release_url\":").append(Json.str(it))
            }
        }
        append(",\"in_progress\":").append(inputs.installing)
        append('}')
    }

    /**
     * Strict admission for an `install` command: a known, installable component; a target resolved from
     * the signed release catalog under the current policy and carrying a valid tag; and strictly newer
     * than what is installed. The one deliberate exception is a Companion already above this panel's
     * safety cap, where installing is the explicit remediation the entity advertised. The installer then
     * verifies the pinned signer and database compatibility before anything is replaced.
     */
    fun admit(inputs: SoftwareUpdateInputs): SoftwareInstallAdmission {
        fun refused(reason: String) = SoftwareInstallAdmission.Refused(reason)
        if (inputs.externallyManaged) return refused("externally-managed")
        if (!inputs.canInstall) return refused("no-install-route")
        if (inputs.installing) return refused("in-progress")
        val target = inputs.target ?: return refused("unresolved")
        if (!ReleaseCatalog.validTag(target.tag)) return refused("invalid-tag")
        if (inputs.component == SoftwareComponent.COMPANION &&
            !CompanionInstaller.withinCap(target.version, inputs.cap)
        ) return refused("exceeds-cap")
        // Neither component installs from nothing here: the Companion entity is withheld while the app
        // is absent, and a first Companion install stays on the Install page.
        val installed = inputs.installedVersion ?: return refused("not-installed")
        val comparison = UpdateChecker.compareVersions(
            normalized(inputs.component, target.version),
            normalized(inputs.component, installed),
        ) ?: return refused("uncomparable")
        return when {
            comparison > 0 -> SoftwareInstallAdmission.Admitted(target.tag)
            companionAboveCap(inputs) -> SoftwareInstallAdmission.Admitted(target.tag, downgrade = true)
            comparison == 0 -> refused("up-to-date")
            else -> refused("downgrade")
        }
    }
}
