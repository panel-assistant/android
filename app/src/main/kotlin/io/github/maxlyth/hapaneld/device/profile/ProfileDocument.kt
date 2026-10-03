package io.github.maxlyth.hapaneld.device.profile

import io.github.maxlyth.hapaneld.hardware.TransferCurve

/**
 * Parsed form of the version-2 profile format. It contains data and named, core-owned strategies only:
 * imported profiles cannot execute code, name arbitrary classes, use regular expressions, or add drivers.
 */
data class ProfileDocument(
    val schema: Int,
    val id: String,
    val version: String,
    val displayName: String,
    val socClass: String,
    val metadata: ProfileProvenance,
    val requires: ProfileRequirements,
    val match: ProfileMatch,
    val platform: ProfilePlatform,
    val hardware: ProfileHardware,
    val sensors: ProfileSensors,
    val identity: ProfileIdentity,
    val input: ProfileInput,
    val cpu: ProfileCpu,
    val display: ProfileDisplay,
    val provisioning: ProfileProvisioning,
    val soc: ProfileSoc? = null,
)

data class ProfileProvenance(
    val author: String,
    val source: String? = null,
    val license: String,
    val maturity: ProfileMaturity,
    val testedFirmware: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
    val links: List<ProfileLink> = emptyList(),
)

/** Offline, author-evidenced SoC facts. These values are descriptive and never runtime-probed. */
data class ProfileSoc(
    val model: String,
    val introducedYear: Int? = null,
    val cpuCores: List<ProfileCpuCoreCluster> = emptyList(),
) {
    fun displayText(): String = buildList {
        add(model)
        cpuCores.takeIf { it.isNotEmpty() }?.let { clusters ->
            add(clusters.joinToString(" + ") { "${it.count}× ${it.architecture}" })
        }
        introducedYear?.let { add("introduced $it") }
    }.joinToString(" · ")
}

data class ProfileCpuCoreCluster(
    val architecture: String,
    val count: Int,
)

/** Display-only reference. Profile links are never fetched by the service or used for provisioning. */
data class ProfileLink(
    val label: String,
    val url: String,
)

data class ProfileRequirements(
    val minCoreVersion: String? = null,
    val drivers: Set<String> = emptySet(),
)

data class ProfileMatch(
    val priority: Int = 0,
    val fallback: Boolean = false,
    /** OR across groups, AND across predicates within one group. */
    val any: List<ProfileMatchGroup> = emptyList(),
)

data class ProfileMatchGroup(
    /** Specificity of this OR branch; exact product identities must outrank broad SoC fallbacks. */
    val priority: Int,
    val all: List<ProfilePredicate>,
)

enum class ProfileFact(val yamlName: String) {
    MODEL("model"),
    DEVICE("device"),
    PRODUCT_VERSION("product_version"),
}

enum class ProfileMatchOp(val yamlName: String) {
    EQUALS("equals"),
    STARTS_WITH("starts_with"),
    CONTAINS("contains"),
}

data class ProfilePredicate(
    val field: ProfileFact,
    val op: ProfileMatchOp,
    val values: List<String>,
)

data class ProfilePlatform(
    val suForm: String,
    val appCanSu: Boolean,
    val hasRecents: Boolean = true,
    val hasNativeNavbar: Boolean? = null,
)

data class ProfileHardware(
    val led: ProfileLed,
    val screenOff: String,
    val hasButtonBacklight: Boolean = false,
    val zigbeeGatewayDir: String? = null,
    val relayBase: String? = null,
    val relayBaseFallbacks: List<String> = emptyList(),
    val buttonLedGpioBase: Int? = null,
    val touchClickGain: Float? = null,
    /**
     * Whether the board is declared to carry a usable camera, as three states rather than two.
     *
     * `true` forces the capability on, `false` suppresses it even where Android enumerates a camera,
     * and null leaves the answer to runtime enumeration. Null is the common case and the reason this is
     * not a plain boolean: an owner whose panel has a camera Android can already see should not have to
     * author a profile before the panel will offer it.
     */
    val cameraDeclared: Boolean? = null,
    // Whether the panel has a usable microphone. Declared independently of the camera because some
    // hardware has one without the other, and unlike the camera it is not enumerable.
    val hasMicrophone: Boolean = false,
    /**
     * How far the camera lens centre sits above the top of the active display area, in screen pixels.
     *
     * A bezel measurement, so it belongs to the board rather than to the app: the camera-in-use light is
     * drawn as an arc centred on the lens, and without this it can only be centred on a guess. Measured
     * from photographs of the two camera panels on 2026-08-30 — 63 px on the TPA10 and 43 px on the
     * WF1589T, about 7.0 mm and 5.2 mm — which is why it cannot be one shared constant.
     *
     * Null means unmeasured; the indicator then falls back to its own default rather than refusing.
     */
    val cameraLensOffsetPx: Int? = null,
    /** Backlight transfer curve; null (the key absent) is the identity passthrough. */
    val backlight: ProfileBacklight? = null,
    /** Key-backlight transfer curve (`hardware.button_backlight`); null is the identity passthrough. */
    val buttonBacklight: ProfileLightCurve? = null,
)

