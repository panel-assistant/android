package io.panelassistant.android.device.profile

internal fun testProfileDocument(
    id: String = "community.example.test-panel",
    version: String = "1.0.0",
    facts: DeviceFacts = DeviceFacts("test-panel", "test-device", "fw-1"),
    fallback: Boolean = false,
) = ProfileDocument(
    schema = ProfileMetadata.SCHEMA,
    id = id,
    version = version,
    displayName = "Test panel",
    socClass = "Test SoC",
    metadata = ProfileProvenance(
        author = "Test author",
        source = "https://example.com/profile",
        license = "MIT",
        maturity = ProfileMaturity.DRAFT,
        testedFirmware = listOf("fw-1"),
        limitations = listOf("Test-only profile."),
    ),
    requires = ProfileRequirements(drivers = setOf("screen.brightness-zero")),
    match = ProfileMatch(
        priority = 500,
        fallback = fallback,
        any = if (fallback) emptyList() else listOf(
            ProfileMatchGroup(
                priority = 900,
                all = listOf(ProfilePredicate(ProfileFact.MODEL, ProfileMatchOp.EQUALS, listOf(facts.model.lowercase()))),
            ),
        ),
    ),
    platform = ProfilePlatform("none", appCanSu = false),
    hardware = ProfileHardware(ProfileLed("none"), "brightness-zero"),
    sensors = ProfileSensors(),
    identity = ProfileIdentity(),
    input = ProfileInput(),
    cpu = ProfileCpu(),
    display = ProfileDisplay(),
    provisioning = ProfileProvisioning(),
)
