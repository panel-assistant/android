package io.panelassistant.android.device.profile

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.common.FlowStyle
import org.snakeyaml.engine.v2.events.CollectionEndEvent
import org.snakeyaml.engine.v2.events.CollectionStartEvent
import org.snakeyaml.engine.v2.events.DocumentStartEvent
import org.snakeyaml.engine.v2.schema.JsonSchema

internal data class ProfileParseResult(
    val document: ProfileDocument?,
    val contentSha256: String,
    val sourceBytes: Int,
    val issues: List<ProfileIssue>,
)

/** Strict YAML codec. The generic object tree is audited before any schema conversion. */
internal object ProfileYaml {
    private val loadSettings = LoadSettings.builder()
            .setLabel("panel profile")
            .setSchema(JsonSchema())
            .setAllowDuplicateKeys(false)
            .setAllowRecursiveKeys(false)
            .setMaxAliasesForCollections(0)
            .setCodePointLimit(ProfileMetadata.MAX_BYTES)
            .build()
    private val parse = Parse(loadSettings)
    private val load = Load(loadSettings)
    private val dump = Dump(
        DumpSettings.builder()
            .setSchema(JsonSchema())
            .setDefaultFlowStyle(FlowStyle.BLOCK)
            .setIndent(2)
            .setIndicatorIndent(2)
            .setIndentWithIndicator(true)
            .setSplitLines(false)
            .setWidth(120)
            .build(),
    )

    fun parse(raw: String): ProfileParseResult {
        // Encoded once and reused for the size bound, hash, and feature-cost byte count.
        val encoded = raw.toByteArray(StandardCharsets.UTF_8)
        val hash = sha256(encoded)
        if (encoded.size > ProfileMetadata.MAX_BYTES) {
            return ProfileParseResult(
                null,
                hash,
                encoded.size,
                listOf(error("$", "Profile exceeds ${ProfileMetadata.MAX_BYTES} bytes.", "profile-source-byte-limit", mapOf("max" to ProfileMetadata.MAX_BYTES.toString()))),
            )
        }
        preflight(raw)?.let { issue -> return ProfileParseResult(null, hash, encoded.size, listOf(issue)) }
        val root = try {
            val values = load.loadAllFromString(raw).iterator()
            if (!values.hasNext()) return ProfileParseResult(null, hash, encoded.size, listOf(error("$", "Profile is empty.", "profile-source-empty")))
            val first = values.next()
            if (values.hasNext()) return ProfileParseResult(null, hash, encoded.size, listOf(error("$", "Exactly one YAML document is allowed.", "yaml-single-document-required")))
            first
        } catch (failure: RuntimeException) {
            // SnakeYAML owns this bounded diagnostic text. It is deliberately compatibility-only:
            // presentation codes describe deterministic core outcomes, never parser exception prose.
            return ProfileParseResult(
                null,
                hash,
                encoded.size,
                listOf(error("$", "Invalid YAML: ${safeMessage(failure)}")),
            )
        } catch (_: StackOverflowError) {
            return ProfileParseResult(null, hash, encoded.size, listOf(error("$", "YAML nesting is too deep.", "yaml-nesting-too-deep")))
        }
        val issues = mutableListOf<ProfileIssue>()
        audit(root, "$", 0, issues)
        if (issues.any { it.severity == ProfileIssueSeverity.ERROR }) {
            return ProfileParseResult(null, hash, encoded.size, issues)
        }
        val reader = SchemaReader(issues)
        val map = reader.map(root, "$", ROOT_KEYS)
        val document = if (map == null) null else reader.document(map)
        return ProfileParseResult(
            document = document.takeIf { issues.none { issue -> issue.severity == ProfileIssueSeverity.ERROR } },
            contentSha256 = hash,
            sourceBytes = encoded.size,
            issues = issues,
        )
    }

    fun serialize(document: ProfileDocument): String = dump.dumpToString(document.toYamlMap())

    fun sha256(raw: String): String = sha256(raw.toByteArray(StandardCharsets.UTF_8))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun audit(value: Any?, path: String, depth: Int, issues: MutableList<ProfileIssue>) {
        if (depth > ProfileMetadata.MAX_DEPTH) {
            issues += error(path, "Nesting exceeds ${ProfileMetadata.MAX_DEPTH} levels.", "yaml-nesting-depth-limit", mapOf("max" to ProfileMetadata.MAX_DEPTH.toString()))
            return
        }
        when (value) {
            null, is Boolean, is Number -> Unit
            is String -> if (value.length > ProfileMetadata.MAX_STRING_LENGTH) {
                issues += error(path, "String exceeds ${ProfileMetadata.MAX_STRING_LENGTH} characters.", "yaml-string-length-limit", mapOf("max" to ProfileMetadata.MAX_STRING_LENGTH.toString()))
            }
            is Map<*, *> -> {
                if (value.size > ProfileMetadata.MAX_COLLECTION_SIZE) {
                    issues += error(path, "Map exceeds ${ProfileMetadata.MAX_COLLECTION_SIZE} entries.", "yaml-map-entry-limit", mapOf("max" to ProfileMetadata.MAX_COLLECTION_SIZE.toString()))
                }
                value.forEach { (key, child) ->
                    if (key !is String) issues += error(path, "Every mapping key must be a string.", "yaml-mapping-key-string-required")
                    audit(child, if (key is String) "$path.$key" else path, depth + 1, issues)
                }
            }
            is List<*> -> {
                if (value.size > ProfileMetadata.MAX_COLLECTION_SIZE) {
                    issues += error(path, "List exceeds ${ProfileMetadata.MAX_COLLECTION_SIZE} entries.", "yaml-list-entry-limit", mapOf("max" to ProfileMetadata.MAX_COLLECTION_SIZE.toString()))
                }
                value.forEachIndexed { index, child -> audit(child, "$path[$index]", depth + 1, issues) }
            }
            else -> issues += error(
                path,
                "Unsupported YAML value type ${value::class.simpleName}.",
                "unsupported-yaml-type",
                mapOf("type" to value::class.simpleName.orEmpty()),
            )
        }
    }

