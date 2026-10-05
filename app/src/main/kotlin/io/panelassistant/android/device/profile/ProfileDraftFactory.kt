package io.panelassistant.android.device.profile

/** Builds an inert, schema-valid starting point from passive/cached evidence only. */
object ProfileDraftFactory {
    fun create(
        facts: DeviceFacts,
        report: PassiveProfileReport? = null,
        author: String = "Community contributor",
    ): PassiveProfileDraft {
        val normalized = facts.normalized()
        val observations = report?.observations.orEmpty()
        val predicates = buildList {
            if (normalized.model.isNotBlank()) add(exact(ProfileFact.MODEL, normalized.model))
            if (normalized.device.isNotBlank()) add(exact(ProfileFact.DEVICE, normalized.device))
            if (normalized.productVersion.isNotBlank()) add(exact(ProfileFact.PRODUCT_VERSION, normalized.productVersion))
            if (isEmpty()) add(exact(ProfileFact.MODEL, "unknown"))
        }
        val seed = listOf(normalized.model, normalized.device, normalized.productVersion).joinToString("|")
        val hashSuffix = ProfileYaml.sha256(seed).take(10)
        val labelSeed = normalized.model.ifBlank { normalized.device.ifBlank { "unknown-panel" } }
        val slug = labelSeed.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("").replace(Regex("-+"), "-").trim('-').take(40).ifBlank { "unknown-panel" }
        val profileId = "community.$slug.$hashSuffix"
        val light = observed(observations, "sensors.light_technology")
            ?: "Ambient light".takeIf { observedBoolean(observations, "sensors.light") == true }
        val proximity = observed(observations, "sensors.proximity_technology")
            ?: "Android proximity sensor".takeIf { observedBoolean(observations, "sensors.proximity") == true }
        val physicalPpi = observed(observations, "display.physical_ppi")?.toIntOrNull()?.takeIf { it in 50..1000 }
        val model = observed(observations, "identity.model")
        val manufacturer = observed(observations, "identity.manufacturer")
        val limitations = listOf(
            "TODO: verify privileged capabilities and add only the required core driver ids.",
            "TODO: test automatic matching against firmware variants and avoid broad fingerprints.",
            "TODO: document hardware evidence before changing maturity from draft.",
        )
        val document = ProfileDocument(
            schema = ProfileMetadata.SCHEMA,
            id = profileId,
            version = "0.1.0",
            displayName = labelSeed.take(100),
            socClass = "Unknown",
            metadata = ProfileProvenance(
                author = author.take(100).ifBlank { "Community contributor" },
                source = null,
                license = "MIT",
                maturity = ProfileMaturity.DRAFT,
                testedFirmware = normalized.productVersion.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty(),
                limitations = limitations,
            ),
            requires = ProfileRequirements(
                drivers = buildSet {
                    add("screen.brightness-zero")
                    if (light != null || proximity != null) add("sensor.android")
                },
            ),
            match = ProfileMatch(priority = 500, fallback = false, any = listOf(ProfileMatchGroup(priority = 900, all = predicates))),
            platform = ProfilePlatform(
                suForm = "none",
                appCanSu = false,
                hasRecents = true,
                // A draft describes unverified hardware, so it never claims a native navigation bar.
                hasNativeNavbar = null,
            ),
            hardware = ProfileHardware(
                led = ProfileLed("none", "identity"),
                screenOff = "brightness-zero",
            ),
            sensors = ProfileSensors(
                proximityTechnology = proximity,
                lightTechnology = light,
            ),
            identity = ProfileIdentity(manufacturer = manufacturer, model = model),
            input = ProfileInput(),
            cpu = ProfileCpu(),
            display = ProfileDisplay(physicalPpi = physicalPpi),
            provisioning = ProfileProvisioning(),
        )
        val issues = buildList {
            addAll(report?.issues.orEmpty())
            add(
                ProfileIssue(
                    ProfileIssueSeverity.INFO,
                    "metadata.limitations",
                    "Draft TODOs are recorded as limitations; unsupported capabilities remain disabled.",
                    ProfilePresentation("draft-todos-recorded-as-limitations"),
                ),
            )
        }
        return PassiveProfileDraft(ProfileYaml.serialize(document), report ?: emptyReport(facts), issues)
    }

    fun fromReport(report: PassiveProfileReport, author: String = "Community contributor"): PassiveProfileDraft =
        create(report.facts, report, author)

    private fun exact(fact: ProfileFact, value: String) = ProfilePredicate(fact, ProfileMatchOp.EQUALS, listOf(value))

    private fun observed(observations: List<PassiveProfileObservation>, path: String): String? =
        observations.lastOrNull { it.path == path && it.confidence == PassiveProfileConfidence.OBSERVED }
            ?.value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 100 }

    private fun observedBoolean(observations: List<PassiveProfileObservation>, path: String): Boolean? =
        observed(observations, path)?.toBooleanStrictOrNull()

    private fun emptyReport(facts: DeviceFacts) = PassiveProfileReport(
        generatedAtEpochMs = 0,
        facts = facts,
        observations = emptyList(),
    )
}