/**
 * One light's transfer curve declaration, shared by the backlight, the LED and the key backlight.
 * [transfer] is `identity`, `perceptual`, `gamma` (with [gamma]) or `points` (with [points], `[request,
 * hardware]` pairs on 0..255 from `[0, 0]` to `[255, 255]`); [floor] (0..255) is the hardware level the
 * lowest non-zero request lands on, and is refused with `points`, which encode their own. Built by
 * [toTransferCurve]; the validator reports its refusal.
 */
data class ProfileLightCurve(
    val transfer: String = "identity",
    val gamma: Double? = null,
    val points: List<Pair<Int, Int>>? = null,
    val floor: Int? = null,
) {
    fun toTransferCurve(): TransferCurve = TransferCurve.from(transfer, gamma, points, floor)
}

/**
 * `hardware.backlight`: the backlight's [curve] and the [route] it is applied through. `setting` writes the
 * curved value into Android's brightness setting, for firmware that pushes that setting to the node itself
 * (NSPanel 86); `node` writes it to the node, for panels where ha-paneld is the node's only writer (the
 * TPA10 helper route). A declared curve must name its route; the identity curve needs none.
 */
data class ProfileBacklight(
    val curve: ProfileLightCurve,
    val route: String? = null,
)

/** `hardware.led`: the LED [mechanism] and its curve. [transfer] also accepts the ioctl-only
 *  `rk3576-four-bit` stub, which takes no [gamma], [points] or [floor]. */
data class ProfileLed(
    val mechanism: String,
    val transfer: String = "identity",
    val gamma: Double? = null,
    val points: List<Pair<Int, Int>>? = null,
    val floor: Int? = null,
) {
    val curve: ProfileLightCurve get() = ProfileLightCurve(transfer, gamma, points, floor)
}

data class ProfileSensors(
    val proximityTechnology: String? = null,
    val proximityCalibration: ProfileProximityCalibration? = null,
    val proximityGpio: Int? = null,
    val lightTechnology: String? = null,
    val cht8305: Boolean = false,
    /** Board carries an Evisionics VI530x time-of-flight sensor the helper can start and read. It is a
     *  distinct fact from [proximityTechnology], which only names the technology for display: this one
     *  says the daemon route exists, because the driver reports nothing until it is started. */
    val vi530x: Boolean = false,
    val roomTempOffsetC: Float = 0f,
)

/** Explicit, versioned hardware observations; never inferred from advertised sensor range or model.
 * Runtime acquisition must corroborate this baseline before it may actuate a wake. */
data class ProfileProximityCalibration(
    val revision: Int,
    val mode: String,
    val clearRaw: Float,
    val nearRaw: Float,
    /** Public evidence reference or description, not a runtime trust assertion. */
    val verification: String,
    val nearEnter: Float = 0.65f,
    val clearExit: Float = 0.30f,
    val debounceMs: Int = 150,
    val clearArmMs: Int = 700,
    val minimumNearMs: Int = 200,
    val maximumNearMs: Int = 4000,
    val cooldownMs: Int = 1000,
    /** Format 1 retains its original single-wave interpretation; format 2 separates presence and wave. */
    val formatVersion: Int = 1,
    val wave: ProfileWaveCalibration? = null,
    /** False only for a verified wave-only source; no ordinary-presence publication. */
    val presenceSupported: Boolean = true,
)