    private fun preflight(raw: String): ProfileIssue? {
        return try {
            var depth = 0
            var documents = 0
            var events = 0
            for (event in parse.parseString(raw)) {
                events++
                if (events > MAX_EVENTS) return error("$", "YAML contains too many parser events.", "yaml-parser-event-limit")
                when (event) {
                    is DocumentStartEvent -> {
                        documents++
                        if (documents > 1) return error("$", "Exactly one YAML document is allowed.", "yaml-single-document-required")
                    }
                    is CollectionStartEvent -> {
                        depth++
                        if (depth > ProfileMetadata.MAX_DEPTH) return error("$", "Nesting exceeds ${ProfileMetadata.MAX_DEPTH} levels.", "yaml-nesting-depth-limit", mapOf("max" to ProfileMetadata.MAX_DEPTH.toString()))
                    }
                    is CollectionEndEvent -> depth--
                }
            }
            null
        } catch (failure: RuntimeException) {
            // Preserve the same arbitrary SnakeYAML boundary as the full parse path above.
            error("$", "Invalid YAML: ${safeMessage(failure)}")
        } catch (_: StackOverflowError) {
            error("$", "YAML nesting is too deep.", "yaml-nesting-too-deep")
        }
    }

    private fun safeMessage(failure: Throwable): String =
        failure.message?.lineSequence()?.firstOrNull()?.take(240) ?: failure::class.simpleName.orEmpty()

    private val ROOT_KEYS = setOf(
        "schema", "id", "version", "display_name", "soc_class", "soc", "metadata", "requires", "match", "platform", "hardware",
        "sensors", "identity", "input", "cpu", "display", "provisioning",
    )

    private const val MAX_EVENTS = 20_000
}

/** Accepted only so immutable schema-2 revisions written by older cores remain loadable. */
private val LEGACY_PROXIMITY_CLASSIFIER_KEYS = setOf(
    "proximity_near_below",
    "proximity_near_raw",
    "proximity_far_raw",
    "proximity_graded_strategy",
)