/** Optional independently measured and validated wave capability; absence in format 2 means presence only. */
data class ProfileWaveCalibration(
    val pattern: String,
    val clearRaw: Float,
    val nearRaw: Float,
    val nearEnter: Float = 0.65f,
    val clearExit: Float = 0.30f,
    val debounceMs: Int = 150,
    val clearArmMs: Int = 700,
    val minimumNearMs: Int = 200,
    val maximumNearMs: Int = 4000,
    val cooldownMs: Int = 1000,
    val maxInterWaveGapMs: Int = 1800,
)

data class ProfileIdentity(
    val manufacturer: String? = null,
    val model: String? = null,
    val modelLabelStrategy: String = "display-name",
)

data class ProfileInput(val evdevButtons: List<ProfileEvdevButton> = emptyList())

data class ProfileEvdevButton(
    val node: String,
    val code: Int,
    val grab: Boolean,
    val eventType: String,
    val sw: Boolean = false,
)

data class ProfileCpu(val governors: Map<String, String>? = null)

/** Physical display facts. Nothing here is a rendering density: Android's logical DPI is observed live,
 *  and the recommendation lives under `provisioning.display`. */
data class ProfileDisplay(
    /** Legacy single approximate density. Kept verbatim so existing revisions keep their content hash;
     *  it resolves as `approximate` geometry and may not be combined with [geometry]. */
    val physicalPpi: Int? = null,
    val geometry: List<ProfileDisplayGeometry> = emptyList(),
)

/**
 * One panel variant's physical geometry. It applies only when the live physical mode has exactly these
 * pixels (either orientation) and, when prefixes are listed, the product version starts with one of them.
 * The active size is either a diagonal or width and height, never both.
 */
data class ProfileDisplayGeometry(
    val variant: String? = null,
    val productVersionPrefixes: List<String> = emptyList(),
    val widthPx: Int,
    val heightPx: Int,
    val activeDiagonalIn: Float? = null,
    val activeWidthMm: Float? = null,
    val activeHeightMm: Float? = null,
    /** The firmware's factory reset logical DPI for this variant; a rendering fact, never a physical one. */
    val factoryBaseDpi: Int? = null,
    val evidence: DisplayGeometryEvidence,
    val evidenceNote: String? = null,
)

/** Where a physical size came from. `approximate` marks values that must not be read as a measurement. */
enum class DisplayGeometryEvidence(val yamlName: String) {
    MEASURED("measured"),
    MODULE("module"),
    SPECIFICATION("specification"),
    APPROXIMATE("approximate"),
}

sealed interface ProfileDensity {
    data class Fixed(val value: Int) : ProfileDensity
    data class Strategy(val id: String) : ProfileDensity
}

data class ProfileProvisioning(
    val access: ProfileProvisioningAccess = ProfileProvisioningAccess(),
    val software: ProfileProvisioningSoftware = ProfileProvisioningSoftware(),
    val display: ProfileProvisioningDisplay = ProfileProvisioningDisplay(),
    val packages: List<ProfilePackageIntent> = emptyList(),
    val recipes: List<ProfileRecipeSelection> = emptyList(),
)

data class ProfileProvisioningAccess(
    val shizuku: ShizukuRecommendation = ShizukuRecommendation.NONE,
)

data class ProfileProvisioningSoftware(
    val webView: ProfileWebViewProvisioning? = null,
    val companion: ProfileCompanionProvisioning? = null,
)

data class ProfileWebViewProvisioning(
    /** Core-owned artifact id; profile files never define APK URLs or signer trust roots. */
    val artifact: String,
)

data class ProfileCompanionProvisioning(
    val maxVersion: String,
)

data class ProfileProvisioningDisplay(
    val density: ProfileDensity? = null,
    val fontScale: Float? = null,
)

data class ProfilePackageIntent(
    val packageName: String,
    val desiredState: ProfilePackageDesiredState,
    val importance: ProfileProvisioningImportance,
    val tags: List<String> = emptyList(),
    val note: String = "",
)

enum class ProfilePackageDesiredState(val yamlName: String) {
    DISABLED("disabled"),
}

enum class ProfileProvisioningImportance(val yamlName: String) {
    RECOMMENDED("recommended"),
    OPTIONAL("optional"),
}

data class ProfileRecipeSelection(val id: String)