private class SchemaReader(private val issues: MutableList<ProfileIssue>) {
    fun document(root: Map<String, Any?>): ProfileDocument {
        val metadata = map(root["metadata"], "metadata", setOf(
            "author", "source", "license", "maturity", "tested_firmware", "limitations", "links",
        ), required = true).orEmpty()
        val soc = map(root["soc"], "soc", setOf("model", "introduced_year", "cpu_cores"))
        val requires = map(root["requires"], "requires", setOf("min_core_version", "drivers"), required = true).orEmpty()
        val match = map(root["match"], "match", setOf("priority", "fallback", "any"), required = true).orEmpty()
        val platform = map(root["platform"], "platform", setOf("su_form", "app_can_su", "has_recents", "has_native_navbar", "launcher"), required = true).orEmpty()
        val launcher = map(platform["launcher"], "platform.launcher", setOf("vendor_home_packages")).orEmpty()
        val hardware = map(root["hardware"], "hardware", setOf(
            "led", "screen_off", "has_button_backlight", "zigbee_gateway_dir", "relay_base",
            "relay_base_fallbacks", "button_led_gpio_base", "touch_click_gain", "camera", "microphone", "speaker",
            "camera_lens_offset_px", "backlight", "button_backlight",
        ), required = true).orEmpty()
        val led = map(hardware["led"], "hardware.led", setOf("mechanism", "transfer", "gamma", "points", "floor"), required = true).orEmpty()
        val sensors = map(
            root["sensors"],
            "sensors",
            setOf(
                "proximity_technology", "proximity_gpio", "light_technology", "cht8305", "vi530x", "room_temp_offset_c", "proximity_calibration",
            ) + LEGACY_PROXIMITY_CLASSIFIER_KEYS,
        ).orEmpty()
        val identity = map(root["identity"], "identity", setOf("manufacturer", "model", "model_label_strategy")).orEmpty()
        val input = map(root["input"], "input", setOf("evdev_buttons")).orEmpty()
        val cpu = map(root["cpu"], "cpu", setOf("governors")).orEmpty()
        val display = map(root["display"], "display", setOf("physical_ppi", "geometry")).orEmpty()
        val provisioning = map(
            root["provisioning"],
            "provisioning",
            setOf("access", "software", "display", "packages", "recipes"),
            required = true,
        ).orEmpty()
        val provisioningAccess = map(
            provisioning["access"],
            "provisioning.access",
            setOf("shizuku"),
        ).orEmpty()
        val provisioningSoftware = map(
            provisioning["software"],
            "provisioning.software",
            setOf("webview", "companion"),
        ).orEmpty()
        val provisioningWebView = map(
            provisioningSoftware["webview"],
            "provisioning.software.webview",
            setOf("artifact"),
        )
        val provisioningCompanion = map(
            provisioningSoftware["companion"],
            "provisioning.software.companion",
            setOf("max_version"),
        )
        val provisioningDisplay = map(
            provisioning["display"],
            "provisioning.display",
            setOf("density", "font_scale"),
        ).orEmpty()
        val schema = integer(root, "schema", "$", required = true) ?: 0
        if (schema != ProfileMetadata.SCHEMA) {
            issues += error(
                "schema",
                "Unsupported schema $schema; expected ${ProfileMetadata.SCHEMA}.",
                "unsupported-schema",
                mapOf("actual" to schema.toString(), "expected" to ProfileMetadata.SCHEMA.toString()),
            )
        }
        return ProfileDocument(
            schema = schema,
            id = string(root, "id", "$", required = true).orEmpty(),
            version = string(root, "version", "$", required = true).orEmpty(),
            displayName = string(root, "display_name", "$", required = true).orEmpty(),
            socClass = string(root, "soc_class", "$", required = true).orEmpty(),
            soc = soc?.let {
                ProfileSoc(
                    model = string(it, "model", "soc", required = true).orEmpty(),
                    introducedYear = integer(it, "introduced_year", "soc"),
                    cpuCores = cpuCoreClusters(it["cpu_cores"]),
                )
            },
            metadata = ProfileProvenance(
                author = string(metadata, "author", "metadata", required = true).orEmpty(),
                source = string(metadata, "source", "metadata"),
                license = string(metadata, "license", "metadata", required = true).orEmpty(),
                maturity = enum(metadata, "maturity", "metadata", ProfileMaturity.entries, null) { it.name.lowercase() }
                    ?: ProfileMaturity.DRAFT,
                testedFirmware = stringList(metadata["tested_firmware"], "metadata.tested_firmware"),
                limitations = stringList(metadata["limitations"], "metadata.limitations"),
                links = profileLinks(metadata["links"]),
            ),
            requires = ProfileRequirements(
                minCoreVersion = string(requires, "min_core_version", "requires"),
                drivers = stringList(requires["drivers"], "requires.drivers", required = true).toSet(),
            ),
            match = ProfileMatch(
                priority = integer(match, "priority", "match", required = true) ?: 0,
                fallback = boolean(match, "fallback", "match", required = true) ?: false,
                any = matchGroups(match["any"]),
            ),
            platform = ProfilePlatform(
                suForm = string(platform, "su_form", "platform", required = true).orEmpty(),
                appCanSu = boolean(platform, "app_can_su", "platform", required = true) ?: false,
                hasRecents = boolean(platform, "has_recents", "platform") ?: true,
                hasNativeNavbar = boolean(platform, "has_native_navbar", "platform"),
                launcher = ProfileLauncher(
                    vendorHomePackages = stringList(launcher["vendor_home_packages"], "platform.launcher.vendor_home_packages"),
                ),
            ),
            hardware = ProfileHardware(
                led = lightCurve(led, "hardware.led", transferRequired = false).let { curve ->
                    ProfileLed(
                        mechanism = string(led, "mechanism", "hardware.led", required = true).orEmpty(),
                        transfer = curve.transfer,
                        gamma = curve.gamma,
                        points = curve.points,
                        floor = curve.floor,
                    )
                },
                screenOff = string(hardware, "screen_off", "hardware", required = true).orEmpty(),
                hasButtonBacklight = boolean(hardware, "has_button_backlight", "hardware") ?: false,
                zigbeeGatewayDir = string(hardware, "zigbee_gateway_dir", "hardware"),
                relayBase = string(hardware, "relay_base", "hardware"),
                relayBaseFallbacks = stringList(hardware["relay_base_fallbacks"], "hardware.relay_base_fallbacks"),
                buttonLedGpioBase = integer(hardware, "button_led_gpio_base", "hardware"),
                touchClickGain = float(hardware, "touch_click_gain", "hardware"),
                cameraDeclared = boolean(hardware, "camera", "hardware"),
                hasMicrophone = boolean(hardware, "microphone", "hardware") ?: false,
                hasSpeaker = boolean(hardware, "speaker", "hardware") ?: true,
                cameraLensOffsetPx = integer(hardware, "camera_lens_offset_px", "hardware"),
                backlight = backlight(hardware["backlight"]),
                buttonBacklight = map(hardware["button_backlight"], "hardware.button_backlight", CURVE_KEYS)
                    ?.let { lightCurve(it, "hardware.button_backlight", transferRequired = true) },
            ),
            sensors = ProfileSensors(
                proximityTechnology = string(sensors, "proximity_technology", "sensors"),
                proximityCalibration = proximityCalibration(sensors["proximity_calibration"]),
                proximityGpio = integer(sensors, "proximity_gpio", "sensors"),
                lightTechnology = string(sensors, "light_technology", "sensors"),
                cht8305 = boolean(sensors, "cht8305", "sensors") ?: false,
                vi530x = boolean(sensors, "vi530x", "sensors") ?: false,
                roomTempOffsetC = float(sensors, "room_temp_offset_c", "sensors") ?: 0f,
            ),
            identity = ProfileIdentity(
                manufacturer = string(identity, "manufacturer", "identity"),
                model = string(identity, "model", "identity"),
                modelLabelStrategy = string(identity, "model_label_strategy", "identity") ?: "display-name",
            ),
            input = ProfileInput(evdevButtons(input["evdev_buttons"])),
            cpu = ProfileCpu(governors(cpu["governors"])),
            display = ProfileDisplay(
                physicalPpi = integer(display, "physical_ppi", "display"),
                geometry = displayGeometry(display["geometry"]),
            ),
            provisioning = ProfileProvisioning(
                access = ProfileProvisioningAccess(
                    shizuku = enum(
                        provisioningAccess,
                        "shizuku",
                        "provisioning.access",
                        ShizukuRecommendation.entries,
                        "none",
                    ) { it.name.lowercase() } ?: ShizukuRecommendation.NONE,
                ),
                software = ProfileProvisioningSoftware(
                    webView = provisioningWebView?.let {
                        ProfileWebViewProvisioning(
                            artifact = string(
                                it,
                                "artifact",
                                "provisioning.software.webview",
                                required = true,
                            ).orEmpty(),
                        )
                    },
                    companion = provisioningCompanion?.let {
                        ProfileCompanionProvisioning(
                            maxVersion = string(
                                it,
                                "max_version",
                                "provisioning.software.companion",
                                required = true,
                            ).orEmpty(),
                        )
                    },
                ),
                display = ProfileProvisioningDisplay(
                    density = density(provisioningDisplay["density"]),
                    fontScale = float(provisioningDisplay, "font_scale", "provisioning.display"),
                ),
                packages = packageIntents(provisioning["packages"]),
                recipes = recipeSelections(provisioning["recipes"]),
            ),
        )
    }

    private fun matchGroups(value: Any?): List<ProfileMatchGroup> = list(value, "match.any", required = true).mapIndexed { groupIndex, item ->
        val path = "match.any[$groupIndex]"
        val group = map(item, path, setOf("priority", "all"), required = true).orEmpty()
        val predicates = list(group["all"], "$path.all", required = true).mapIndexed { predicateIndex, predicateValue ->
            val predicatePath = "$path.all[$predicateIndex]"
            val predicate = map(predicateValue, predicatePath, setOf("field", "op", "values"), required = true).orEmpty()
            ProfilePredicate(
                field = enum(predicate, "field", predicatePath, ProfileFact.entries, null) { it.yamlName } ?: ProfileFact.MODEL,
                op = enum(predicate, "op", predicatePath, ProfileMatchOp.entries, null) { it.yamlName } ?: ProfileMatchOp.EQUALS,
                values = stringList(predicate["values"], "$predicatePath.values", required = true),
            )
        }
        ProfileMatchGroup(
            priority = integer(group, "priority", path, required = true) ?: 0,
            all = predicates,
        )
    }

    private fun evdevButtons(value: Any?): List<ProfileEvdevButton> = list(value, "input.evdev_buttons").mapIndexed { index, item ->
        val path = "input.evdev_buttons[$index]"
        val map = map(item, path, setOf("node", "code", "grab", "event_type", "sw"), required = true).orEmpty()
        ProfileEvdevButton(
            node = string(map, "node", path, required = true).orEmpty(),
            code = integer(map, "code", path, required = true) ?: 0,
            grab = boolean(map, "grab", path, required = true) ?: false,
            eventType = string(map, "event_type", path, required = true).orEmpty(),
            sw = boolean(map, "sw", path) ?: false,
        )
    }

    private fun cpuCoreClusters(value: Any?): List<ProfileCpuCoreCluster> =
        list(value, "soc.cpu_cores").mapIndexed { index, item ->
            val path = "soc.cpu_cores[$index]"
            val cluster = map(item, path, setOf("architecture", "count"), required = true).orEmpty()
            ProfileCpuCoreCluster(
                architecture = string(cluster, "architecture", path, required = true).orEmpty(),
                count = integer(cluster, "count", path, required = true) ?: 0,
            )
        }

    private fun profileLinks(value: Any?): List<ProfileLink> =
        list(value, "metadata.links").mapIndexed { index, item ->
            val path = "metadata.links[$index]"
            val link = map(item, path, setOf("label", "url"), required = true).orEmpty()
            ProfileLink(
                label = string(link, "label", path, required = true).orEmpty(),
                url = string(link, "url", path, required = true).orEmpty(),
            )
        }

    private fun packageIntents(value: Any?): List<ProfilePackageIntent> =
        list(value, "provisioning.packages").mapIndexed { index, item ->
        val path = "provisioning.packages[$index]"
        val map = map(
            item,
            path,
            setOf("package", "desired_state", "importance", "tags", "note"),
            required = true,
        ).orEmpty()
        ProfilePackageIntent(
            packageName = string(map, "package", path, required = true).orEmpty(),
            desiredState = enum(
                map,
                "desired_state",
                path,
                ProfilePackageDesiredState.entries,
                null,
                ProfilePackageDesiredState::yamlName,
            ) ?: ProfilePackageDesiredState.DISABLED,
            importance = enum(
                map,
                "importance",
                path,
                ProfileProvisioningImportance.entries,
                null,
                ProfileProvisioningImportance::yamlName,
            ) ?: ProfileProvisioningImportance.OPTIONAL,
            tags = stringList(map["tags"], "$path.tags"),
            note = string(map, "note", path) ?: "",
        )
    }

    private fun displayGeometry(value: Any?): List<ProfileDisplayGeometry> =
        list(value, "display.geometry").mapIndexed { index, item ->
            val path = "display.geometry[$index]"
            val map = map(
                item,
                path,
                setOf(
                    "variant", "product_version_prefixes", "width_px", "height_px", "active_diagonal_in",
                    "active_width_mm", "active_height_mm", "factory_base_dpi", "evidence", "evidence_note",
                ),
                required = true,
            ).orEmpty()
            if (!map.containsKey("evidence")) issues += error("$path.evidence", "Required string is missing.", "required-string")
            ProfileDisplayGeometry(
                variant = string(map, "variant", path),
                productVersionPrefixes = stringList(map["product_version_prefixes"], "$path.product_version_prefixes"),
                widthPx = integer(map, "width_px", path, required = true) ?: 0,
                heightPx = integer(map, "height_px", path, required = true) ?: 0,
                activeDiagonalIn = float(map, "active_diagonal_in", path),
                activeWidthMm = float(map, "active_width_mm", path),
                activeHeightMm = float(map, "active_height_mm", path),
                factoryBaseDpi = integer(map, "factory_base_dpi", path),
                evidence = enum(
                    map,
                    "evidence",
                    path,
                    DisplayGeometryEvidence.entries,
                    null,
                    DisplayGeometryEvidence::yamlName,
                ) ?: DisplayGeometryEvidence.APPROXIMATE,
                evidenceNote = string(map, "evidence_note", path),
            )
        }

    private fun recipeSelections(value: Any?): List<ProfileRecipeSelection> =
        list(value, "provisioning.recipes").mapIndexed { index, item ->
            val path = "provisioning.recipes[$index]"
            val map = map(item, path, setOf("id"), required = true).orEmpty()
            ProfileRecipeSelection(id = string(map, "id", path, required = true).orEmpty())
        }

    private fun governors(value: Any?): Map<String, String>? {
        if (value == null) return null
        val raw = map(value, "cpu.governors", emptySet(), allowAnyKeys = true) ?: return null
        return raw.mapValues { (key, item) ->
            if (item !is String) {
                issues += error("cpu.governors.$key", "Expected a string.", "expected-string")
                ""
            } else item
        }
    }

    private fun density(value: Any?): ProfileDensity? = when (value) {
        null -> null
        is Number -> if (value.toDouble().isFinite() && value.toDouble() % 1.0 == 0.0 && value.toLong() in Int.MIN_VALUE..Int.MAX_VALUE) {
            ProfileDensity.Fixed(value.toInt())
        } else {
            issues += error("provisioning.display.density", "Expected a 32-bit integer or named strategy.", "expected-32-bit-integer-or-strategy")
            null
        }
        is String -> ProfileDensity.Strategy(value)
        else -> {
            issues += error("provisioning.display.density", "Expected an integer or named strategy.", "expected-integer-or-strategy")
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun map(
        value: Any?,
        path: String,
        allowed: Set<String>,
        required: Boolean = false,
        allowAnyKeys: Boolean = false,
    ): Map<String, Any?>? {
        if (value == null) {
            if (required) issues += error(path, "Required mapping is missing.", "required-mapping")
            return null
        }
        if (value !is Map<*, *>) {
            issues += error(path, "Expected a mapping.", "expected-mapping")
            return null
        }
        val result = value as Map<String, Any?>
        if (!allowAnyKeys) result.keys.filterNot { it in allowed }.forEach {
            issues += error(if (path == "$") it else "$path.$it", "Unknown field.", "unknown-field")
        }
        return result
    }

    private fun list(value: Any?, path: String, required: Boolean = false): List<Any?> {
        if (value == null) {
            if (required) issues += error(path, "Required list is missing.", "required-list")
            return emptyList()
        }
        if (value !is List<*>) {
            issues += error(path, "Expected a list.", "expected-list")
            return emptyList()
        }
        return value
    }

    private fun stringList(value: Any?, path: String, required: Boolean = false): List<String> =
        list(value, path, required).mapIndexed { index, item ->
            if (item !is String) {
                issues += error("$path[$index]", "Expected a string.", "expected-string")
                ""
            } else item
        }

    private fun string(map: Map<String, Any?>, key: String, path: String, required: Boolean = false): String? {
        val fullPath = if (path == "$") key else "$path.$key"
        if (!map.containsKey(key) || map[key] == null) {
            if (required) issues += error(fullPath, "Required string is missing.", "required-string")
            return null
        }
        return (map[key] as? String) ?: run {
            issues += error(fullPath, "Expected a string.", "expected-string")
            null
        }
    }

    private fun backlight(value: Any?): ProfileBacklight? {
        val path = "hardware.backlight"
        val fields = map(value, path, CURVE_KEYS + "route") ?: return null
        return ProfileBacklight(
            curve = lightCurve(fields, path, transferRequired = true),
            route = string(fields, "route", path),
        )
    }

    /** The curve fields every light shares; shape rules belong to [ProfileLightCurve.toTransferCurve]. */
    private fun lightCurve(fields: Map<String, Any?>, path: String, transferRequired: Boolean): ProfileLightCurve =
        ProfileLightCurve(
            transfer = string(fields, "transfer", path, required = transferRequired) ?: "identity",
            gamma = float(fields, "gamma", path)?.let { (fields["gamma"] as Number).toDouble() },
            points = if (fields["points"] != null) controlPoints(fields["points"], "$path.points") else null,
            floor = integer(fields, "floor", path),
        )

    /** `[[request, hardware], ...]` integer pairs; shape rules (ends, monotonicity) belong to the curve. */
    private fun controlPoints(value: Any?, path: String): List<Pair<Int, Int>> =
        list(value, path, required = true).mapIndexedNotNull { index, item ->
            val pair = item as? List<*>
            val request = (pair?.getOrNull(0) as? Number)?.takeIf { it.toDouble() % 1.0 == 0.0 }
            val level = (pair?.getOrNull(1) as? Number)?.takeIf { it.toDouble() % 1.0 == 0.0 }
            if (pair?.size != 2 || request == null || level == null) {
                issues += error("$path[$index]", "Expected an integer pair [request, hardware].")
                null
            } else {
                request.toInt() to level.toInt()
            }
        }

    private fun proximityCalibration(value: Any?): ProfileProximityCalibration? {
        val path = "sensors.proximity_calibration"
        val fields = map(value, path, setOf(
            "revision", "mode", "clear_raw", "near_raw", "verification", "near_enter", "clear_exit",
            "debounce_ms", "clear_arm_ms", "minimum_near_ms", "maximum_near_ms", "cooldown_ms", "format_version", "wave", "presence_supported",
        )) ?: return null
        return ProfileProximityCalibration(
            formatVersion = integer(fields, "format_version", path) ?: 1,
            presenceSupported = boolean(fields, "presence_supported", path) ?: true,
            wave = waveCalibration(fields["wave"], "$path.wave"),
            revision = integer(fields, "revision", path, required = true) ?: 0,
            mode = string(fields, "mode", path, required = true).orEmpty(),
            clearRaw = float(fields, "clear_raw", path, required = true) ?: 0f,
            nearRaw = float(fields, "near_raw", path, required = true) ?: 0f,
            verification = string(fields, "verification", path, required = true).orEmpty(),
            nearEnter = float(fields, "near_enter", path) ?: 0.65f,
            clearExit = float(fields, "clear_exit", path) ?: 0.30f,
            debounceMs = integer(fields, "debounce_ms", path) ?: 150,
            clearArmMs = integer(fields, "clear_arm_ms", path) ?: 700,
            minimumNearMs = integer(fields, "minimum_near_ms", path) ?: 200,
            maximumNearMs = integer(fields, "maximum_near_ms", path) ?: 4000,
            cooldownMs = integer(fields, "cooldown_ms", path) ?: 1000,
        )
    }

    private fun waveCalibration(value: Any?, path: String): ProfileWaveCalibration? {
        val fields = map(value, path, setOf(
            "pattern", "clear_raw", "near_raw", "near_enter", "clear_exit", "debounce_ms", "clear_arm_ms",
            "minimum_near_ms", "maximum_near_ms", "cooldown_ms", "max_inter_wave_gap_ms",
        )) ?: return null
        return ProfileWaveCalibration(
            pattern = string(fields, "pattern", path, required = true).orEmpty(),
            clearRaw = float(fields, "clear_raw", path, required = true) ?: 0f,
            nearRaw = float(fields, "near_raw", path, required = true) ?: 0f,
            nearEnter = float(fields, "near_enter", path) ?: 0.65f,
            clearExit = float(fields, "clear_exit", path) ?: 0.30f,
            debounceMs = integer(fields, "debounce_ms", path) ?: 150,
            clearArmMs = integer(fields, "clear_arm_ms", path) ?: 700,
            minimumNearMs = integer(fields, "minimum_near_ms", path) ?: 200,
            maximumNearMs = integer(fields, "maximum_near_ms", path) ?: 4000,
            cooldownMs = integer(fields, "cooldown_ms", path) ?: 1000,
            maxInterWaveGapMs = integer(fields, "max_inter_wave_gap_ms", path) ?: 1800,
        )
    }

    private fun integer(map: Map<String, Any?>, key: String, path: String, required: Boolean = false): Int? {
        val fullPath = if (path == "$") key else "$path.$key"
        if (!map.containsKey(key) || map[key] == null) {
            if (required) issues += error(fullPath, "Required integer is missing.", "required-integer")
            return null
        }
        val number = map[key] as? Number
        if (number == null || number.toDouble() % 1.0 != 0.0 || number.toLong() !in Int.MIN_VALUE..Int.MAX_VALUE) {
            issues += error(fullPath, "Expected a 32-bit integer.", "expected-integer")
            return null
        }
        return number.toInt()
    }

    private fun float(map: Map<String, Any?>, key: String, path: String, required: Boolean = false): Float? {
        val fullPath = "$path.$key"
        if (!map.containsKey(key) || map[key] == null) {
            if (required) issues += error(fullPath, "Required finite number is missing.")
            return null
        }
        val number = map[key] as? Number
        val converted = number?.toFloat()
        if (number == null || !number.toDouble().isFinite() || converted == null || !converted.isFinite()) {
            issues += error(fullPath, "Expected a finite number.", "expected-finite-number")
            return null
        }
        return converted
    }

    private fun boolean(map: Map<String, Any?>, key: String, path: String, required: Boolean = false): Boolean? {
        val fullPath = "$path.$key"
        if (!map.containsKey(key) || map[key] == null) {
            if (required) issues += error(fullPath, "Required boolean is missing.", "required-boolean")
            return null
        }
        return (map[key] as? Boolean) ?: run {
            issues += error(fullPath, "Expected a boolean.", "expected-boolean")
            null
        }
    }

    private fun <T> enum(
        map: Map<String, Any?>,
        key: String,
        path: String,
        values: Iterable<T>,
        default: String?,
        yamlName: (T) -> String,
    ): T? {
        val raw = if (map.containsKey(key)) string(map, key, path, required = true) else default
        if (raw == null) return null
        return values.firstOrNull { yamlName(it) == raw } ?: run {
            issues += error("$path.$key", "Unknown value '$raw'.", "unknown-value", mapOf("value" to raw))
            null
        }
    }
}

internal fun ProfileDocument.toYamlMap(): Map<String, Any?> = linkedMapOf(
    "schema" to schema,
    "id" to id,
    "version" to version,
    "display_name" to displayName,
    "soc_class" to socClass,
    "soc" to soc?.let {
        linkedMapOf(
            "model" to it.model,
            "introduced_year" to it.introducedYear,
            "cpu_cores" to it.cpuCores.map { cluster ->
                linkedMapOf("architecture" to cluster.architecture, "count" to cluster.count)
            }.takeIf { clusters -> clusters.isNotEmpty() },
        ).withoutNullValues()
    },
    "metadata" to linkedMapOf(
        "author" to metadata.author,
        "source" to metadata.source,
        "license" to metadata.license,
        "maturity" to metadata.maturity.name.lowercase(),
        "tested_firmware" to metadata.testedFirmware,
        "limitations" to metadata.limitations,
        "links" to metadata.links.map { linkedMapOf("label" to it.label, "url" to it.url) }
            .takeIf { it.isNotEmpty() },
    ).withoutNullValues(),
    "requires" to linkedMapOf(
        "min_core_version" to requires.minCoreVersion,
        "drivers" to requires.drivers.toList().sorted(),
    ).withoutNullValues(),
    "match" to linkedMapOf(
        "priority" to match.priority,
        "fallback" to match.fallback,
        "any" to match.any.map { group ->
            linkedMapOf("priority" to group.priority, "all" to group.all.map { predicate ->
                linkedMapOf(
                    "field" to predicate.field.yamlName,
                    "op" to predicate.op.yamlName,
                    "values" to predicate.values,
                )
            })
        },
    ),
    "platform" to linkedMapOf(
        "su_form" to platform.suForm,
        "app_can_su" to platform.appCanSu,
        "has_recents" to platform.hasRecents,
        "has_native_navbar" to platform.hasNativeNavbar,
        "launcher" to platform.launcher.vendorHomePackages.takeIf { it.isNotEmpty() }
            ?.let { linkedMapOf("vendor_home_packages" to it) },
    ).withoutNullValues(),
    "hardware" to linkedMapOf(
        "led" to linkedMapOf<String, Any?>("mechanism" to hardware.led.mechanism)
            .apply { putAll(hardware.led.curve.toYamlFields()) }.withoutNullValues(),
        "screen_off" to hardware.screenOff,
        "has_button_backlight" to hardware.hasButtonBacklight,
        "zigbee_gateway_dir" to hardware.zigbeeGatewayDir,
        "relay_base" to hardware.relayBase,
        "relay_base_fallbacks" to hardware.relayBaseFallbacks.takeIf { it.isNotEmpty() },
        "button_led_gpio_base" to hardware.buttonLedGpioBase,
        "touch_click_gain" to hardware.touchClickGain,
        "camera" to hardware.cameraDeclared,
        "microphone" to hardware.hasMicrophone,
        // Written only when false, so profiles that never declared it keep their canonical form.
        "speaker" to hardware.hasSpeaker.takeUnless { it },
        "camera_lens_offset_px" to hardware.cameraLensOffsetPx,
        "backlight" to hardware.backlight?.let { backlight ->
            backlight.curve.toYamlFields().apply { put("route", backlight.route) }.withoutNullValues()
        },
        "button_backlight" to hardware.buttonBacklight?.toYamlFields()?.withoutNullValues(),
    ).withoutNullValues(),
    "sensors" to linkedMapOf(
        "proximity_technology" to sensors.proximityTechnology,
        "proximity_gpio" to sensors.proximityGpio,
        "proximity_calibration" to sensors.proximityCalibration?.let {
            linkedMapOf(
                "format_version" to it.formatVersion.takeUnless { version -> version == 1 },
                "presence_supported" to it.presenceSupported.takeUnless { supported -> supported },
                "wave" to it.wave?.let { wave ->
                    linkedMapOf(
                        "pattern" to wave.pattern, "clear_raw" to wave.clearRaw, "near_raw" to wave.nearRaw,
                        "near_enter" to wave.nearEnter, "clear_exit" to wave.clearExit,
                        "debounce_ms" to wave.debounceMs, "clear_arm_ms" to wave.clearArmMs,
                        "minimum_near_ms" to wave.minimumNearMs, "maximum_near_ms" to wave.maximumNearMs,
                        "cooldown_ms" to wave.cooldownMs, "max_inter_wave_gap_ms" to wave.maxInterWaveGapMs,
                    )
                },
                "revision" to it.revision, "mode" to it.mode,
                "clear_raw" to it.clearRaw, "near_raw" to it.nearRaw,
                "verification" to it.verification,
                "near_enter" to it.nearEnter, "clear_exit" to it.clearExit,
                "debounce_ms" to it.debounceMs, "clear_arm_ms" to it.clearArmMs,
                "minimum_near_ms" to it.minimumNearMs, "maximum_near_ms" to it.maximumNearMs,
                "cooldown_ms" to it.cooldownMs,
            ).withoutNullValues()
        },
        "light_technology" to sensors.lightTechnology,
        "cht8305" to sensors.cht8305,
        "vi530x" to sensors.vi530x,
        "room_temp_offset_c" to sensors.roomTempOffsetC,
    ).withoutNullValues(),
    "identity" to linkedMapOf(
        "manufacturer" to identity.manufacturer,
        "model" to identity.model,
        "model_label_strategy" to identity.modelLabelStrategy,
    ).withoutNullValues(),
    "input" to linkedMapOf("evdev_buttons" to input.evdevButtons.map {
        linkedMapOf("node" to it.node, "code" to it.code, "grab" to it.grab, "event_type" to it.eventType, "sw" to it.sw)
    }),
    "cpu" to linkedMapOf("governors" to cpu.governors).withoutNullValues(),
    "display" to linkedMapOf(
        "physical_ppi" to display.physicalPpi,
        "geometry" to display.geometry.takeIf { it.isNotEmpty() }?.map {
            linkedMapOf(
                "variant" to it.variant,
                "product_version_prefixes" to it.productVersionPrefixes.takeIf { prefixes -> prefixes.isNotEmpty() },
                "width_px" to it.widthPx,
                "height_px" to it.heightPx,
                "active_diagonal_in" to it.activeDiagonalIn,
                "active_width_mm" to it.activeWidthMm,
                "active_height_mm" to it.activeHeightMm,
                "factory_base_dpi" to it.factoryBaseDpi,
                "evidence" to it.evidence.yamlName,
                "evidence_note" to it.evidenceNote,
            ).withoutNullValues()
        },
    ).withoutNullValues(),
    "provisioning" to linkedMapOf(
        "access" to provisioning.access.shizuku
            .takeUnless { it == ShizukuRecommendation.NONE }
            ?.let { linkedMapOf("shizuku" to it.name.lowercase()) },
        "software" to linkedMapOf(
            "webview" to provisioning.software.webView?.let {
                linkedMapOf("artifact" to it.artifact)
            },
            "companion" to provisioning.software.companion?.let {
                linkedMapOf("max_version" to it.maxVersion)
            },
        ).withoutNullValues(),
        "display" to linkedMapOf(
            "density" to when (val density = provisioning.display.density) {
                is ProfileDensity.Fixed -> density.value
                is ProfileDensity.Strategy -> density.id
                null -> null
            },
            "font_scale" to provisioning.display.fontScale,
        ).withoutNullValues(),
        "packages" to provisioning.packages.map {
            linkedMapOf(
                "package" to it.packageName,
                "desired_state" to it.desiredState.yamlName,
                "importance" to it.importance.yamlName,
                "tags" to it.tags,
                "note" to it.note,
            )
        },
        "recipes" to provisioning.recipes.map { linkedMapOf("id" to it.id) },
    ).withoutNullValues(),
).withoutNullValues()

private fun <K, V> LinkedHashMap<K, V?>.withoutNullValues(): LinkedHashMap<K, V> {
    val result = LinkedHashMap<K, V>()
    forEach { (key, value) -> if (value != null) result[key] = value }
    return result
}

private fun error(
    path: String,
    message: String,
    presentationCode: String? = null,
    presentationParams: Map<String, String> = emptyMap(),
) = ProfileIssue(
    ProfileIssueSeverity.ERROR,
    path.removePrefix("$."),
    message,
    presentationCode?.let { runCatching { ProfilePresentation(it, presentationParams) }.getOrNull() },
)

private val CURVE_KEYS = setOf("transfer", "gamma", "points", "floor")

private fun ProfileLightCurve.toYamlFields(): LinkedHashMap<String, Any?> = linkedMapOf(
    "transfer" to transfer,
    "gamma" to gamma,
    "points" to points?.map { (request, level) -> listOf(request, level) },
    "floor" to floor,
)
